import 'package:flutter/services.dart';
import 'package:hyperdusk/src/ui/format/action_label.dart';

/// 框架连接状态，对应 `getXposedStatus`。
///
/// 这个结构从上一版沿用至今，Kotlin 侧的字段保持不变（规格 §2）。
class XposedStatus {
  const XposedStatus({
    required this.connected,
    required this.message,
    this.frameworkName,
    this.frameworkVersion,
    this.apiVersion,
  });

  const XposedStatus.unavailable([this.message = '未连接到兼容的 Xposed 框架。'])
    : connected = false,
      frameworkName = null,
      frameworkVersion = null,
      apiVersion = null;

  final bool connected;
  final String message;
  final String? frameworkName;
  final String? frameworkVersion;
  final int? apiVersion;
}

/// 模块作用域的固定顺序（规格 §2）。
///
/// 顺序决定首页「作用域」卡片的行序：先 system_server（真正执行手势的进程），
/// 再三个被注入的系统进程。原生侧返回的 `scopes` 正常情况下就是这四项。
const List<String> kModuleScopes = <String>[
  'system',
  'com.android.settings',
  'com.android.systemui',
  'com.miui.securitycore',
];

/// 平台通道里取字符串字段的防御式读取：类型不符就当没设置。
String? _asString(Object? value) => value is String ? value : null;

/// 模块运行时状态，对应 MethodChannel 的 `getModuleState`。
///
/// 字段语义见规格 §2：
/// - [available]：框架已连接且 RemotePreferences 可读写，决定总开关是否可用。
/// - [enabled]：RemotePreferences 里的 `module.enabled`，默认 true。
/// - 三个动作值是 `Settings.System` 里的原始 function 字符串，可能为 null。
/// - [scopes]：已启用的作用域包名列表。
class ModuleState {
  const ModuleState({
    required this.available,
    required this.enabled,
    this.doubleClickVolumeUp,
    this.backDoubleTap,
    this.backTripleTap,
    this.scopes = kModuleScopes,
  });

  /// 框架不可用时的降级值：开关显示为关闭且不可操作，动作值缺失。
  ///
  /// 作用域仍返回固定四项，让首页在框架断开时依然能解释「模块会作用在哪些进程」，
  /// 而不是整块空白。
  const ModuleState.unavailable()
    : available = false,
      enabled = false,
      doubleClickVolumeUp = null,
      backDoubleTap = null,
      backTripleTap = null,
      scopes = kModuleScopes;

  /// 从 MethodChannel 的 Map 还原状态。
  ///
  /// 原生侧承诺字段齐全，但这里仍对每个字段做防御式解析：任何一个字段类型不对
  /// 都退回默认值，绝不因为一个脏字段让整页崩掉（规格 §2 的降级要求）。
  factory ModuleState.fromMap(Map<String, Object?> data) {
    final rawScopes = data['scopes'];
    final scopes = rawScopes is List
        ? rawScopes.whereType<String>().toList(growable: false)
        : const <String>[];
    return ModuleState(
      available: data['available'] == true,
      enabled: data['enabled'] == true,
      doubleClickVolumeUp: _asString(data['doubleClickVolumeUp']),
      backDoubleTap: _asString(data['backDoubleTap']),
      backTripleTap: _asString(data['backTripleTap']),
      // 原生侧固定返回四项；万一返回空数组（例如读取失败被吞掉），
      // 用内置的固定列表兜底，保证作用域卡片始终有内容。
      scopes: scopes.isEmpty ? kModuleScopes : scopes,
    );
  }

  final bool available;
  final bool enabled;
  final String? doubleClickVolumeUp;
  final String? backDoubleTap;
  final String? backTripleTap;
  final List<String> scopes;

  /// 双击音量上键当前绑定的动作名。
  String get doubleClickVolumeUpLabel => actionLabelFor(doubleClickVolumeUp);

  /// 背部双击当前绑定的动作名。
  String get backDoubleTapLabel => actionLabelFor(backDoubleTap);

  /// 背部三击当前绑定的动作名。
  String get backTripleTapLabel => actionLabelFor(backTripleTap);

