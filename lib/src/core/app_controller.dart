import 'package:flutter/foundation.dart';
import 'package:hyperdusk/src/core/core_gateway.dart';
import 'package:hyperdusk/src/platform/xposed_gateway.dart';

/// 页面状态机。三者都携带开关写入后需要整体替换的数据，
/// 因此 `AppReady` 与 `AppFailure` 都持有 [ModuleState]。
sealed class AppState {
  const AppState();
}

final class AppLoading extends AppState {
  const AppLoading();
}

final class AppReady extends AppState {
  const AppReady({
    required this.core,
    required this.xposed,
    required this.module,
  });

  final CoreSnapshot core;
  final XposedStatus xposed;
  final ModuleState module;
}

/// Rust 核心不可用。
///
/// 只降级 Rust 相关信息：框架状态与模块状态仍然可能读得到，
/// 首页据此继续渲染手势/作用域卡片，并把状态卡换成错误卡（规格 §3.2）。
final class AppFailure extends AppState {
  const AppFailure({
    required this.message,
    required this.xposed,
    required this.module,
  });

  final String message;
  final XposedStatus xposed;
  final ModuleState module;
}

/// 应用状态与动作入口。
///
/// 保留 `ValueNotifier<AppState>` 形态（规格 §4），页面只监听一个状态对象；
/// 所有 gateway 调用的异常都在这里或 gateway 内部转成降级值，页面不写 try/catch。
class AppController extends ValueNotifier<AppState> {
  AppController({required this.coreGateway, required this.xposedGateway})
    : super(const AppLoading());

  final CoreGateway coreGateway;
  final XposedGateway xposedGateway;
  bool _initialized = false;
  bool _disposed = false;

  /// 首次进入页面时加载；[force] 为 true 时（下拉刷新/顶栏刷新按钮/重试）
  /// 重新读取框架、模块与 Rust 三方状态。
  Future<void> initialize({bool force = false}) async {
    if (_initialized && !force) return;
    _initialized = true;
    _set(const AppLoading());

    // 框架与模块状态互不依赖，和 Rust 初始化并行发起，尽量缩短加载态停留时间。
    final statusFuture = _readStatus();
    final moduleFuture = _readModuleState();
    final xposed = await statusFuture;
    final module = await moduleFuture;

    try {
      final core = await coreGateway.initialize();
      if (!core.ready) {
        _set(
          AppFailure(
            message: core.error ?? 'Rust 核心未能完成初始化。',
            xposed: xposed,
            module: module,
          ),
        );
        return;
      }
      _set(AppReady(core: core, xposed: xposed, module: module));
    } catch (error) {
      _set(
        AppFailure(
          message: 'Rust 核心不可用：$error',
          xposed: xposed,
          module: module,
        ),
      );
    }
  }

  /// 写入 `module.enabled`。
  ///
  /// 返回是否成功；成功时先本地更新开关显示，避免用户点击后开关状态回弹。
  /// 失败（框架断开、原生写入异常）保持原状态并返回 false，页面不做提示，
  /// 因为总开关本身处于禁用态时用户可见「框架未连接，无法修改」。
  Future<bool> setModuleEnabled(bool enabled) async {
    try {
      final written = await xposedGateway.setModuleEnabled(enabled);
      if (!written) return false;
      _updateModule((module) => module.copyWith(enabled: enabled));
      return true;
    } catch (_) {
      return false;
    }
  }

  /// 打开系统快捷手势 / 背部轻敲设置页。
  Future<bool> openSettings(String target) {
    return _safeBool(() => xposedGateway.openSettings(target));
  }

  /// 用浏览器打开外部链接。
  Future<bool> openUrl(String url) {
    return _safeBool(() => xposedGateway.openUrl(url));
  }

  Future<XposedStatus> _readStatus() async {
    try {
      return await xposedGateway.getStatus();
    } catch (error) {
      return XposedStatus.unavailable('读取框架状态失败：$error');
    }
  }

  Future<ModuleState> _readModuleState() async {
    try {
      return await xposedGateway.getModuleState();
    } catch (_) {
      return const ModuleState.unavailable();
    }
  }

  Future<bool> _safeBool(Future<bool> Function() call) async {
    try {
      return await call();
    } catch (_) {
      return false;
    }
  }

  /// 在当前状态上就地更新模块数据，同时保留 Rust/框架部分。
  void _updateModule(ModuleState Function(ModuleState module) update) {
    switch (value) {
      case AppReady ready:
        _set(
          AppReady(
            core: ready.core,
            xposed: ready.xposed,
            module: update(ready.module),
          ),
        );
      case AppFailure failure:
        _set(
          AppFailure(
            message: failure.message,
            xposed: failure.xposed,
            module: update(failure.module),
          ),
        );
      case AppLoading():
        // 加载中还没有模块数据可更新，写入结果由下一次 initialize 覆盖。
        break;
    }
  }

  void _set(AppState state) {
    if (_disposed) return;
    value = state;
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
