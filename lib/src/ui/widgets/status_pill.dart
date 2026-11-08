import 'package:flutter/material.dart';
import 'package:flutter_miuix/miuix.dart';

/// 框架连接状态胶囊（规格 §3.3）。
///
/// 自绘而不用 MiuixBadge：Badge 默认是 error 色的徽标语义，这里需要的是
/// 「已连接/未连接」的中性状态色，用主题里的容器色更贴近 HyperOS 设置页。
class StatusPill extends StatelessWidget {
  const StatusPill({required this.connected, super.key});

  final bool connected;

  @override
  Widget build(BuildContext context) {
    final theme = MiuixTheme.of(context);
    final colors = theme.colors;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: connected
            ? colors.secondaryContainer
            : colors.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(999),
      ),
      child: MiuixText(
        connected ? '已连接' : '未连接',
        // 1.3.0 的 TextStyles 没有 caption1，footnote2（11sp）是同等定位的最小字号。
        style: theme.textStyles.footnote2,
        color: connected
            ? colors.onSecondaryContainer
            : colors.onSurfaceContainerHigh,
      ),
    );
  }
}
