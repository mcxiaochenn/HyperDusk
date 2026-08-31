# HyperDusk

HyperDusk 是面向 Xiaomi 17 Pro HyperOS 4 的 libxposed API 102 Hook 模块。MVP 通过 `system_server` 原生 `MiuiSingleKeyRuleManager` 将实体音量上键双击映射为系统手电筒动作，不启动后台监听进程，也不使用无障碍服务。

## 支持范围

首个验证目标为 Xiaomi 17 Pro（`pandora`）、Android 17、HyperOS `OS4.0.0.27.XBLCNXM`。其他小米/红米 HyperOS 4 设备仅属于待验证范围。

设置页提供两个动作：`未设置`（默认，保持原音量行为）和 `切换手电筒`。启用或关闭后，在 LSPosed 中重启一次 system_server/设备即可应用。

## 构建

需要 JDK 25、Android SDK Platform 37 和 Build Tools 37.0.0：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
$env:ANDROID_HOME = "D:\Android\Sdk"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon
```

Debug APK 位于 `app/build/outputs/apk/debug/`。构建不需要 GitHub Packages 凭据或仓库 Secrets。

## 安装与回退

使用 Android 包管理器安装 APK，在 LSPosed IT 中仅启用 HyperDusk 的 `system` 作用域并重启。关闭设置中的动作即可恢复原行为；也可以在 LSPosed 禁用模块并重启，或卸载 APK。项目不会写入 `/system`、`/data/adb` 或 LSPosed 配置目录。

## 许可证与来源

本项目以 AGPL-3.0 发布。它基于 HyperCeiler（保留完整 Git 历史）裁剪并修改，修改范围记录在 [NOTICE.md](NOTICE.md) 和验证文档中。
