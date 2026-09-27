import 'package:flutter/material.dart';
import 'package:hyperdusk/src/core/core_gateway.dart';

class AboutPage extends StatelessWidget {
  const AboutPage({required this.coreGateway, super.key});

  final CoreGateway coreGateway;

  @override
  Widget build(BuildContext context) {
    AppAboutInfo? info;
    String? error;
    try {
      info = coreGateway.getAboutInfo();
    } catch (caught) {
      error = '无法读取 Rust 核心信息：$caught';
    }

    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
          children: [
            Row(
              children: [
                IconButton(
                  onPressed: () => Navigator.of(context).pop(),
                  tooltip: '返回',
                  icon: const Icon(Icons.arrow_back_ios_new_rounded),
                ),
                const SizedBox(width: 8),
                Text('关于', style: Theme.of(context).textTheme.displaySmall),
              ],
            ),
            const SizedBox(height: 26),
            const _IdentityCard(),
            const SizedBox(height: 24),
            Text('项目信息', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            _InfoGroup(
              children: [
                _InfoRow(label: '应用版本', value: info?.appVersion ?? '0.1.0'),
                _InfoRow(
                  label: '包名',
                  value: info?.packageName ?? 'com.mcxiaochen.hyperdusk',
                ),
                _InfoRow(label: 'Rust 核心', value: info?.coreVersion ?? '不可用'),
                _InfoRow(label: '许可证', value: info?.license ?? 'MIT'),
              ],
            ),
            const SizedBox(height: 12),
            _InfoGroup(
              children: [
                _InfoRow(
                  label: '架构',
                  value:
                      info?.architecture ??
                      'Flutter UI · Rust core · libxposed API 102',
                ),
              ],
            ),
            if (error != null) ...[
              const SizedBox(height: 12),
              Text(
                error,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const SizedBox(height: 24),
            FilledButton.tonalIcon(
              onPressed: () => showLicensePage(
                context: context,
                applicationName: 'HyperDusk',
                applicationVersion: info?.appVersion ?? '0.1.0',
                applicationLegalese: '© 2026 HyperDusk Contributors · MIT',
              ),
              icon: const Icon(Icons.description_outlined),
              label: const Text('查看开源许可'),
            ),
          ],
        ),
      ),
    );
  }
}

class _IdentityCard extends StatelessWidget {
  const _IdentityCard();

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(26),
      ),
      child: Column(
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              color: colors.primaryContainer,
              borderRadius: BorderRadius.circular(22),
            ),
            child: Icon(
              Icons.nights_stay_rounded,
              color: colors.primary,
              size: 38,
            ),
          ),
          const SizedBox(height: 16),
          Text('HyperDusk', style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 6),
          Text(
            '独立、无默认作用域的现代 libxposed 模块骨架',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium
                ?.copyWith(color: colors.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

class _InfoGroup extends StatelessWidget {
  const _InfoGroup({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(children: children),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 15),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 82,
            child: Text(
              label,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: const TextStyle(fontWeight: FontWeight.w500),
            ),
          ),
        ],
      ),
    );
  }
}
