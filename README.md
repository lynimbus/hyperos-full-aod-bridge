# HyperOS Full AOD Bridge（ROM 侧补丁）

在 AOD 恢复边沿（OFF→DOZE）立即点亮面板的 ROM 侧补丁，修复可能的唤醒失效问题。  
> **此版本可能为冗余修改，具体效果请自行测试**  

> **手工操作请参阅 [手动修补指南](手动修补指南.md)。**

## 版本历史

- **v3（当前）**：桥直接写面板亮度（`SurfaceControl.setDisplayBrightness`），绕过原方法
  ——Full AOD 状态下原方法会走"正常亮度"分支调用 `updateDozeBrightness(0)` 把 doze 亮度
  清零（实机 dmesg：relight 时刻写 backlight 0）。新增 LHBM 补写：点击屏幕唤醒 AOD 会触发
  指纹认证的 Local HBM 窗口（~360ms），窗口内内核拒绝背光写入，补写 400ms 后重写一次，
  解决"点击唤醒无效，需遮挡再放开摄像头才能点亮"的问题。armed→relight 实测 ~213ms，其中
  `setDisplayPowerMode(NORMAL)` 约 188ms 是面板硬件切换，无法从框架层缩短。
- **v2**：亮度请求边沿 relight（`NORMAL -> 写亮度 -> DOZE`），功能完整。
- **v1**：早期实现，因字符串拼接在 boot classpath 上触发 `invoke-custom` 导致
  system_server abort（ART 解析 `StringConcatFactory` 时 `Runtime::Abort`），尝试唤醒aod时崩溃；
  单一 16ms 延迟还因面板 LP1 未完全退出而丢失写亮度竞态。

## 许可

[MIT](LICENSE)
