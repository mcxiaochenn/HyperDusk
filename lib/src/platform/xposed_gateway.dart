import 'package:flutter/services.dart';

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

abstract interface class XposedGateway {
  Future<XposedStatus> getStatus();
}

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
}
