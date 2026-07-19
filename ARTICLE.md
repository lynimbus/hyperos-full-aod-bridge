# 解决米系手机强开全屏AOD后智能显示失效的问题

## 现象

小米从 15 系开始正式提供全屏 AOD，息屏后可以保留与锁屏界面接近的壁纸和样式。一些较早的机型也带有相关的系统框架代码，通过下面两项安全设置即可强制开启：

```shell
su -c 'settings put secure doze_always_on 1'
su -c 'settings put secure full_screen_aod_on 1'
```

官改包和功能补全模块通常还会补上设置界面、资源和机型判断，最终写入的核心开关也是 `full_screen_aod_on`。

强制开启后，第一次锁屏通常可以正常显示，亮度也符合当时的环境。智能显示因为遮挡、暗光或无人注视而关闭屏幕后，条件恢复时 AOD 可能一直保持黑屏。切回普通 AOD，同样的遮挡和注视操作可以正常熄灭和恢复。

实机日志显示，传感器、注视判断和 AOD 内容恢复均在正常运行，系统也持续算出了新的息屏亮度。故障发生在亮度写入屏幕的最后一步。

本文使用小米 14 Pro 完成取证和验证，相关链路属于米系设备共用的显示框架，因此同类机型出现相同症状时也可以参考这套分析。

## 普通 AOD 与全屏 AOD 的亮度通道

屏幕在这段流程中会经过三个物理状态。ON 对应正常亮屏，OFF 对应面板关闭，DOZE 位于两者之间，用较低刷新率和功耗维持息屏画面。

传统 AOD 显示的内容较少，小米为它设置了专用的息屏亮度通道。环境光最终被归入高、低两档，再通过显示功能 mode 25 交给驱动。驱动直接处理“息屏高亮”和“息屏低亮”命令，面板处于 DOZE 时也可以完成档位切换。

全屏 AOD 会显示壁纸和完整锁屏样式，对亮度过渡的要求更高。系统里的 `DozeAutoBrightnessController` 根据环境光计算连续的目标亮度，再通过通用背光通道写给屏幕。

`LocalDisplayAdapter.setDisplayBrightness()` 中的这处分支展示了两条通道的选择过程：

```java
if (!isFullAodState(displayId) && isDoze(mState)) {
    updateDozeBrightness(physicalDisplayId, brightness); // mode 25，高低两档
} else {
    mBacklightAdapter.setBacklight(...);                 // 通用连续亮度
}
```

普通 AOD 的实机日志与反编译结果一致，mode 25 在高、低两个状态之间切换：

```text
DisplayFeatureManagerService: mode=25 value=1
DisplayFeatureHal: DOZE_BRIGHTNESS_STATE modeId=1

DisplayFeatureManagerService: mode=25 value=2
DisplayFeatureHal: DOZE_BRIGHTNESS_STATE modeId=2
```

两档限制来自传统 AOD 的控制接口。面板本身可以保持更多亮度等级：第一次进入全屏 AOD 时，系统会在正常亮屏状态写入一个连续 DBV，面板进入 DOZE 后可以继续保持这个值。

## 第一次锁屏和智能显示恢复的时序差异

第一次锁屏时，面板仍处于 ON。系统先写入自动亮度控制器算出的目标值，随后把面板切入 DOZE。DOZE 会保留已经生效的亮度，因此画面可以正常显示。

```text
第一次锁屏：ON → 写入连续亮度 → DOZE
```

智能显示关闭 AOD 后，面板会进入 OFF。再次满足显示条件时，原系统先把面板从 OFF 切到 DOZE，随后再写连续亮度：

```text
智能显示恢复：OFF → DOZE → 写入连续亮度
```

验证样机的厂商显示组件会在第二种时序中跳过通用亮度写入，日志给出了直接证据：

```text
HWPeripheralDRM::SetPanelBrightness:
Power state 1 pending or aod layer 0!
Skip for setting brightness 48 level
```

后续亮度继续由上层计算并提交，HWC 仍按相同条件跳过。此时屏幕状态已经回到 DOZE，AOD 内容也已准备完成，面板亮度则停留在 OFF 留下的零值。智能显示可以正常关闭画面，恢复阶段却缺少让画面重新可见的非零亮度。

日志中的 `aod layer 0` 表示 HWC 当前记录到零个 AOD 图层，它与电源状态共同参与这次亮度写入检查。这条日志来自用户态的厂商 composer/HWC，亮度命令在进入内核 DRM 前已经被跳过。现有证据由此把失败位置确定在 HWC；内核 DRM 和面板驱动位于更下游，负责真正的面板命令发送。

小米 14 Pro 与 15 Pro 的系统文件对比进一步缩小了范围。两者的 AOD 自动亮度控制器完全相同，普通与全屏亮度通道的上层分流也相同；两机的厂商显示库尺寸和哈希则各不相同。15 Pro 的下层显示实现完成了全屏 AOD 所需的状态配合，14 Pro 则在恢复时序中停在 HWC 的检查处。

## 四字节 HAL 补丁与 mode 25 桥接

