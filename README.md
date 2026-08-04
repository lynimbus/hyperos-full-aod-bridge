# HyperOS Full AOD Bridge（ROM 侧补丁）

> **如需手工操作，或需了解每一步 smali 改动的细节，请参阅 [手动修补指南](手动修补指南.md)。**

## 背景

全屏 AOD 下智能显示熄灭面板后，恢复时面板仍处于 LP1（doze）状态，厂商显示栈与内核拒绝普通背光写入（`skip set backlight ... due to LP1 on`），屏幕无法重新点亮。

## 原理

新增类 `AodDozeBridge`（编入 `classes2.dex`），在 `LocalDisplayAdapter$LocalDisplayDevice$1` 注入两处：

- `setDisplayState(I)`：开头仅记录 OFF→DOZE 边沿，不操作面板
- `setDisplayBrightness(FF)`：原方法改名为 `aodBridgeSetDisplayBrightness`，新增同名包装：`beginBrightness()` → 原方法 → `endBrightness()`

时序：记录边沿 → 写亮度时先将面板切换为 `NORMAL`，等待 16 ms，写入亮度，等待 64 ms，再切回 `DOZE`。介入需同时满足以下条件：存在待处理边沿、`isFullAodState` 为真、亮度为有限正数。

### 为何必须在写亮度处注入

`DisplayPowerController` 将一次恢复拆分为两个请求：状态请求中的亮度为 `-1.0`，真实亮度约 70 ms 后才到达。仅在状态点注入无法取得亮度值，因此边沿必须跨事件记忆——这正是 `sPendingEdge` 存在的唯一原因。

### 两个延迟常量

面板电源模式与亮度均为异步事务。实测 NORMAL 约 27 ms、亮度写入约 50 ms 生效，仅等待 16 ms 仍会被内核拒绝。`PANEL_SETTLE_MS = 16`、`COMMIT_DELAY_MS = 64` 为可调参数：若日志显示已 relight 但屏幕仍为黑屏，应调大这两个值。

## 仓库结构

```text
build.sh          唯一入口：原厂 services.jar → 补丁包
patch_smali.py    smali 注入，签名不匹配即报错
repack_jar.py     dex 按 4 字节对齐重打包
src/              AodDozeBridge.java（唯一新增类）
stubs/            编译期垫片，不被打进产物
magisk/           刷入包的 module.prop 和 customize.sh
```

## 构建

```shell
./build.sh /path/to/机器上原厂的/services.jar
```

必须使用目标机器自身的 `services.jar`，若不匹配将直接报错，而不会产出无法开机的补丁包。首次运行需联网下载工具至 `.tools/`。产物：

- `out/services.jar` —— 补丁后的框架
- `out/aod-doze-bridge-rom.zip` —— Magisk 刷入包，安装时校验机器 jar 的 sha256，不符即中止

若此前安装过旧版本，须先卸载旧模块并重启，否则安装校验无法通过。

## 安装 / 回滚

刷入后重启即可：校验不符的旧 odex 会被 ART 忽略，仅首次开机略有延迟。卸载模块即可恢复原厂 jar，无需重刷分区。

## 直接替换进 ROM

覆盖 `/system/framework/services.jar`，删除 `oat/arm64/services.{odex,vdex,art}` 及 `.fsv_meta`，按官改包流程处理 AVB / dm-verity。

## 日志

每条日志同时写入 logcat 与 `/data/system/aod_bridge.log`（热重启后仍保留，256 KB 循环覆写）：

```shell
adb shell su -c 'cat /data/system/aod_bridge.log'
```

正常的恢复过程应产生两行日志：

```text
armed OFF->DOZE edge, display=0 state=3
relighting display 0 state=3 with brightness 0.0304
```

若仅出现第一行，说明边沿已记录但亮度请求未命中。通过 `adb shell su -c 'dmesg | grep -iE "backlight|LP1"'` 确认写入是否真正到达面板：出现 `skip set backlight` 说明写入发生在 LP1 之后，需增大延迟；若桥接与 framework 均无输出，则检查 `adb logcat -b crash -d`。

## 许可

[MIT](LICENSE)
