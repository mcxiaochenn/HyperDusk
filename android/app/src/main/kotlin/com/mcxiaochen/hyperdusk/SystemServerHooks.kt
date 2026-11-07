package com.mcxiaochen.hyperdusk

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server 侧的 hook 安装器（scope.list 里的 `system`）。
 *
 * 本版本只做两条执行链路：
 *  1) 背部轻敲（双击/三击）→ 手电筒：[installBackTapTorchHook]（B 项）
 *  2) 双击音量上键 → 配置的动作：[VolumeUpDoubleClickHook]（C 项）
 *
 * 时机：HyperDuskModule#onSystemServerStarting 里用 param.classLoader 调 [install]。
 * 这时 PhoneWindowManager / BaseMiuiPhoneWindowManager 还没构造（systemReady 更晚），
 * 所以这里只做方法 hook，不 hook 构造器；方法 hook 对之后创建的实例同样生效。
 */
internal object SystemServerHooks {

    private const val TAG = HyperDuskModule.TAG

    private const val CLASS_BACK_TAP = "com.miui.server.input.MiuiBackTapGestureService"
    private const val FUNCTION_TURN_ON_TORCH = "turn_on_torch"

    private const val HOOK_BACK_TAP_UNSUPPORTED = "system_server:MiuiBackTapGestureService#removeUnsupportedFunction"

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!HyperDuskPrefs.getBoolean(HyperDuskPrefs.KEY_MODULE_ENABLED, true)) {
            module.log(
                Log.INFO,
                TAG,
                "module disabled by remote preference ${HyperDuskPrefs.KEY_MODULE_ENABLED}, " +
                    "skip system_server hooks",
            )
            return
        }
        installBackTapTorchHook(module, classLoader)
        VolumeUpDoubleClickHook.install(module, classLoader)
    }

    /**
     * B 项：让「背部轻敲 → 手电筒」真正生效。
     *
     * 目标：`com.miui.server.input.MiuiBackTapGestureService#removeUnsupportedFunction()`（private void）。
     * 依据（.research/notes/system-gestures.md §1.3/§1.4 + 反编译源）：
     *  - miui-services/sources/com/miui/server/input/MiuiBackTapGestureService.java:94-101：
     *    当 back_double_tap / back_triple_tap 的值是 turn_on_torch（或 launch_alipay_health_code /
     *    launch_ai_shortcut）时，把 Settings.System 里对应值强制回写成 "none"；
     *  - 调用点：backTapSettingsInit():91（开机初始化）、updateSettings():128（切用户）；
     *  - 该服务由 BaseMiuiPhoneWindowManager#systemReadyInternal 创建
     *    （BaseMiuiPhoneWindowManager.java:761），晚于 onSystemServerStarting，因此方法 hook 不会漏；
     *  - 服务读取设置值的字段：mBackDoubleTapFunction(:43) / mBackTripleTapFunction(:45)，
     *    ContentObserver 触发后 :226-228 会重新读取设置，所以只要不写回 "none"，字段就保持 turn_on_torch；
     *  - updateBackTapFeatureState():133-168 在「亮屏 + function 非 none」时注册 backtap sensor，
     *    保留 turn_on_torch 后状态正常，不需要额外 hook。
     *
     * 做法：hook 该方法，只在当前两个手势值里有 turn_on_torch 时跳过原逻辑（不写回 none），
     *       其余情况（例如确实要清掉 alipay_health_code / launch_ai_shortcut）保持 MIUI 原行为。
     *
     * 执行侧链路（无需兜底 hook）：轻敲 → MiuiBackTapGestureService$1.onBackDoubleTap/TripleTap
     *   → H.handleMessage → ShortCutActionsUtils#triggerFunction(function="turn_on_torch",
     *   action="back_double_tap"/"back_triple_tap") → launchTorch() 发
     *   miui.intent.action.TOGGLE_TORCH 广播，由 SystemUI 的 MiuiFlashlightControllerImpl 开灯。
     */
    private fun installBackTapTorchHook(module: XposedModule, classLoader: ClassLoader) {
        val clazz = classLoader.findClassOrNull(CLASS_BACK_TAP)
        if (clazz == null) {
            module.log(Log.WARN, TAG, "back tap hook skipped, class not found: $CLASS_BACK_TAP")
            return
        }
        val target = clazz.findMethodOrNull("removeUnsupportedFunction")
        if (target == null) {
            module.log(Log.WARN, TAG, "back tap hook skipped, method not found: $CLASS_BACK_TAP#removeUnsupportedFunction")
            return
        }

        // 两个调用方都是短方法，可能把 removeUnsupportedFunction 内联掉，需要 deoptimize 调用方。
        module.tryDeoptimize(clazz.findMethodOrNull("backTapSettingsInit"), "caller of removeUnsupportedFunction")
        module.tryDeoptimize(clazz.findMethodOrNull("updateSettings"), "caller of removeUnsupportedFunction")

        val doubleTapField = clazz.findFieldOrNull("mBackDoubleTapFunction")
        val tripleTapField = clazz.findFieldOrNull("mBackTripleTapFunction")
        if (doubleTapField == null && tripleTapField == null) {
            module.log(
                Log.WARN,
                TAG,
                "back tap hook: function fields not found, removeUnsupportedFunction will be fully suppressed",
            )
        }
        val loggedKeepTorch = AtomicBoolean(false)

        module.hookMethod(HOOK_BACK_TAP_UNSUPPORTED, target) { chain ->
            val service = chain.getThisObject()
            val doubleTap = doubleTapField.readStringOrNull(service)
            val tripleTap = tripleTapField.readStringOrNull(service)
            val cannotInspect = doubleTapField == null && tripleTapField == null
            val keepTorch = cannotInspect ||
                doubleTap == FUNCTION_TURN_ON_TORCH ||
                tripleTap == FUNCTION_TURN_ON_TORCH

            if (keepTorch) {
                if (loggedKeepTorch.compareAndSet(false, true)) {
                    module.log(
                        Log.INFO,
                        TAG,
                        "back tap: keep turn_on_torch (back_double_tap=$doubleTap, back_triple_tap=$tripleTap)",
                    )
                }
                // 不调用 chain.proceed()：原逻辑会把 turn_on_torch 回写成 "none"
                null
            } else {
                // 与 MIUI 原行为一致：清掉其它不受支持的 function
                chain.proceed()
            }
        }
    }

    private fun Field?.readStringOrNull(target: Any?): String? {
        val field = this ?: return null
        if (target == null) return null
        return try {
            field.get(target) as? String
        } catch (t: Throwable) {
            null
        }
    }
}
