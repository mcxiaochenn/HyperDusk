import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hyperdusk/src/app.dart';
import 'package:hyperdusk/src/core/app_controller.dart';
import 'package:hyperdusk/src/core/core_gateway.dart';
import 'package:hyperdusk/src/platform/xposed_gateway.dart';

void main() {
  testWidgets('首页展示 Rust 就绪与 Xposed 未连接状态', (tester) async {
    await tester.pumpWidget(
      HyperDuskApp(
        controller: AppController(
          coreGateway: const _ReadyCoreGateway(),
          xposedGateway: const _UnavailableXposedGateway(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('独立模块骨架'), findsOneWidget);
    expect(find.text('已就绪 · v0.1.0'), findsOneWidget);
    expect(find.text('未连接'), findsOneWidget);
    expect(find.textContaining('默认作用域为空'), findsOneWidget);
  });

  testWidgets('Rust 初始化失败时保留可用页面', (tester) async {
    await tester.pumpWidget(
      HyperDuskApp(
        controller: AppController(
          coreGateway: const _FailingCoreGateway(),
          xposedGateway: const _UnavailableXposedGateway(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('初始化失败'), findsOneWidget);
    expect(find.textContaining('测试故障'), findsOneWidget);
    expect(find.byTooltip('关于 HyperDusk'), findsOneWidget);
  });

  testWidgets('关于页展示版本、包名与许可证入口', (tester) async {
    await tester.pumpWidget(
      HyperDuskApp(
        controller: AppController(
          coreGateway: const _ReadyCoreGateway(),
          xposedGateway: const _ConnectedXposedGateway(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('服务已连接'), findsOneWidget);
    expect(find.textContaining('LSPosed 2.0 · API 102'), findsOneWidget);
    await tester.tap(find.byTooltip('关于 HyperDusk'));
    await tester.pumpAndSettle();

    expect(find.text('com.mcxiaochen.hyperdusk'), findsOneWidget);
    expect(find.text('0.1.0'), findsNWidgets(2));
    await tester.drag(find.byType(ListView).last, const Offset(0, -500));
    await tester.pumpAndSettle();
    expect(find.text('查看开源许可'), findsOneWidget);
  });

  testWidgets('应用跟随系统深色主题', (tester) async {
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);

    await tester.pumpWidget(
      HyperDuskApp(
        controller: AppController(
          coreGateway: const _ReadyCoreGateway(),
          xposedGateway: const _UnavailableXposedGateway(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final context = tester.element(find.text('HyperDusk').first);
    expect(Theme.of(context).brightness, Brightness.dark);
  });
}

class _ReadyCoreGateway implements CoreGateway {
  const _ReadyCoreGateway();

  @override
  AppAboutInfo getAboutInfo() => const AppAboutInfo(
    appName: 'HyperDusk',
    appVersion: '0.1.0',
    packageName: 'com.mcxiaochen.hyperdusk',
    architecture: 'Flutter UI · Rust core · libxposed API 102',
    coreVersion: '0.1.0',
    license: 'MIT',
  );

  @override
  Future<CoreSnapshot> initialize() async => const CoreSnapshot(
    ready: true,
    coreVersion: '0.1.0',
    dataSchemaVersion: 1,
  );
}

class _FailingCoreGateway implements CoreGateway {
  const _FailingCoreGateway();

  @override
  AppAboutInfo getAboutInfo() => throw StateError('测试故障');

  @override
  Future<CoreSnapshot> initialize() => Future.error(StateError('测试故障'));
}

class _UnavailableXposedGateway implements XposedGateway {
  const _UnavailableXposedGateway();

  @override
  Future<XposedStatus> getStatus() async => const XposedStatus.unavailable();
}

class _ConnectedXposedGateway implements XposedGateway {
  const _ConnectedXposedGateway();

  @override
  Future<XposedStatus> getStatus() async => const XposedStatus(
    connected: true,
    message: '框架服务已连接。',
    frameworkName: 'LSPosed',
    frameworkVersion: '2.0',
    apiVersion: 102,
  );
}
