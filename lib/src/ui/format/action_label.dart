/// 系统手势 function 值 → HyperOS 界面里显示的动作名。
///
/// `Settings.System` 里保存的是 `turn_on_torch` 这类稳定的英文字符串，
/// 而 HyperOS 设置页展示的是中文名。映射刻意只放在这里一份：
/// 首页、关于页和测试都调用 [actionLabel]，避免多处维护后出现显示不一致。
const Map<String, String> _actionLabels = <String, String>{
  'turn_on_torch': '手电筒',
  'launch_camera': '相机',
  'launch_smarthome': '小米一指连',
  'mi_pay': '公交·Mi Pay·门卡',
  'au_pay': 'AU PAY',
  'google_pay': 'Google Pay',
  'screen_shot': '截屏',
  'launch_alipay_health_code': '支付宝健康码',
  'launch_ai_shortcut': 'AI 快捷功能',
};

/// 把 `function` 值转成可读的动作名。
///
/// - `null`、空串、`none`：系统里表示「没有绑定动作」，统一显示「未设置」。
/// - 未知值：原样返回。HyperOS 后续新增动作时，这里宁可显示原始 key，
///   也不要显示空白或「未设置」误导用户。
String actionLabel(String? function) {
  if (function == null) return '未设置';
  final value = function.trim();
  if (value.isEmpty || value == 'none') return '未设置';
  return _actionLabels[value] ?? value;
}
