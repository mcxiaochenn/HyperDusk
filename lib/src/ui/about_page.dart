import 'package:flutter/material.dart';
import 'package:flutter_miuix/miuix.dart';
import 'package:hyperdusk/src/core/app_controller.dart';
import 'package:hyperdusk/src/core/app_meta.dart';
import 'package:hyperdusk/src/core/core_gateway.dart';
import 'package:hyperdusk/src/ui/widgets/end_value_text.dart';
import 'package:hyperdusk/src/ui/widgets/miuix_icon_by_name.dart';

/// 卡片内各行的统一内边距，与首页保持一致。
const EdgeInsets _rowMargin = EdgeInsets.symmetric(
  horizontal: 16,
  vertical: 14,
);

/// 关于页（规格 §1.2）：品牌卡 / 应用信息 / 项目入口。
class AboutPage extends StatelessWidget {
  const AboutPage({required this.controller, super.key});

  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final theme = MiuixTheme.of(context);
    final info = _readAboutInfo();
    return MiuixScaffold(
      topBar: MiuixSmallTopAppBar(
        title: '关于',
        navigationIcon: MiuixIconButton(
          onPressed: () => Navigator.of(context).maybePop(),
          child: miuixIconByName(
            name: 'back',
            fallback: Icons.arrow_back_rounded,
            contentDescription: '返回',
          ),
        ),
      ),
      content: (insets) => ListView(
        padding: EdgeInsets.fromLTRB(12, insets.top, 12, insets.bottom + 24),
        children: [
          _BrandCard(theme: theme),
          const SizedBox(height: 16),
          const MiuixSmallTitle('应用信息'),
          MiuixCard(
            child: Column(
              children: [
                _InfoRow(title: '版本', value: kAppVersion),
                const MiuixHorizontalDivider(),
                _InfoRow(
                  title: '包名',
                  value: info?.packageName ?? 'com.mcxiaochen.hyperdusk',
                ),
                const MiuixHorizontalDivider(),
                // Rust 不可用时逐项显示「不可用」，页面其余部分照常工作。
                _InfoRow(title: 'Rust 核心', value: info?.coreVersion ?? '不可用'),
                const MiuixHorizontalDivider(),
                _InfoRow(title: 'Rust 数据版本', value: _rustDataVersion()),
                const MiuixHorizontalDivider(),
                _InfoRow(title: '平台', value: 'Android 17 · API 37'),
                const MiuixHorizontalDivider(),
                _InfoRow(title: '许可证', value: info?.license ?? 'MIT'),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const MiuixSmallTitle('项目'),
          MiuixCard(
            child: Column(
              children: [
                MiuixArrowPreference(
                  title: '查看开源许可',
                  onClick: () => _showLicenses(context),
                  insideMargin: _rowMargin,
                ),
                const MiuixHorizontalDivider(),
                MiuixArrowPreference(
                  title: '项目主页',
                  onClick: () => controller.openUrl(kProjectHomeUrl),
                  insideMargin: _rowMargin,
                ),
                const MiuixHorizontalDivider(),
                MiuixArrowPreference(
                  title: '问题反馈',
                  onClick: () => controller.openUrl(kProjectIssuesUrl),
                  insideMargin: _rowMargin,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Rust 信息只用于展示，读取失败时返回 null，由各行降级为「不可用」。
  AppAboutInfo? _readAboutInfo() {
    try {
      return controller.coreGateway.getAboutInfo();
    } catch (_) {
      return null;
    }
  }

  /// Rust 数据版本来自 `CoreStatus`，关于页需要从当前状态取。
  String _rustDataVersion() {
    return switch (controller.value) {
      AppReady(:final core) => '${core.dataSchemaVersion}',
      AppFailure() => '不可用',
      AppLoading() => '不可用',
    };
  }

  /// Flutter 自带的许可证页是 Material 页面，规格允许在这条不可控路径使用 Material；
  /// 主题来自 MaterialApp，因此这里不额外包 Material 组件。
  void _showLicenses(BuildContext context) {
    showLicensePage(
      context: context,
      applicationName: 'HyperDusk',
      applicationVersion: kAppVersion,
      applicationLegalese: '© 2026 HyperDusk Contributors · MIT',
    );
  }
}

/// 品牌卡：图标 + 名称 + 定位 + 版本。
class _BrandCard extends StatelessWidget {
  const _BrandCard({required this.theme});

  final MiuixThemeData theme;

  static const double _iconSize = 88;

  @override
  Widget build(BuildContext context) {
    return MiuixCard(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
        child: Column(
          children: [
            _buildIcon(context),
            const SizedBox(height: 16),
            MiuixText('HyperDusk', style: theme.textStyles.title2),
            const SizedBox(height: 6),
            MiuixText(
              'HyperOS 4 / Android 17 快捷手势增强模块',
              style: theme.textStyles.body2,
              color: theme.colors.onSurfaceVariantSummary,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 4),
            MiuixText(
              'v$kAppVersion',
              style: theme.textStyles.footnote1,
              color: theme.colors.onBackgroundVariant,
            ),
          ],
        ),
      ),
    );
  }

  /// 88×88 圆角图标。
  ///
  /// 图标由构建流水线放进 `assets/branding/app_icon.png`；开发环境或资源缺失时
  /// `errorBuilder` 回退到 Miuix 图标，保证关于页不会因为一张图片而显示异常。
  Widget _buildIcon(BuildContext context) {
    return ClipPath(
      clipper: ShapeBorderClipper(
        shape: const MiuixSquircleBorder(cornerRadius: 22),
      ),
      child: Image.asset(
        'assets/branding/app_icon.png',
        width: _iconSize,
        height: _iconSize,
        fit: BoxFit.cover,
        errorBuilder: (context, error, stackTrace) => _fallbackIcon(),
      ),
    );
  }

  Widget _fallbackIcon() {
    return Container(
      width: _iconSize,
      height: _iconSize,
      alignment: Alignment.center,
      decoration: ShapeDecoration(
        color: theme.colors.secondaryContainer,
        shape: const MiuixSquircleBorder(cornerRadius: 22),
      ),
      child: MiuixIcon(
        icon: Icons.nights_stay_rounded,
        size: 44,
        tint: theme.colors.primary,
      ),
    );
  }
}

/// 应用信息行：标题在左，值放在行尾的 MiuixText 里。
class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.title, required this.value});

  final String title;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = MiuixTheme.of(context);
    return MiuixBasicComponent(
      title: title,
      // 包名这类值比行尾槽位宽，统一限宽 + 省略号，避免 RenderFlex overflow；
      // Text 控件本身仍保留完整字符串，语义和测试断言不受影响。
      endActions: [
        EndValueText(
          value,
          maxWidth: 220,
          color: theme.colors.onSurfaceVariantSummary,
        ),
      ],
      insideMargin: _rowMargin,
    );
  }
}
