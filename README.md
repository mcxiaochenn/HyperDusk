# HyperDusk

HyperDusk 是面向 HyperOS 4.0 / Android 17（API 37）的独立现代 libxposed 模块。它不替换系统应用，而是在系统设置里扩展快捷手势：为「背部轻敲」增加手电筒选项，并新增「双击音量上键」手势。

应用本体使用 Flutter 渲染状态页、Rust 提供核心状态；Android 端负责 libxposed 模块入口、框架服务桥接，以及注入到系统进程里执行手势与 UI 注入的 Hook 代码（实现位于 `android/app/src/main/kotlin/com/mcxiaochen/hyperdusk/`）。

## 功能

### 背部轻敲 → 手电筒

- 入口：设置 → 辅助功能 → 快捷手势 → 背部轻敲。
- 模块给「轻敲两下 / 轻敲三下」的下拉列表增加「手电筒」选项；选择后双击或三击背部即可开关手电筒。
- 手电筒由系统执行：`system_server` 发出 `miui.intent.action.TOGGLE_TORCH` 广播，SystemUI 的 `MiuiFlashlightControllerImpl` 实际控制 LED。
- 系统原本会在启动/切换用户时把背部轻敲上的 `turn_on_torch` 强制改写成 `none`，模块拦截了这一步，使配置保留。
- 背部轻敲 sensor 由系统只在亮屏时注册（MIUI 原生行为），因此灭屏时轻敲不会触发。

### 快捷手势 → 双击音量上键

- 入口：设置 → 辅助功能 → 快捷手势 → 自定义手势 → 「双击音量上键」（模块在列表中新增的一行）。
- 点击该行进入的二级菜单直接复用「双击电源键」页面，选项与系统当前配置保持一致；中国版静态推导为：相机 / 手电筒 / 公交·Mi Pay·门卡 / 小米一指连 / 无操作。
- 触发条件与系统「双击音量下极速相机」同款：只有**灭屏**时（AOD/doze 也算灭屏）双击音量上键才执行所选动作，触发前会先唤醒屏幕；**亮屏状态（即使锁屏未解锁）不触发，音量键行为完全不变**；**媒体播放/通话中不触发**。
- 配置存储：`Settings.System` 的字符串键 `hyperdusk_double_click_volume_up`，取值为上述功能值（`none` 或空表示不触发）。
- 查看当前配置：

```powershell
adb shell settings get system hyperdusk_double_click_volume_up
```

## 作用域与安装

前提：HyperOS 4.0 / Android 17 真机，以及实现了 libxposed API 102 的框架（如 LSPosed 2.x / Vector 2.2；`system_server` Hook 还需要框架支持 `PROP_CAP_SYSTEM`）。

1. 安装构建出的 debug APK（见下方「验证与构建」）。
2. 在 LSPosed / Vector 管理器中启用 HyperDusk。模块作用域由 `scope.list` 固定（`staticScope=true`），共四项，无需手动勾选、也不能新增列表外的应用：
   - `system`（system_server）
   - `com.android.settings`
   - `com.android.systemui`
   - `com.miui.securitycore`
3. 重启手机：`system_server` 与 `com.miui.securitycore` 里的 Hook 只在进程启动时安装；只重装模块 APK 时，需要重启 `securitycore` 进程（或重启手机）才会重新注入。
4. 打开系统设置里的对应入口配置功能。

补充说明：

- libxposed API 101 起模块 App 自身不再被注入，因此配置入口在系统设置内，模块 App 只提供状态页与框架服务连接。
- `com.android.settings` / `com.android.systemui` 目前只安装了注入探针 Hook（每个进程打一条日志，用于确认注入成功），不改变这两个进程的行为；它们的业务能力在后续版本。
- 修改 Hook 代码后必须重装 APK，并重启手机（或至少重启对应系统进程）才会生效（`module.prop` 中 `autoHotReload=false`）。

## 已验证与未验证的边界

- CI（`.github/workflows/verify.yml`）验证：代码生成物无 diff、Rust 格式/测试/clippy、Flutter analyze/test、legacy 符号扫描、debug + release APK 构建、签名证书指纹校验与 APK 结构审计。
- **功能行为尚未在真机上完成验证**：所有 Hook 是否真正回调、设置页注入是否生效、手势端到端是否可用，都需要在真机上确认。待验证清单见「真机验证清单」。

## 已知限制

