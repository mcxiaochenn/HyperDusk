package com.mcxiaochen.hyperdusk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server 侧的 hook 安装器（scope.list 里的 `system`）。
 *
 * 本版本做两块功能：
 *  1) 背部轻敲（双击/三击）→ 手电筒：保留 turn_on_torch 配置（[installBackTapTorchHook]）
 *     + 修正 launchTorch 的 IS_ENABLE 恒 false 问题（[installBackTapTorchToggleHook]）
 *  2) 双击音量上键 → 配置的动作：[VolumeUpDoubleClickHook]（C 项）
 *
 * 时机：HyperDuskModule#onSystemServerStarting 里用 param.classLoader 调 [install]。
 * 这时 PhoneWindowManager / BaseMiuiPhoneWindowManager 还没构造（systemReady 更晚），
 * 所以这里只做方法 hook，不 hook 构造器；方法 hook 对之后创建的实例同样生效。
 */
internal object SystemServerHooks {

    private const val TAG = HyperDuskModule.TAG

    private const val CLASS_BACK_TAP = "com.miui.server.input.MiuiBackTapGestureService"
    private const val CLASS_SHORT_CUT_ACTIONS = "com.miui.server.input.util.ShortCutActionsUtils"
    private const val FUNCTION_TURN_ON_TORCH = "turn_on_torch"
    private const val ACTION_BACK_DOUBLE_TAP = "back_double_tap"
    private const val ACTION_BACK_TRIPLE_TAP = "back_triple_tap"

    /** 手电筒广播（ShortCutActionsUtils#launchTorch :1765-1788 同款 action / extra）。 */
    private const val ACTION_TOGGLE_TORCH = "miui.intent.action.TOGGLE_TORCH"
    private const val EXTRA_IS_TOGGLE = "miui.intent.extra.IS_TOGGLE"

    private const val HOOK_BACK_TAP_UNSUPPORTED = "system_server:MiuiBackTapGestureService#removeUnsupportedFunction"
    private const val HOOK_TRIGGER_FUNCTION = "system_server:ShortCutActionsUtils#triggerFunction"

    /** 只打一次「背敲手电筒已按翻转逻辑放行」的日志。 */
    private val loggedTorchToggleFix = AtomicBoolean(false)

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
        installBackTapTorchToggleHook(module, classLoader)
        VolumeUpDoubleClickHook.install(module, classLoader)
    }

    /**
     * B 项（执行侧修复，第二轮真机反馈）：「背部轻敲 → 手电筒」一直不亮。
     *
     * 真机日志证据：链路走到 `launchTorch` 并发出 TOGGLE_TORCH 广播，但 SystemUI 收到的是
     * `FlashlightController: setTorchState: enabled: false`，所以只会关灯不会开灯。
     *
     * 根因（反编译 ShortCutActionsUtils.java:1765-1788 `launchTorch(Bundle extra)`）：
     * `if (extra != null) IS_ENABLE = extra.getBoolean(EXTRA_TORCH_ENABLED, false)`，
     * 而背部轻敲的 bundle 只有 shortcut / event_data（MiuiBackTapGestureService.java:202-206,250-252），
     * 没有 `extra_torch_enabled` → 恒为 false。双击电源键的原生路径会显式带该 extra，所以那条链路正常。
     *
     * 修法：hook `ShortCutActionsUtils#triggerFunction(String function, String action, Bundle extras,
     * boolean hapticFeedback)`（4 参 public，ShortCutActionsUtils.java:1272）。当 action 是
     * back_double_tap/back_triple_tap 且 function 是 turn_on_torch 时，**不再走原 launchTorch**，
     * 而是自己发 `miui.intent.action.TOGGLE_TORCH` + `miui.intent.extra.IS_TOGGLE=true` 广播，
     * 然后 return true 吞掉原逻辑（SystemUI MiuiFlashlightControllerImpl.onReceive:372-380 支持 IS_TOGGLE：取反当前状态）。
     *
     * 为什么不用「extras 传 null 再 proceed」：5 参实现开头会做 shortcut one-track 上报，
     * 其中 back_double_tap/back_triple_tap 分支直接用 `extras.getString("event_data")`（ShortCutActionsUtils.java:260-262），
     * extras 为 null 会 NPE；所以采用直接发广播 + 吞掉原逻辑的方式（回退：广播发送失败时走原逻辑，不会比修复前更差）。
     */
    private fun installBackTapTorchToggleHook(module: XposedModule, classLoader: ClassLoader) {
        val method = classLoader.findClassOrNull(CLASS_SHORT_CUT_ACTIONS)?.findMethodOrNull(
            "triggerFunction",
            arrayOf(
                String::class.java,
                String::class.java,
                Bundle::class.java,
                Boolean::class.javaPrimitiveType!!,
            ),
        )
        if (method == null) {
            module.log(
                Log.WARN,
                TAG,
                "back tap torch toggle fix skipped: $CLASS_SHORT_CUT_ACTIONS#triggerFunction(String,String,Bundle,boolean) not found",
            )
            return
        }
        module.hookMethod(HOOK_TRIGGER_FUNCTION, method) { chain ->
            val function = chain.getArg(0) as? String
            val action = chain.getArg(1) as? String
            val isBackTapTorch = function == FUNCTION_TURN_ON_TORCH &&
                (action == ACTION_BACK_DOUBLE_TAP || action == ACTION_BACK_TRIPLE_TAP)
            if (!isBackTapTorch) {
                chain.proceed()
            } else {
                try {
                    val sent = sendTorchToggleBroadcast(chain.getThisObject())
                    if (sent) {
                        if (loggedTorchToggleFix.compareAndSet(false, true)) {
                            module.log(
                                Log.INFO,
                                TAG,
                                "back tap torch: sent TOGGLE_TORCH(IS_TOGGLE=true) directly " +
                                    "(action=$action), original launchTorch swallowed",
                            )
                        }
                        true
                    } else {
                        module.log(Log.WARN, TAG, "back tap torch: sendBroadcast failed, fall back to original")
                        chain.proceed()
                    }
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "back tap torch toggle redirect failed, fall back to original", t)
                    chain.proceed()
                }
            }
        }
    }

    /** 用 ShortCutActionsUtils 实例里的 system_server Context 发手电筒翻转广播。 */
    private fun sendTorchToggleBroadcast(shortCutActionsInstance: Any?): Boolean {
        val context = ScReflect.fieldValue(shortCutActionsInstance, "mContext") as? Context ?: return false
        val intent = Intent(ACTION_TOGGLE_TORCH)
        intent.addFlags(268435456)
        intent.putExtra(EXTRA_IS_TOGGLE, true)
        context.sendBroadcast(intent)
        return true
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
     * 执行侧链路：轻敲 → MiuiBackTapGestureService$1.onBackDoubleTap/TripleTap
     *   → H.handleMessage → ShortCutActionsUtils#triggerFunction(function="turn_on_torch",
     *   action="back_double_tap"/"back_triple_tap") → launchTorch() 发
     *   miui.intent.action.TOGGLE_TORCH 广播，由 SystemUI 的 MiuiFlashlightControllerImpl 开关灯。
     *   注意：背敲 bundle 没有 extra_torch_enabled，launchTorch 会恒发 IS_ENABLE=false；
     *   该问题由 [installBackTapTorchToggleHook] 单独修正（本 hook 只负责保住配置）。
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
