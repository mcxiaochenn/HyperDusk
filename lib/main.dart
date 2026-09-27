import 'package:flutter/material.dart';
import 'package:hyperdusk/src/app.dart';
import 'package:hyperdusk/src/core/app_controller.dart';
import 'package:hyperdusk/src/core/core_gateway.dart';
import 'package:hyperdusk/src/platform/xposed_gateway.dart';
import 'package:hyperdusk/src/rust/frb_generated.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  Object? rustStartupError;
  try {
    await RustLib.init();
  } catch (error) {
    rustStartupError = error;
  }

  final controller = AppController(
    coreGateway: RustCoreGateway(startupError: rustStartupError),
    xposedGateway: const MethodChannelXposedGateway(),
  );
  runApp(HyperDuskApp(controller: controller));
}
