package com.mcxiaochen.hyperdusk

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter ⇄ Kotlin 桥（channel: [CHANNEL_NAME]，契约见 .research/notes/ui-redesign.md §2）。
 *
 * 约定：每个 method 只返回可序列化的基础类型，跨进程 / 系统调用失败一律降级成 false / null，
 * 不允许把异常抛回 Dart —— Dart 侧只有 PlatformException 一条异常通道，拿不到有意义的降级值；
 * 状态读取失败时宁可返回「不可用」的空结果，也不能让页面崩在 channel 调用上。
 */
class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME,
        ).setMethodCallHandler { call, result ->
            val app = application as HyperDuskApplication
            try {
                when (call.method) {
                    "getXposedStatus" -> result.success(app.xposedStatus())
                    "getModuleState" -> result.success(app.moduleState())
                    "setModuleEnabled" -> result.success(
                        call.argument<Boolean>("enabled")?.let(app::setModuleEnabled) ?: false,
                    )
                    "openSettings" -> result.success(openSettings(call.argument<String>("target")))
                    "openUrl" -> result.success(openUrl(call.argument<String>("url")))
                    else -> result.notImplemented()
                }
            } catch (t: Throwable) {
                // 兜底：状态类给 null（Dart 侧当作不可用），动作类给 false。绝不让异常穿回 Dart。
                Log.e(TAG, "method ${call.method} failed", t)
                result.success(fallbackResult(call.method))
            }
        }
    }

    /** 降级返回值：状态查询没有可读状态就给 null，动作/写入失败就是 false。 */
    private fun fallbackResult(method: String): Any? = when (method) {
        "setModuleEnabled", "openSettings", "openUrl" -> false
        else -> null
    }

    /**
     * 打开系统设置页（§2 `openSettings`）。
     *
     * - `shortcut` → securitycore 的「快捷手势」页；
     * - `backtap` → securitycore 的「背部轻敲」页；
     * - `accessibility` → 系统「无障碍」页。
     *
     * 两个 securitycore Activity 在真机 manifest 里都是 `exported=true`
     * （见 .research/notes/securitycore-gestures.md：GestureShortcutActivity manifest :169-174、
     * BackTapSettingsActivity manifest :196-199），但 MIUI 仍可能因组件被禁用 / 权限策略导致启动失败，
     * 所以统一回退到「无障碍」页；无障碍页再失败才返回 false（§2 的回退链）。
     */
    private fun openSettings(target: String?): Boolean = when (target) {
        "shortcut" -> launch(securityCoreIntent(SHORTCUT_ACTIVITY)) || launch(accessibilityIntent())
        "backtap" -> launch(securityCoreIntent(BACKTAP_ACTIVITY)) || launch(accessibilityIntent())
        "accessibility" -> launch(accessibilityIntent())
        // 未知 target 不猜、不乱开页面，直接算失败
        else -> false
    }

    /** 用浏览器打开链接（§2 `openUrl`，用于项目主页 / 问题反馈）。 */
    private fun openUrl(url: String?): Boolean {
        val target = url?.trim().orEmpty()
        if (target.isEmpty()) return false
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
    }

    /** 显式组件：system 应用里的页面按「包名 + 完整类名」启动，不依赖 action 是否被改写。 */
    private fun securityCoreIntent(activityName: String): Intent =
        Intent().setComponent(ComponentName(PACKAGE_SECURITY_CORE, activityName))

    private fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /**
     * startActivity 的唯一入口：ActivityNotFound / SecurityException / 组件被禁用都收敛成 false，
     * 由调用方决定是否走回退链。
     */
    private fun launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "startActivity failed: $intent", t)
        false
    }

    private companion object {
        const val TAG = "HyperDusk"
        const val CHANNEL_NAME = "com.mcxiaochen.hyperdusk/xposed"

        const val PACKAGE_SECURITY_CORE = "com.miui.securitycore"
        const val SHORTCUT_ACTIVITY = "com.miui.miinput.gesture.GestureShortcutActivity"
        const val BACKTAP_ACTIVITY = "com.miui.miinput.gesture.backtap.BackTapSettingsActivity"
    }
}
