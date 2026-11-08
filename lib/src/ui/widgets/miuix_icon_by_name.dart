import 'package:flutter/material.dart';
import 'package:flutter_miuix/miuix.dart';

/// 按名字取 Miuix 扩展图标，取不到时退回 Material 图标字体。
///
/// flutter_miuix 1.3.0 的扩展图标集只有 100 多个常用名字，`extension`
/// 这类系统语义图标并不在其中。规格 §5 要求「优先用 MiuixIcons.extended.byName，
/// 不存在时用 MiuixIcon(icon: Icons.xxx)」，这里把这个二选一集中成一处，
/// 页面代码就不必反复写 null 判断。
MiuixIcon miuixIconByName({
  required String name,
  required IconData fallback,
  double? size,
  Color? tint,
  String? contentDescription,
}) {
  final vector = MiuixIcons.extended.byName(name);
  if (vector != null) {
    return MiuixIcon(
      vector: vector,
      size: size,
      tint: tint,
      contentDescription: contentDescription,
    );
  }
  return MiuixIcon(
    icon: fallback,
    size: size,
    tint: tint,
    contentDescription: contentDescription,
  );
}
