package com.mcxiaochen.hyperdusk

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME,
        ).setMethodCallHandler { call, result ->
            if (call.method == "getXposedStatus") {
                val app = application as HyperDuskApplication
                result.success(app.xposedStatus())
            } else {
                result.notImplemented()
            }
        }
    }

    private companion object {
        const val CHANNEL_NAME = "com.mcxiaochen.hyperdusk/xposed"
    }
}
