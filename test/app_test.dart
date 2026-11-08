import 'package:flutter/material.dart';
import 'package:flutter_miuix/miuix.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hyperdusk/src/app.dart';
import 'package:hyperdusk/src/core/app_controller.dart';
import 'package:hyperdusk/src/core/core_gateway.dart';
import 'package:hyperdusk/src/platform/xposed_gateway.dart';
import 'package:hyperdusk/src/ui/about_page.dart';
import 'package:hyperdusk/src/ui/format/action_label.dart';

void main() {
  testWidgets('就绪状态展示框架连接、动作名与四个作用域', (tester) async {
    await _pumpHome(
      tester,
      core: const _ReadyCoreGateway(),
      xposed: const _FakeXposedGateway(
        status: _connectedStatus,
        module: _connectedModule,
      ),
    );

    expect(find.text('已连接'), findsOneWidget);
    expect(find.text('LSPosed 2.2.1 · API 102'), findsOneWidget);
    expect(find.text('手电筒'), findsOneWidget);
    // 行尾只显示双击动作，三击不再拼在行尾（避免 miuix endActions 槽位溢出）。
    expect(find.text('双击 · 相机'), findsOneWidget);
    for (final title in ['系统框架', '系统设置', '系统界面', '安全核心']) {
      expect(find.text(title), findsOneWidget);
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets('框架未连接时开关禁用但设置值照常展示', (tester) async {
    await _pumpHome(
      tester,
      core: const _ReadyCoreGateway(),
      xposed: const _FakeXposedGateway(
        status: _unavailableStatus,
        module: _unavailableModule,
      ),
    );

    expect(find.text('未连接'), findsOneWidget);
    expect(find.text('框架未连接，无法修改'), findsOneWidget);
    // 框架断开不影响 Settings.System 的值展示。
    expect(find.text('相机'), findsOneWidget);

    final preference = tester.widget<MiuixSwitchPreference>(
      find.byType(MiuixSwitchPreference),
    );
    expect(preference.enabled, isFalse);
    expect(preference.value, isFalse);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Rust 初始化失败展示重试按钮且其余区块可用', (tester) async {
    await _pumpHome(
      tester,
      core: const _FailingCoreGateway(),
      xposed: const _FakeXposedGateway(
        status: _connectedStatus,
        module: _connectedModule,
      ),
    );

    expect(find.text('Rust 核心初始化失败'), findsOneWidget);
    expect(find.text('重试'), findsOneWidget);
    // Rust 失败只替换状态卡，手势/作用域/关于仍按模块状态渲染。
    expect(find.text('手电筒'), findsOneWidget);
    expect(find.text('系统框架'), findsOneWidget);
    expect(find.text('关于 HyperDusk'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('关于页展示版本、包名与许可证入口', (tester) async {
    await _pumpHome(
      tester,
      core: const _ReadyCoreGateway(),
      xposed: const _FakeXposedGateway(
        status: _connectedStatus,
        module: _connectedModule,
      ),
    );

    await tester.tap(find.text('关于 HyperDusk'));
    await tester.pumpAndSettle();

    // 版本号在「版本」与「Rust 核心」两行都出现（两者都是 0.0.1），所以用 findsWidgets。
    expect(find.text('0.0.1'), findsWidgets);
    expect(find.text('com.mcxiaochen.hyperdusk'), findsOneWidget);

    // 许可证入口在「项目」卡片里，需要滚动到可视区域再断言（规格 §6.4）。
    final aboutScrollable = find.descendant(
      of: find.byType(AboutPage),
      matching: find.byType(Scrollable),
    );
    await tester.scrollUntilVisible(
      find.text('查看开源许可'),
      200,
      scrollable: aboutScrollable,
    );
    expect(find.text('查看开源许可'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  group('动作名映射', () {
    test('已知 function 值映射为中文名', () {
      expect(actionLabel('turn_on_torch'), '手电筒');
      expect(actionLabel('launch_camera'), '相机');
      expect(actionLabel('launch_smarthome'), '小米一指连');
      expect(actionLabel('mi_pay'), '公交·Mi Pay·门卡');
      expect(actionLabel('screen_shot'), '截屏');
    });

    test('none / null / 空串统一显示未设置', () {
      expect(actionLabel(null), '未设置');
      expect(actionLabel('none'), '未设置');
      expect(actionLabel('   '), '未设置');
    });

    test('未知值原样返回', () {
      expect(actionLabel('launch_screen_record'), 'launch_screen_record');
    });
  });

  group('ModuleState 解析', () {
    test('缺失字段按降级值填充', () {
      final state = ModuleState.fromMap(<String, Object?>{
        'available': true,
        'enabled': false,
      });
      expect(state.available, isTrue);
      expect(state.enabled, isFalse);
      expect(state.doubleClickVolumeUp, isNull);
      expect(state.doubleClickVolumeUpLabel, '未设置');
      // 原生侧没给 scopes 时退回固定四项。
      expect(state.scopes, kModuleScopes);
    });

    test('scopes 只保留字符串项', () {
      final state = ModuleState.fromMap(<String, Object?>{
        'available': false,
        'enabled': false,
        'scopes': <Object?>['system', 42, 'com.android.settings'],
      });
      expect(state.scopes, ['system', 'com.android.settings']);
    });
  });
}

/// 统一在手机上尺寸的大画布上跑：首页四组卡片在 800×600 的默认测试画布上
/// 放不下，断言「作用域四行」时会被 ListView 懒加载挡住。
Future<void> _pumpHome(
  WidgetTester tester, {
  required CoreGateway core,
  required XposedGateway xposed,
}) async {
  tester.view.physicalSize = const Size(800, 2800);
  tester.view.devicePixelRatio = 2.0;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(
    HyperDuskApp(
      controller: AppController(coreGateway: core, xposedGateway: xposed),
    ),
  );
  await tester.pumpAndSettle();
}

const _connectedStatus = XposedStatus(
  connected: true,
  message: '框架服务已连接。',
  frameworkName: 'LSPosed',
  frameworkVersion: '2.2.1',
  apiVersion: 102,
);

const _unavailableStatus = XposedStatus.unavailable();

const _connectedModule = ModuleState(
  available: true,
  enabled: true,
  doubleClickVolumeUp: 'turn_on_torch',
  backDoubleTap: 'launch_camera',
  backTripleTap: 'screen_shot',
);

/// 框架不可读写，但 Settings 值仍然存在（规格 §3.2 的第二种情况）。
const _unavailableModule = ModuleState(
  available: false,
  enabled: false,
  doubleClickVolumeUp: 'launch_camera',
);

class _ReadyCoreGateway implements CoreGateway {
  const _ReadyCoreGateway();

  @override
  Future<CoreSnapshot> initialize() async => const CoreSnapshot(
    ready: true,
    coreVersion: '0.0.1',
    dataSchemaVersion: 1,
  );

  @override
  AppAboutInfo getAboutInfo() => const AppAboutInfo(
    appName: 'HyperDusk',
    appVersion: '0.0.1',
    packageName: 'com.mcxiaochen.hyperdusk',
    architecture: 'arm64-v8a',
    coreVersion: '0.0.1',
    license: 'MIT',
  );
}

class _FailingCoreGateway implements CoreGateway {
  const _FailingCoreGateway();

  @override
  Future<CoreSnapshot> initialize() =>
      Future<CoreSnapshot>.error(StateError('测试故障'));

  @override
  AppAboutInfo getAboutInfo() => throw StateError('测试故障');
}

class _FakeXposedGateway implements XposedGateway {
  const _FakeXposedGateway({required this.status, required this.module});

  final XposedStatus status;
  final ModuleState module;

  @override
  Future<XposedStatus> getStatus() async => status;

  @override
  Future<ModuleState> getModuleState() async => module;

  @override
  Future<bool> setModuleEnabled(bool enabled) async => false;

  @override
  Future<bool> openSettings(String target) async => true;

  @override
  Future<bool> openUrl(String url) async => true;
}
