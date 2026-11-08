import 'package:flutter/material.dart';
import 'package:flutter_miuix/miuix.dart';
import 'package:hyperdusk/src/core/app_controller.dart';
import 'package:hyperdusk/src/core/app_meta.dart';
import 'package:hyperdusk/src/platform/xposed_gateway.dart';
import 'package:hyperdusk/src/ui/about_page.dart';
import 'package:hyperdusk/src/ui/widgets/end_value_text.dart';
import 'package:hyperdusk/src/ui/widgets/miuix_icon_by_name.dart';
import 'package:hyperdusk/src/ui/widgets/status_pill.dart';

/// 卡片内各行的统一内边距：状态卡与三组卡片共用，保证行高一致。
const EdgeInsets _rowMargin = EdgeInsets.symmetric(
  horizontal: 16,
  vertical: 14,
);

/// 首页：HyperOS 风格设置页。
///
/// 信息架构见规格 §1.1：状态卡 / 手势功能 / 作用域 / 关于 / 版本脚注。
class HomePage extends StatefulWidget {
  const HomePage({required this.controller, super.key});

  final AppController controller;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  // 顶栏折叠与列表滚动必须共享同一个 behavior，否则大标题不会随列表收起。
  late final MiuixExitUntilCollapsedScrollBehavior _scrollBehavior;

  @override
  void initState() {
    super.initState();
    _scrollBehavior = miuixScrollBehavior();
    // 进页面即开始读取；加载态只显示一个居中指示器，不阻塞首帧。
    widget.controller.initialize();
  }

  @override
  void dispose() {
    // behavior 持有 ChangeNotifier，页面销毁时一并释放，避免滚动状态泄漏。
    _scrollBehavior.state.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<AppState>(
      valueListenable: widget.controller,
      builder: (context, state, _) {
        return MiuixScaffold(
          topBar: MiuixTopAppBar(
            title: 'HyperDusk',
            largeTitle: 'HyperDusk',
            subtitle: 'HyperOS 4 · 快捷手势增强',
            scrollBehavior: _scrollBehavior,
            actions: [
              MiuixIconButton(
                onPressed: _refresh,
                child: miuixIconByName(
                  name: 'refresh',
                  fallback: Icons.refresh_rounded,
                  contentDescription: '刷新',
                ),
              ),
              MiuixIconButton(
                onPressed: _openAbout,
                child: miuixIconByName(
                  name: 'info',
                  fallback: Icons.info_outline_rounded,
                  contentDescription: '关于',
                ),
              ),
            ],
          ),
          content: (insets) => _buildContent(context, state, insets),
        );
      },
    );
  }

  Widget _buildContent(
    BuildContext context,
    AppState state,
    EdgeInsets insets,
  ) {
    // 加载中只铺一个居中指示器：此时还没有任何可展示的数据，
    // 先渲染整页骨架再替换会造成明显闪屏（规格 §3.2）。
    if (state is AppLoading) {
      return Padding(
        padding: EdgeInsets.fromLTRB(12, insets.top, 12, insets.bottom),
        child: const Center(child: MiuixCircularProgressIndicator()),
      );
    }

    final theme = MiuixTheme.of(context);
    final module = _moduleOf(state);

    return MiuixScrollBehaviorListener(
      behavior: _scrollBehavior,
      child: ListView(
        // 顶部让位给折叠顶栏，底部额外留 24 的呼吸空间。
        padding: EdgeInsets.fromLTRB(12, insets.top, 12, insets.bottom + 24),
        children: [
          _buildStatusSection(state),
          const SizedBox(height: 16),
          const MiuixSmallTitle('手势功能'),
          _GestureCard(
            module: module,
            onOpenShortcut: () => widget.controller.openSettings('shortcut'),
            onOpenBackTap: () => widget.controller.openSettings('backtap'),
          ),
          const SizedBox(height: 16),
          const MiuixSmallTitle('作用域'),
          _ScopeCard(scopes: module.scopes),
          const SizedBox(height: 16),
          const MiuixSmallTitle('关于'),
          _AboutCard(
            onAbout: _openAbout,
            onProjectHome: () => widget.controller.openUrl(kProjectHomeUrl),
          ),
          const SizedBox(height: 24),
          Center(
            child: MiuixText(
              'HyperDusk v$kAppVersion · MIT',
              style: theme.textStyles.footnote1,
              color: theme.colors.onBackgroundVariant,
            ),
          ),
        ],
      ),
    );
  }

