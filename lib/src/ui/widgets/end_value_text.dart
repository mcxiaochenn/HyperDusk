import 'package:flutter/material.dart';
import 'package:flutter_miuix/miuix.dart';

/// 列表行尾的短文本（动作名 / 值）。
///
/// miuix 的 `endActions` 槽位是 `Row(mainAxisSize: min)`，不会给子组件额外的
/// 宽度约束；中文动作名一长就会把行撑出 RenderFlex overflow。这里统一加
/// 最大宽度 + 单行省略号：无论多长的系统动作名，行尾都只占固定宽度，
/// 完整信息仍保留在摘要或设置页里。
class EndValueText extends StatelessWidget {
  const EndValueText(this.text, {this.maxWidth = 160, this.color, super.key});

  final String text;

  /// 行尾文本的最大宽度。ArrowPreference 行还要给右侧箭头留位置，
  /// 所以默认值比 BasicComponent 的取值行小一些。
  final double maxWidth;

  /// 文字颜色；为 null 时用主题里的「行尾操作」色。
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final theme = MiuixTheme.of(context);
    // Flexible 提供「不超过可用宽度」的松约束（endActions 所在的 Row 是
    // mainAxisSize.min，直接放 ConstrainedBox 会在窄屏下超出可用宽度），
    // ConstrainedBox 再把宽度收紧到设计值以内的固定上限。
    return Flexible(
      fit: FlexFit.loose,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: maxWidth),
        child: MiuixText(
          text,
          style: theme.textStyles.body2,
          color: color ?? theme.colors.onSurfaceVariantActions,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );
  }
}
