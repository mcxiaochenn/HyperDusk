import 'package:flutter/material.dart';
import 'package:hyperdusk/src/core/app_controller.dart';
import 'package:hyperdusk/src/platform/xposed_gateway.dart';
import 'package:hyperdusk/src/ui/about_page.dart';
import 'package:hyperdusk/src/ui/widgets/status_card.dart';

class HomePage extends StatefulWidget {
  const HomePage({required this.controller, super.key});

  final AppController controller;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  @override
  void initState() {
    super.initState();
    widget.controller.initialize();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: () => widget.controller.initialize(force: true),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 22, 20, 32),
            children: [
              _Header(onAbout: _openAbout),
              const SizedBox(height: 24),
              const _ModuleBanner(),
              const SizedBox(height: 28),
              Text('运行状态', style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 12),
              ValueListenableBuilder<AppState>(
                valueListenable: widget.controller,
                builder: (context, state, _) => _StatusContent(state: state),
              ),
              const SizedBox(height: 28),
              Text('目标环境', style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 12),
              const StatusCard(
                icon: Icons.android_rounded,
                title: 'Android',
                value: 'Android 17 · API 37',
                description: '发布构建仅包含 arm64-v8a 与 x86_64。',
                positive: true,
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _openAbout() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => AboutPage(coreGateway: widget.controller.coreGateway),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.onAbout});

  final VoidCallback onAbout;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Text(
            'HyperDusk',
            style: Theme.of(context).textTheme.displaySmall,
          ),
        ),
        IconButton.filledTonal(
          onPressed: onAbout,
          tooltip: '关于 HyperDusk',
          icon: const Icon(Icons.info_outline_rounded),
        ),
      ],
    );
  }
}

class _ModuleBanner extends StatelessWidget {
  const _ModuleBanner();

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(22),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xFF3482FF), Color(0xFF6C5CE7)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(26),
        boxShadow: [
          BoxShadow(
            color: const Color(0xFF3482FF).withValues(alpha: 0.24),
            blurRadius: 26,
            offset: const Offset(0, 12),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.nights_stay_rounded, color: colors.onPrimary, size: 30),
          const SizedBox(height: 18),
          Text(
            '独立模块骨架',
            style: Theme.of(context).textTheme.headlineSmall
                ?.copyWith(color: Colors.white),
          ),
          const SizedBox(height: 7),
          Text(
            '当前版本不安装任何 Hook，默认作用域为空，不会改变系统或应用行为。',
            style: Theme.of(context).textTheme.bodyLarge
                ?.copyWith(color: Colors.white.withValues(alpha: 0.88)),
          ),
        ],
      ),
    );
  }
}

class _StatusContent extends StatelessWidget {
  const _StatusContent({required this.state});

  final AppState state;

  @override
  Widget build(BuildContext context) {
    return switch (state) {
      AppLoading() => const _LoadingCard(),
      AppReady(:final core, :final xposed) => Column(
        children: [
          StatusCard(
            icon: Icons.memory_rounded,
            title: 'Rust 核心',
            value: '已就绪 · v${core.coreVersion}',
            description: '数据结构版本 ${core.dataSchemaVersion}，业务状态由 Rust 提供。',
            positive: true,
          ),
          const SizedBox(height: 12),
          _XposedCard(status: xposed),
        ],
      ),
      AppFailure(:final message, :final xposed) => Column(
        children: [
          StatusCard(
            icon: Icons.error_outline_rounded,
            title: 'Rust 核心',
            value: '初始化失败',
            description: message,
            positive: false,
          ),
          const SizedBox(height: 12),
          _XposedCard(status: xposed),
        ],
      ),
    };
  }
}

class _XposedCard extends StatelessWidget {
  const _XposedCard({required this.status});

  final XposedStatus status;

  @override
  Widget build(BuildContext context) {
    final details = status.connected
        ? '${status.frameworkName ?? 'Xposed'} ${status.frameworkVersion ?? ''} · API ${status.apiVersion ?? '未知'}'
        : status.message;
    return StatusCard(
      icon: Icons.extension_rounded,
      title: 'Xposed 框架',
      value: status.connected ? '服务已连接' : '未连接',
      description: details,
      positive: status.connected,
    );
  }
}

class _LoadingCard extends StatelessWidget {
  const _LoadingCard();

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      height: 112,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(20),
      ),
      child: const Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox.square(
            dimension: 20,
            child: CircularProgressIndicator(strokeWidth: 2.5),
          ),
          SizedBox(width: 12),
          Text('正在检查运行环境…'),
        ],
      ),
    );
  }
}