  /// 手动作值到显示名的映射。
  ///
  /// 统一委托给 [actionLabel]：映射表只存在 `action_label.dart` 一处，
  /// 数据层不复制一份，UI 和测试也就不会读到两套结果。
  String actionLabelFor(String? function) => actionLabel(function);

  /// 总开关写入成功后刷新本地状态用。
  ModuleState copyWith({
    bool? available,
    bool? enabled,
    String? doubleClickVolumeUp,
    String? backDoubleTap,
    String? backTripleTap,
    List<String>? scopes,
  }) {
    return ModuleState(
      available: available ?? this.available,
      enabled: enabled ?? this.enabled,
      doubleClickVolumeUp: doubleClickVolumeUp ?? this.doubleClickVolumeUp,
      backDoubleTap: backDoubleTap ?? this.backDoubleTap,
      backTripleTap: backTripleTap ?? this.backTripleTap,
      scopes: scopes ?? this.scopes,
    );
  }
}

/// 原生桥接抽象。测试用 fake 替换，生产走 [MethodChannelXposedGateway]。
abstract interface class XposedGateway {
  Future<XposedStatus> getStatus();

  Future<ModuleState> getModuleState();

  Future<bool> setModuleEnabled(bool enabled);

  /// [target] 取 `shortcut` / `backtap` / `accessibility`。
  Future<bool> openSettings(String target);

  Future<bool> openUrl(String url);
}

/// 基于 MethodChannel 的实现。
///
/// 所有方法都在这里吞掉异常并返回降级值：MethodChannel 在桌面/测试环境没有
/// 原生实现，缺失插件属于正常情况；真正的失败也不允许把异常抛给页面
/// （规格 §4：不允许未捕获异常）。
final class MethodChannelXposedGateway implements XposedGateway {
  const MethodChannelXposedGateway();

  static const _channel = MethodChannel('com.mcxiaochen.hyperdusk/xposed');

  @override
  Future<XposedStatus> getStatus() async {
    try {
      final data = await _channel.invokeMapMethod<String, Object?>(
        'getXposedStatus',
      );
      if (data == null || data['connected'] != true) {
        return XposedStatus.unavailable(
          data?['message'] as String? ?? '未连接到兼容的 Xposed 框架。',
        );
      }
      return XposedStatus(
        connected: true,
        message: data['message'] as String? ?? '框架服务已连接。',
        frameworkName: data['frameworkName'] as String?,
        frameworkVersion: data['frameworkVersion'] as String?,
        apiVersion: data['apiVersion'] as int?,
      );
    } on PlatformException catch (error) {
      return XposedStatus.unavailable(error.message ?? '读取框架状态失败。');
    } on MissingPluginException {
      return const XposedStatus.unavailable('当前平台没有 Xposed 状态桥接。');
    } catch (error) {
      return XposedStatus.unavailable('读取框架状态失败：$error');
    }
  }

  @override
  Future<ModuleState> getModuleState() async {
    try {
      final data = await _channel.invokeMapMethod<String, Object?>(
        'getModuleState',
      );
      if (data == null) return const ModuleState.unavailable();
      return ModuleState.fromMap(data);
    } catch (_) {
      // 包括 MissingPluginException 和原生侧读取失败的 PlatformException：
      // 统一降级为「框架未连接」，页面继续展示其余区块。
      return const ModuleState.unavailable();
    }
  }

  @override
  Future<bool> setModuleEnabled(bool enabled) {
    return _invokeBool('setModuleEnabled', <String, Object?>{
      'enabled': enabled,
    });
  }

  @override
  Future<bool> openSettings(String target) {
    return _invokeBool('openSettings', <String, Object?>{'target': target});
  }

  @override
  Future<bool> openUrl(String url) {
    return _invokeBool('openUrl', <String, Object?>{'url': url});
  }

  /// 统一调用返回 bool 的 method，任何异常都视为「没有成功」。
  Future<bool> _invokeBool(
    String method,
    Map<String, Object?> arguments,
  ) async {
    try {
      return await _channel.invokeMethod<bool>(method, arguments) ?? false;
    } catch (_) {
      return false;
    }
  }
}
