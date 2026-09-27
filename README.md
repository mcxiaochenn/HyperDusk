# HyperDusk

HyperDusk 是一个面向 Android 17（API 37）的独立现代 libxposed 模块骨架。应用使用 Flutter 渲染界面、Rust 提供核心状态，Android 端仅保留 libxposed 服务与 Flutter 的薄桥接。

当前版本不包含任何 Hook，不迁移旧版本的按键或手电筒功能，默认作用域为空，因此不会注入任何系统或应用进程。

## 当前能力

- MIUIX 风格的 Flutter 状态页与关于页，支持系统明暗主题。
- Rust 核心初始化、版本和数据结构状态检查。
- libxposed API 102 模块识别与框架服务状态展示。
- `arm64-v8a` 真机与 `x86_64` 测试构建。

## 环境

- Flutter 3.47.1 / Dart 3.13.1
- Rust 1.98.0
- JDK 21 或更高版本
- Android SDK 37
- Android NDK 28.2.13676358
- flutter_rust_bridge 2.14.0-beta.2

## 验证与构建

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

Debug APK 位于 `build/app/outputs/flutter-apk/app-debug.apk`。它只使用调试签名，不作为正式发布包。

## 模块边界

- API：libxposed 102
- Java 入口：`com.mcxiaochen.hyperdusk.HyperDuskModule`
- 默认作用域：空
- Hook 数量：0
- applicationId：`com.mcxiaochen.hyperdusk`

即使用户以后手动设置作用域，当前入口也只记录加载状态，不会安装 Hook。

## 许可证

HyperDusk 以 [MIT License](LICENSE) 发布。第三方组件的许可信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 以及应用内“查看开源许可”。
