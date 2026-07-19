# HyperOS Full AOD Bridge

一个用于修复米系设备强制开启全屏 AOD 后，智能显示熄灭便无法重新点亮的 LSPosed 模块。

出现问题时，环境光和注视检测仍然正常，系统也算出了新的息屏亮度；但物理屏幕从 OFF 恢复到 DOZE 后，通用亮度写入被厂商显示组件拒绝。模块只在这次恢复边沿短暂将面板切到 ON，写入小米原自动亮度控制器给出的最新值，等待一次异步提交后立即返回 DOZE。

完整原理、反编译依据、实机日志和方案取舍见《[解决米系手机强开全屏 AOD 后智能显示失效的问题](ARTICLE.md)》。

## 适用范围

模块通过 Java Hook 调整系统框架中的显示恢复时序，适用于具有相同小米显示框架结构，且表现为“全屏 AOD 首次显示正常，智能显示关闭后无法恢复亮度”的设备。

目前验证环境为小米 14 Pro（`shennong`）、Android 16、`OS3.0.307.0.WNBCNXM`、MIUI AOD `22335101`。使用同套框架结构的其他 HyperOS 机型和版本也可以尝试；框架类或字段发生变化时，需要针对相应版本调整反射目标。

使用本模块前，需要通过系统设置、官改包或其他模块启用 `full_screen_aod_on`。

## 安装

从 [Releases](https://github.com/silverpoetry/hyperos-full-aod-bridge/releases) 下载 APK。安装后在 LSPosed 中启用模块，作用域只选择“系统框架（`system`）”，然后重启设备。

应用 ID 为 `io.github.silverpoetry.hyperos.aodbridge`。

## 实现

核心实现只有一个类：[AodDozeBridge.java](app/src/main/java/io/github/silverpoetry/hyperos/aodbridge/AodDozeBridge.java)。它缓存全屏 AOD 状态和原系统算出的最终亮度，记录物理 OFF→DOZE 边沿，并在该边沿第一次提交有效亮度时执行一次：

```text
NORMAL → 原始亮度写入 → 16 ms → DOZE
```

后续环境光变化用于更新缓存，下一次 AOD 恢复显示时应用最新亮度。普通 AOD 和正常亮屏继续使用系统原有策略。

## 构建

项目使用 Android Gradle Plugin 8.5.2、Gradle 8.7、JDK 17 和 Android SDK 36：

```shell
./gradlew assembleDebug
```

## 许可

项目以 [MIT License](LICENSE) 发布。
