import 'package:flutter/foundation.dart';
import 'package:hyperdusk/src/core/core_gateway.dart';
import 'package:hyperdusk/src/platform/xposed_gateway.dart';

sealed class AppState {
  const AppState();
}

final class AppLoading extends AppState {
  const AppLoading();
}

final class AppReady extends AppState {
  const AppReady({required this.core, required this.xposed});

  final CoreSnapshot core;
  final XposedStatus xposed;
}

final class AppFailure extends AppState {
  const AppFailure({required this.message, required this.xposed});

  final String message;
  final XposedStatus xposed;
}

class AppController extends ValueNotifier<AppState> {
  AppController({required this.coreGateway, required this.xposedGateway})
    : super(const AppLoading());

  final CoreGateway coreGateway;
  final XposedGateway xposedGateway;
  bool _initialized = false;

  Future<void> initialize({bool force = false}) async {
    if (_initialized && !force) return;
    _initialized = true;
    value = const AppLoading();

    final xposedFuture = xposedGateway.getStatus();
    try {
      final core = await coreGateway.initialize();
      final xposed = await xposedFuture;
      if (!core.ready) {
        value = AppFailure(
          message: core.error ?? 'Rust 核心未能完成初始化。',
          xposed: xposed,
        );
        return;
      }
      value = AppReady(core: core, xposed: xposed);
    } catch (error) {
      final xposed = await xposedFuture;
      value = AppFailure(message: 'Rust 核心不可用：$error', xposed: xposed);
    }
  }
}