  /// 状态卡位置：加载中卡片 / 框架状态卡 / Rust 失败错误卡三选一。
  Widget _buildStatusSection(AppState state) {
    return switch (state) {
      AppLoading() => const _LoadingCard(),
      AppReady(:final xposed, :final module) => _StatusCard(
        xposed: xposed,
        module: module,
        onToggle: _onToggleModule,
      ),
      AppFailure(:final message) => _FailureCard(
        message: message,
        onRetry: _refresh,
      ),
    };
  }

  ModuleState _moduleOf(AppState state) {
    return switch (state) {
      AppReady(:final module) => module,
      AppFailure(:final module) => module,
      AppLoading() => const ModuleState.unavailable(),
    };
  }

  Future<void> _onToggleModule(bool enabled) async {
    await widget.controller.setModuleEnabled(enabled);
  }

  void _refresh() {
    widget.controller.initialize(force: true);
  }

  void _openAbout() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => AboutPage(controller: widget.controller),
      ),
    );
  }
}

/// 框架服务 + 模块总开关。
class _StatusCard extends StatelessWidget {
  const _StatusCard({
    required this.xposed,
    required this.module,
    required this.onToggle,
  });

  final XposedStatus xposed;
  final ModuleState module;
  final ValueChanged<bool> onToggle;

  @override
  Widget build(BuildContext context) {
    return MiuixCard(
      child: Column(
        children: [
          MiuixBasicComponent(
            title: '框架服务',
            summary: _frameworkSummary(xposed),
            startAction: miuixIconByName(
              name: 'extension',
              fallback: Icons.extension_rounded,
            ),
            endActions: [StatusPill(connected: xposed.connected)],
            insideMargin: _rowMargin,
          ),
          const MiuixHorizontalDivider(),
          MiuixSwitchPreference(
            title: '模块总开关',
            summary: _moduleSwitchSummary(module),
            value: module.enabled,
            // 框架不可读写时整个开关禁用，点击不会发起写入。
            enabled: module.available,
            onChanged: onToggle,
            insideMargin: _rowMargin,
          ),
        ],
      ),
    );
  }
}

/// 已连接时展示框架名/版本/API；未连接时显示固定文案。
String _frameworkSummary(XposedStatus status) {
  if (!status.connected) return '未连接到 Xposed 框架';
  final name = status.frameworkName ?? 'Xposed';
  final version = status.frameworkVersion;
  final versionText = version == null || version.isEmpty ? '' : ' $version';
  final api = status.apiVersion;
  return '$name$versionText · ${api == null ? 'API 未知' : 'API $api'}';
}

/// 总开关的摘要：不可用态优先，避免显示「关闭后…」误导用户以为可以操作。
String _moduleSwitchSummary(ModuleState module) {
  if (!module.available) return '框架未连接，无法修改';
  return module.enabled ? '关闭后所有手势立即停止响应' : '所有手势已停用';
}

/// 手势功能两行：双击音量上键、背部轻敲。
class _GestureCard extends StatelessWidget {
  const _GestureCard({
    required this.module,
    required this.onOpenShortcut,
    required this.onOpenBackTap,
  });

  final ModuleState module;
  final VoidCallback onOpenShortcut;
  final VoidCallback onOpenBackTap;

  @override
  Widget build(BuildContext context) {
    return MiuixCard(
      child: Column(
        children: [
          MiuixArrowPreference(
            title: '双击音量上键',
            summary: '灭屏时双击音量上键，执行与「双击电源键」相同的动作',
            endActions: [EndValueText(module.doubleClickVolumeUpLabel)],
            onClick: onOpenShortcut,
            insideMargin: _rowMargin,
          ),
          const MiuixHorizontalDivider(),
          MiuixArrowPreference(
            title: '背部轻敲',
            summary: '轻敲背部两下 / 三下触发所选动作',
            // 行尾只放双击动作：miuix 的 endActions 是 Row(min)，
            // 拼接「双击 / 三击」两个中文动作名必然把行撑爆；三击信息留在
            // 系统设置页里，这里保证至少能看清双击绑定。
            endActions: [EndValueText('双击 · ${module.backDoubleTapLabel}')],
            onClick: onOpenBackTap,
            insideMargin: _rowMargin,
          ),
        ],
      ),
    );
  }
}

