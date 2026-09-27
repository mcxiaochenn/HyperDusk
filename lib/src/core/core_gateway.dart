import 'package:hyperdusk/src/rust/api/core.dart' as rust;

class CoreSnapshot {
  const CoreSnapshot({
    required this.ready,
    required this.coreVersion,
    required this.dataSchemaVersion,
    this.error,
  });

  final bool ready;
  final String coreVersion;
  final int dataSchemaVersion;
  final String? error;
}

class AppAboutInfo {
  const AppAboutInfo({
    required this.appName,
    required this.appVersion,
    required this.packageName,
    required this.architecture,
    required this.coreVersion,
    required this.license,
  });

  final String appName;
  final String appVersion;
  final String packageName;
  final String architecture;
  final String coreVersion;
  final String license;
}

abstract interface class CoreGateway {
  Future<CoreSnapshot> initialize();
  AppAboutInfo getAboutInfo();
}

final class RustCoreGateway implements CoreGateway {
  const RustCoreGateway({this.startupError});

  final Object? startupError;

  @override
  Future<CoreSnapshot> initialize() async {
    final error = startupError;
    if (error != null) throw error;

    final status = rust.initializeCore();
    return CoreSnapshot(
      ready: status.ready,
      coreVersion: status.coreVersion,
      dataSchemaVersion: status.dataSchemaVersion,
      error: status.error,
    );
  }

  @override
  AppAboutInfo getAboutInfo() {
    final error = startupError;
    if (error != null) throw error;

    final info = rust.getAboutInfo();
    return AppAboutInfo(
      appName: info.appName,
      appVersion: info.appVersion,
      packageName: info.packageName,
      architecture: info.architecture,
      coreVersion: info.coreVersion,
      license: info.license,
    );
  }
}