根据 `libsdmdal.so` 的反汇编结果，偏移 `0x53F44` 处是一条 AArch64 无条件跳转。原始指令进入拒绝路径，修改后会进入后续亮度设置路径：

```text
偏移：0x53F44
原始：04 00 00 14    b 0x53f54，进入拒绝路径
修改：19 00 00 14    b 0x53fa8，进入后续设置路径
```

这处补丁覆盖的是 `libsdmdal.so` 内部状态为特定值且目标亮度非零时的一次分支选择，普通亮屏和全屏 AOD 都可能经过这里。补丁范围停留在这一条跳转，同一函数更早的状态检查和面板电源时序保持原样。正确挂载补丁库后，系统可以正常启动，AOD 恢复问题仍然存在，HWC 继续记录 `Power state ... pending or aod layer 0`。一条通用亮度命令需要同时满足多项条件，单个跳转补丁尚未形成通往面板的完整路径。

当前证据把失败位置确定在厂商 composer/HWC。继续向下适配需要处理 HWC 状态机、DRM 提交、内核面板驱动以及屏幕固件支持的面板命令，才能形成一条完整的 DOZE 连续调光路径。这个方案与具体厂商库和固件版本紧密相关。

另一项实验把全屏 AOD 接到传统 AOD 的 mode 25 通道。屏幕可以重新出现，亮度随之变成高、低两档，连续控制器的数值还会遇到阈值和单位不一致。最终方案继续使用全屏 AOD 原有的连续亮度通道。

## 修复方法

第一次锁屏已经提供了一条可工作的顺序。智能显示恢复时短暂复现“在 ON 状态写亮度，再进入 DOZE”的过程，就能让厂商显示组件接收亮度，同时保留小米原有的环境光计算结果。

模块采用纯事件驱动，共安装四个 Hook：

`DozeBrightnessStrategyImpl.updateAodMode()` 在全屏 AOD 设置发生变化时触发，模块由此缓存当前模式；`DozeAutoBrightnessController.updateAutoBrightness()` 在系统完成一次环境光计算时触发，模块缓存最终浮点亮度；`LocalDisplayAdapter` 内部的 `setDisplayState()` 在物理屏幕切换状态时触发，用于记录真实的 OFF→DOZE 恢复边沿；同一对象的 `setDisplayBrightness()` 在系统提交亮度时触发，并在刚才记录的边沿执行一次修正。

四处 Hook 都由系统原有函数调用触发，事件之间模块处于空闲状态。16 毫秒等待发生在修正事件内部，每次显示恢复执行一次。

修正过程如下：

```text
OFF → DOZE → 临时 ON → 写入最新亮度 → 等待 16 ms → DOZE
```

`SurfaceControl` 会异步把电源状态和亮度事务提交给 HWC。写入亮度后保留 16 毫秒的提交窗口，可以让亮度事务在返回 DOZE 前完成。早期测试在写入后立即返回 DOZE，日志显示 DOZE 请求先到达，随后处理的亮度再次遇到 `Power state 3 pending`；加入提交窗口后，顺序稳定为 ON 完成、亮度生效、DOZE 完成。

环境光变化会继续触发系统原有的自动亮度事件，模块随之更新缓存。物理 ON→DOZE 操作绑定在下一次 OFF→DOZE 恢复边沿，每次显示恢复执行一次。AOD 保持显示期间，面板维持本次写入的亮度；下一次隐藏并恢复时，再使用当时最新的环境亮度。这样实现了按显示事件更新的连续亮度，并控制了额外的面板状态切换次数。

普通 AOD 会沿 mode 25 通道运行，正常亮屏也沿原有背光策略运行。模块的触发条件包含全屏 AOD 标志、OFF→DOZE 边沿和有效的正亮度，三项同时满足才会执行修正。

## 适用范围

模块由 Java Hook 和资源文件组成，兼容范围由小米 framework 的类、方法和字段结构决定。使用同套全屏 AOD 分流、自动亮度控制器和物理显示回调的 HyperOS 版本，可以直接尝试。目标结构发生变化时，保护模式会终止 Hook 安装，模块保持无动作，再按对应版本调整反射目标即可。

目前完成实机验证的环境是小米 14 Pro（`shennong`）、Android 16、`OS3.0.307.0.WNBCNXM`，MIUI AOD 版本号 `22335101`。验证样机的故障特征为首次全屏 AOD 正常、智能显示执行 OFF→DOZE 恢复后亮度写入被 HWC 跳过。相同特征可以作为其他机型试用和排查的依据。

使用前需要先通过系统设置、官改包或功能补全模块开启全屏 AOD。若普通 AOD 也出现恢复故障，或者日志把问题指向传感器、内容绘制等其他阶段，应沿对应链路继续排查。

## 使用方法

模块需要 root、LSPosed 和支持 libxposed API 101 的管理器。安装后在 LSPosed 中启用模块，作用域选择“系统框架（System Framework / system）”，然后重启设备。

模块下载：[GitHub Releases](https://github.com/silverpoetry/hyperos-full-aod-bridge/releases)

开源地址：[hyperos-full-aod-bridge](https://github.com/silverpoetry/hyperos-full-aod-bridge)