/// 作用域四行。作用域值来自原生侧，标题与说明在这里翻译成人话。
class _ScopeCard extends StatelessWidget {
  const _ScopeCard({required this.scopes});

  final List<String> scopes;

  @override
  Widget build(BuildContext context) {
    return MiuixCard(
      child: Column(
        children: [
          for (var index = 0; index < scopes.length; index++) ...[
            if (index > 0) const MiuixHorizontalDivider(),
            MiuixBasicComponent(
              title: _scopeTitle(scopes[index]),
              summary: _scopeSummary(scopes[index]),
              endActions: const [EndValueText('已启用', maxWidth: 120)],
              insideMargin: _rowMargin,
            ),
          ],
        ],
      ),
    );
  }
}

/// scope 包名 → 中文标题。未知包名原样显示，便于排查原生侧异常数据。
String _scopeTitle(String scope) {
  return switch (scope) {
    'system' => '系统框架',
    'com.android.settings' => '系统设置',
    'com.android.systemui' => '系统界面',
    'com.miui.securitycore' => '安全核心',
    _ => scope,
  };
}

/// scope 包名 → 一句话说明「这个进程里注入的是哪块功能」。
String _scopeSummary(String scope) {
  return switch (scope) {
    'system' => 'system_server：手势执行',
    'com.android.settings' => '注入探针',
    'com.android.systemui' => '手电筒广播接收',
    'com.miui.securitycore' => '快捷手势页注入',
    _ => '已启用',
  };
}

/// 关于卡片：进入关于页 / 打开项目主页。
class _AboutCard extends StatelessWidget {
  const _AboutCard({required this.onAbout, required this.onProjectHome});

  final VoidCallback onAbout;
  final VoidCallback onProjectHome;

  @override
  Widget build(BuildContext context) {
    return MiuixCard(
      child: Column(
        children: [
          MiuixArrowPreference(
            title: '关于 HyperDusk',
            onClick: onAbout,
            insideMargin: _rowMargin,
          ),
          const MiuixHorizontalDivider(),
          MiuixArrowPreference(
            title: '项目主页',
            onClick: onProjectHome,
            insideMargin: _rowMargin,
          ),
        ],
      ),
    );
  }
}

/// Rust 初始化失败时替换状态卡。
///
/// 其余区块（手势/作用域/关于）继续渲染：Rust 只提供版本与架构信息，
/// 不影响框架状态与设置值的展示（规格 §3.2）。
class _FailureCard extends StatelessWidget {
  const _FailureCard({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final theme = MiuixTheme.of(context);
    final colors = theme.colors;
    return MiuixCard(
      insideMargin: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              MiuixIcon(
                icon: Icons.error_outline_rounded,
                size: 22,
                tint: colors.error,
              ),
              const SizedBox(width: 8),
              MiuixText('Rust 核心初始化失败', style: theme.textStyles.headline1),
            ],
          ),
          const SizedBox(height: 8),
          MiuixText(
            message,
            style: theme.textStyles.body2,
            color: colors.error,
          ),
          const SizedBox(height: 16),
          MiuixButton(onPressed: onRetry, child: const MiuixText('重试')),
        ],
      ),
    );
  }
}

/// 加载占位卡。
///
/// 目前只在 `initialize()` 期间短暂出现；保留卡片形态是为了与其他状态卡
/// 高度接近，状态切换时不会大幅跳动。
class _LoadingCard extends StatelessWidget {
  const _LoadingCard();

  @override
  Widget build(BuildContext context) {
    return const MiuixCard(
      child: SizedBox(
        height: 112,
        child: Center(child: MiuixCircularProgressIndicator()),
      ),
    );
  }
}