- **securitycore Hook 依赖该应用的内部实现**：部分类名/成员被混淆，代码里带运行时结构守卫与失败降级；securitycore 版本升级后 UI 注入可能失效（需要重新适配）。本版按 securitycore `4.4.4.4-4-260914` 的逆向结果实现。
- **灭屏双击音量上键会同时调两次音量**：检测方式是旁路观察、不吞按键（与系统「双击音量下极速相机」相同的既有行为），随后才执行所选动作。
- **媒体播放/通话中不触发**：与系统「双击音量下极速相机」一致，检测到音频活跃（通话/录音/媒体流）时会丢弃该手势，避免打断媒体控制。
- **背部轻敲只在亮屏时可用**：sensor 由系统在亮屏时注册，灭屏时不会触发（原生行为，模块未改变）。
- **`system_server` Hook 依赖框架能力**：需要框架支持 `PROP_CAP_SYSTEM`；HyperOS 4 的部分系统进程注入还依赖框架版本（如 HYOS spawner 支持）。如果 Xposed 日志里没有对应进程的加载记录，应先排查框架而不是代码。
- **国际版未适配**：国际版「双击电源键」页会直接关闭，因此「双击音量上键」入口只在非国际版注入；其余功能按中国版验证。
- **模块 App 内还没有配置页**：配置在系统设置内完成，应用内状态页文案尚未随本版功能更新。

## 环境

- Flutter 3.47.1 / Dart 3.13.1
- Rust 1.98.0
- JDK 21 或更高版本
- Android SDK 37
- Android NDK 28.2.13676358
- flutter_rust_bridge 2.14.0-beta.2

## 验证与构建

本地验证与打包命令：

```powershell
flutter pub get
flutter_rust_bridge_codegen generate

Push-Location rust
cargo fmt --check
cargo test
cargo clippy --all-targets -- -D warnings
Pop-Location

flutter analyze
flutter test --no-pub
flutter build apk --debug --no-pub --target-platform android-arm64,android-x64
```

Debug APK 位于 `build/app/outputs/flutter-apk/app-debug.apk`。它使用仓库内固定的 debug 签名，不作为正式发布包。

### 签名

- **debug**：CI 与本地构建固定使用仓库内的 `android/app/debug.keystore`（别名 `androiddebugkey`），
  因此不同次构建的 debug APK 签名一致，可以直接覆盖安装/升级；证书 SHA-256 指纹记录在 `.research/notes/signing.md`。
- **release**：release keystore 不进仓库；CI 从 GitHub Secrets（`RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、
  `RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`）解码后签名，并校验证书指纹与固定值一致。
- 本地构建 release 时，设置 `RELEASE_KEYSTORE_FILE`（keystore 路径）、`RELEASE_KEYSTORE_PASSWORD`、
  `RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 即可使用正式签名；未设置时 release 构建退化为 debug 签名（仅用于本地验证）。

CI 在此基础上还会：

- 用 `git diff --exit-code` 校验 flutter_rust_bridge 生成物；
- 扫描 legacy 污染符号（禁止旧模块标识符）；
- 构建 debug 与 release 两个 APK（release 使用 Secrets 中的固定 release 签名）；
- 校验两个 APK 的签名证书 SHA-256 指纹与固定值一致（签名漂移直接失败）；
- 审计两个 APK 的内容：`META-INF/xposed/{java_init.list,module.prop,scope.list}` 存在、`scope.list` 必须恰好是上述四行、
  `lib/arm64-v8a/libhyperdusk_core.so` 与 `lib/x86_64/libhyperdusk_core.so` 存在、dex 内无 legacy 符号，并分别输出 SHA256SUMS。

### 真机验证清单

- Xposed 日志中出现：`system_server`、`com.android.settings`、`com.android.systemui`、`com.miui.securitycore` 的加载日志与 Hook 安装日志。
- 背部轻敲页下拉出现「手电筒」；选中后轻敲背部能开关手电筒；重启后配置仍是 `turn_on_torch`。
- 快捷手势页出现「双击音量上键」；二级页标题正确、选中项来自 `hyperdusk_double_click_volume_up`，选择后写入的是该键而不是 `double_click_power_key`；普通「双击电源键」页行为不变。
- 灭屏（含 AOD）时双击音量上键执行所选动作；亮屏（含锁屏未解锁）不触发、媒体播放/通话中不触发；亮屏音量键行为不变。

## 模块边界

- API：libxposed 102（`minApiVersion=102`、`targetApiVersion=102`、`exceptionMode=protective`、`staticScope=true`、`autoHotReload=false`）
- Java 入口：`com.mcxiaochen.hyperdusk.HyperDuskModule`（`java_init.list` 仅此一行）
- 固定作用域：`system`、`com.android.settings`、`com.android.systemui`、`com.miui.securitycore`
- Hook 数量：9 个功能 Hook（system_server 4 个、securitycore 5 个）+ 2 个注入探针（Settings / SystemUI 各 1 个）
- applicationId：`com.mcxiaochen.hyperdusk`
- `compileSdk` / `minSdk` / `targetSdk`：37；ABI：`arm64-v8a`、`x86_64`

## 许可证

HyperDusk 以 [MIT License](LICENSE) 发布。第三方组件的许可信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 以及应用内“查看开源许可”。
