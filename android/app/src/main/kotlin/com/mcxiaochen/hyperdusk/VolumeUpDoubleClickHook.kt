package com.mcxiaochen.hyperdusk

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * C 项：双击音量上键 → 执行 Settings.System#hyperdusk_double_click_volume_up 里配置的动作。
 *
 * ## 技术路线（三条候选的评估见 .research/notes/impl-system-hooks.md）
 * 主 hook：`com.android.server.policy.SingleKeyGestureDetector#interceptKey(KeyEvent, boolean, int)`；
 * 兜底 hook：`com.android.server.policy.BaseMiuiPhoneWindowManager#interceptKeyBeforeQueueingInternal(
 * KeyEvent, int, boolean)`（MIUI 音量/电源键策略入口）。
 * 两个 hook 都只「旁路观察」音量上键的按下事件并自行计数；不改参数、不吞事件、不替换返回值，
 * 同一次按键会先后经过两个 hook，用「事件对象 + eventTime」去重，避免同一次按下被计两次。
 *
 * 为什么主 hook 可行（反编译依据）：
 *  - services.jar PhoneWindowManager#interceptKeyBeforeQueueing 对**所有按键**都会调用
 *    handleKeyGesture()（PhoneWindowManager.java:3543），后者调用
 *    mSingleKeyGestureDetector.interceptKey(...)（:3887）；
 *  - 只有 repeatCount != 0 / 注入事件才会被 skipKeyGesutre() 跳过
 *    （MiuiShortcutTriggerHelper.java:507-512），正常音量键事件一定会到达 interceptKey；
 *  - 反编译里 SingleKeyGestureDetector 的 mRules 没有 keycode 24 的规则（MiuiSingleKeyRuleManager.java:44-72
 *    只有 3/25/26/27/326/690），所以探测器的规则匹配对音量上键是空转的——我们只在方法入口观察，
 *    不依赖规则注册，也不会干扰已有规则（音量下/电源键规则照常工作）。
 *
 * 为什么需要兜底 hook：
 *  - interceptKey 由 PhoneWindowManager#handleKeyGesture 调用，两个都是短方法，可能被 AOT 内联；
 *    虽然对被 hook 方法的调用方调了 deoptimize，但不同 ROM 的 AOT 策略可能不同，
 *    内联一旦发生主 hook 就静默不回调。MIUI 的 interceptKeyBeforeQueueingInternal
 *    （BaseMiuiPhoneWindowManager.java:1525，方法体很大不会被内联；由 MiuiPhoneWindowManager.java:279-281
 *    的 override 调用，是 HyperOS 全部按键策略的入口）是同一调用链上更早、更稳的观察点。
 *
 * 为什么这样不会吞掉正常音量调节：
 *  - interceptKey 返回 void，原按键事件照常进入后续的 interceptKeyBeforeDispatching → AudioService 调音量；
 *  - 我们只在**灭屏**时触发动作（见 [isScreenOff]，AOD 也算灭屏），亮屏（含锁屏未解锁）只更新一个时间戳，
 *    音量键行为与未装模块时完全一致。
 *
 * 触发行为对齐 VolumeDownKeyRule（VolumeDownKeyRule.java:74-98、128-142）：
 *  - 仅灭屏时触发（本版按真机反馈收紧：亮屏即使锁屏也不触发，减少误触；AOD/doze 时 isInteractive=false 正好放行）；
 *  - 媒体播放/通话中不触发（对齐 VolumeDownKeyRule#isAudioActive，:128-142）；
 *  - 触发前 wakeUp（唤醒屏幕），再 dispatch；
 *  - dispatch 复用 ShortCutActionsUtils#triggerFunction(String, String, Bundle, boolean)
 *    （ShortCutActionsUtils.java:1272，4 参 public），action 传 "double_click_power_key"，
 *    与 PowerKeyRule#triggerDoubleClick 的即时路径一致（PowerKeyRule.java:366-383）：
 *    launch_camera 在 launchCamera 里被转成 reason "power_double_tap"（:1792-1794），
 *    mi_pay 在 launchMiPay 里被转成 "double_click_power"（:1755），其余原样；
 *    hapticFeedback=true 与系统双击电源键一致。
 *
 * 总开关（规格 §8.3）：hook 安装不受开关影响，[dispatch] 入口每次读 RemotePreferences 的
 * `module.enabled`，关闭时直接返回；开关切换不需要重启 system_server。
 */
internal object VolumeUpDoubleClickHook {

    private const val TAG = HyperDuskModule.TAG

    /** 配置来源：Settings.System（system_server 直接可读）。值是「双击电源键」同款 function 字符串。 */
    private const val SETTINGS_KEY = "hyperdusk_double_click_volume_up"

    private const val FUNCTION_LAUNCH_CAMERA = "launch_camera"
    private const val FUNCTION_TURN_ON_TORCH = "turn_on_torch"
    private const val FUNCTION_MI_PAY = "mi_pay"
    private const val FUNCTION_LAUNCH_SMARTHOME = "launch_smarthome"
    private const val FUNCTION_NONE = "none"

    /**
     * 允许触发的 function 白名单：既覆盖「双击电源键」的本版可选项，也避免 Settings 被写入任意字符串后
     * 让 system_server 执行到意外分支。
     */
    private val ALLOWED_FUNCTIONS = setOf(
        FUNCTION_LAUNCH_CAMERA,
        FUNCTION_TURN_ON_TORCH,
        FUNCTION_MI_PAY,
        FUNCTION_LAUNCH_SMARTHOME,
    )

    /** 动作名与 PowerKeyRule#triggerDoubleClick 即时路径一致（PowerKeyRule.java:381）。 */
    private const val ACTION_REASON = "double_click_power_key"

    private const val CLASS_DETECTOR = "com.android.server.policy.SingleKeyGestureDetector"
    private const val CLASS_PHONE_WINDOW_MANAGER = "com.android.server.policy.PhoneWindowManager"
    private const val CLASS_MIUI_PHONE_WINDOW_MANAGER = "com.android.server.policy.BaseMiuiPhoneWindowManager"
    private const val CLASS_SHORT_CUT_ACTIONS = "com.miui.server.input.util.ShortCutActionsUtils"

    private const val HOOK_INTERCEPT_KEY = "system_server:SingleKeyGestureDetector#interceptKey"
    private const val HOOK_MIUI_INTERCEPT_KEY = "system_server:BaseMiuiPhoneWindowManager#interceptKeyBeforeQueueingInternal"
    private const val HOOK_GET_SHORT_CUT_ACTIONS = "system_server:ShortCutActionsUtils#getInstance"

    /** WindowManagerPolicy.FLAG_INJECTED，与 VolumeDownKeyRule 里 `mPolicyFlag & 16777216` 判定同源。 */
    private const val FLAG_INJECTED = 0x01000000

    /** 与 SingleKeyGestureDetector.MULTI_PRESS_TIMEOUT 同源（ViewConfiguration.getMultiPressTimeout()）。 */
    private var multiPressTimeoutMs = 400L

    /** 空转保护：挂上 hook 却一直不回调时给出可诊断的日志。 */
    private val loggedTrackerEvents = AtomicBoolean(false)
    private val loggedMissingContext = AtomicBoolean(false)
    private val loggedTrigger = AtomicBoolean(false)
    private val loggedTriggerFailure = AtomicBoolean(false)
    private val loggedInvalidValue = AtomicBoolean(false)
    private val loggedAudioSkip = AtomicBoolean(false)

    /** 总开关关闭导致的跳过只打一次日志，避免每次双击都刷屏（规格 §8.4）。 */
    private val loggedDisabled = AtomicBoolean(false)

    @Volatile
    private var mainHandler: Handler? = null

    /** 上一次音量上键按下的 uptime（0 表示无待配对的按下）；只在输入线程读写。 */
    private var lastVolumeUpDownTime = 0L

    /**
     * 主 hook 与兜底 hook 会拿到同一次按键的同一个 KeyEvent 实例（MIUI 入口直接把 event 透传给 AOSP 策略），
     * 用「实例 + eventTime」去重，防止同一次按下被计两次。
     * KeyEvent 会被 InputDispatcher 回收复用，所以不能只比较实例。
     */
    @Volatile
    private var lastSeenEvent: KeyEvent? = null

    @Volatile
    private var lastSeenEventTime = -1L

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val detectorClass = classLoader.findClassOrNull(CLASS_DETECTOR)
        val interceptKey = detectorClass?.findMethodOrNull(
            "interceptKey",
            arrayOf(KeyEvent::class.java, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
        )
        if (interceptKey == null) {
            module.log(Log.WARN, TAG, "primary hook target not found: $CLASS_DETECTOR#interceptKey")
        }

        // interceptKey 由 PhoneWindowManager#handleKeyGesture（private void）调用，短方法可能被 AOT 内联；
        // 这里 deoptimize 已知调用链上的两个方法（本 build 只有这一条调用链，见反编译
        // PhoneWindowManager.java:3887 与 :3543）：
        //  - handleKeyGesture：interceptKey 的直接调用方；
        //  - interceptKeyBeforeQueueing：handleKeyGesture 的调用方（万一 handleKeyGesture 被内联进它）。
        val phoneWindowManager = classLoader.findClassOrNull(CLASS_PHONE_WINDOW_MANAGER)
        module.tryDeoptimize(
            phoneWindowManager?.findMethodOrNull(
                "handleKeyGesture",
                arrayOf(KeyEvent::class.java, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            ),
            "caller of SingleKeyGestureDetector#interceptKey",
        )
        module.tryDeoptimize(
            phoneWindowManager?.findMethodOrNull(
                "interceptKeyBeforeQueueing",
                arrayOf(KeyEvent::class.java, Int::class.javaPrimitiveType!!),
            ),
            "caller of handleKeyGesture (prevents inlining handleKeyGesture into it)",
        )

        multiPressTimeoutMs = ViewConfiguration.getMultiPressTimeout().toLong()
        mainHandler = Handler(Looper.getMainLooper())

        ShortCutActionsBridge.install(module, classLoader)

        // 主 hook：SingleKeyGestureDetector#interceptKey（参数 1 = interactive）
        val primaryInstalled = interceptKey != null &&
            installKeyHook(module, HOOK_INTERCEPT_KEY, interceptKey, interactiveArgIndex = 1)
        // 兜底 hook：MIUI 按键策略入口（参数 2 = isScreenOn；方法体很大，不会被内联）
        val fallbackInstalled = installMiuiKeyHook(module, classLoader)

        if (!primaryInstalled && !fallbackInstalled) {
            module.log(Log.ERROR, TAG, "volume up double click disabled: no key hook could be installed")
            return
        }
        module.log(
            Log.INFO,
            TAG,
            "volume up double click hooks installed (primary=$primaryInstalled, " +
                "miuiFallback=$fallbackInstalled, multiPressTimeout=${multiPressTimeoutMs}ms)",
        )
    }

    /** 安装一个「观察按键」hook；interactiveArgIndex 指定哪个参数是屏幕交互状态。 */
    private fun installKeyHook(
        module: XposedModule,
        hookId: String,
        method: Method,
        interactiveArgIndex: Int,
        policyFlagsArgIndex: Int? = null,
    ): Boolean = module.hookMethod(hookId, method) { chain ->
        try {
            val event = chain.getArg(0)
            val interactive = chain.getArg(interactiveArgIndex) as? Boolean ?: false
            // 与 VolumeDownKeyRule#volumeDownKeyDoubleClick 一致：注入事件（adb input / 辅助服务模拟）不触发
            val injected = if (policyFlagsArgIndex != null) {
                val policyFlags = chain.getArg(policyFlagsArgIndex) as? Int ?: 0
                (policyFlags and FLAG_INJECTED) != 0
            } else {
                false
            }
            if (event is KeyEvent && !injected) {
                onKeyEvent(module, event, interactive)
            }
        } catch (t: Throwable) {
            // 旁路逻辑无论出什么问题都不能影响按键处理本身
            module.log(Log.ERROR, TAG, "volume up double click tracker error", t)
        }
        chain.proceed()
    } != null

    /**
     * 兜底 hook：`BaseMiuiPhoneWindowManager#interceptKeyBeforeQueueingInternal(KeyEvent, int, boolean)`
     * （BaseMiuiPhoneWindowManager.java:1525）。它是 MiuiPhoneWindowManager.java:279-281 这个
     * WindowManagerPolicy#interceptKeyBeforeQueueing 实现的实体，HyperOS 上所有按键都会经过它。
     */
    private fun installMiuiKeyHook(module: XposedModule, classLoader: ClassLoader): Boolean {
        val method = classLoader.findClassOrNull(CLASS_MIUI_PHONE_WINDOW_MANAGER)?.findMethodOrNull(
            "interceptKeyBeforeQueueingInternal",
            arrayOf(
                KeyEvent::class.java,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            ),
        )
        if (method == null) {
            module.log(
                Log.WARN,
                TAG,
                "miui key hook not found: $CLASS_MIUI_PHONE_WINDOW_MANAGER#interceptKeyBeforeQueueingInternal",
            )
            return false
        }
        return installKeyHook(module, HOOK_MIUI_INTERCEPT_KEY, method, interactiveArgIndex = 2, policyFlagsArgIndex = 1)
    }

    /**
     * 输入线程上的轻量状态机：只记录时间戳，真正的读取设置/触发动作统一 post 到主线程，
     * 避免在输入分发线程上做 ContentProvider/Binder 调用。
     */
    private fun onKeyEvent(module: XposedModule, event: KeyEvent, interactive: Boolean) {
        if (isDuplicateFromSecondaryHook(event)) return
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            // 中间按下过其它键：本次双击不成立
            lastVolumeUpDownTime = 0L
            return
        }
        if (loggedTrackerEvents.compareAndSet(false, true)) {
            module.log(Log.INFO, TAG, "volume up key observed (interactive=$interactive), double tap detection armed")
        }

        val now = SystemClock.uptimeMillis()
        val previous = lastVolumeUpDownTime
        if (previous != 0L && now - previous in 0..multiPressTimeoutMs) {
            lastVolumeUpDownTime = 0L
            val handler = mainHandler ?: return
            handler.post { dispatch(module, interactive) }
        } else {
            lastVolumeUpDownTime = now
        }
    }

    /**
     * 主 hook 与兜底 hook 都在同一条调用链上，同一次按键会先经过 MIUI 入口、再到 SingleKeyGestureDetector。
     * MIUI 入口把同一个 KeyEvent 实例继续往下传，所以「实例相同 + eventTime 相同」= 同一次按键，跳过第二次。
     * KeyEvent 会被 InputDispatcher 回收复用，必须同时比对 eventTime，避免复用实例被误判成同一次按键。
     */
    private fun isDuplicateFromSecondaryHook(event: KeyEvent): Boolean {
        if (lastSeenEvent === event && lastSeenEventTime == event.eventTime) return true
        lastSeenEvent = event
        lastSeenEventTime = event.eventTime
        return false
    }

    private fun dispatch(module: XposedModule, interactiveFallback: Boolean) {
        try {
            // 规格 §8.3：总开关在运行时判断。hook 已经装好不撤（撤了就只能重启 system_server 才能恢复），
            // 关闭时在这里直接返回，等于回到「没装模块」的行为。
            if (!HyperDuskPrefs.getBoolean(HyperDuskPrefs.KEY_MODULE_ENABLED, true)) {
                if (loggedDisabled.compareAndSet(false, true)) {
                    module.log(
                        Log.INFO,
                        TAG,
                        "volume up double click paused: ${HyperDuskPrefs.KEY_MODULE_ENABLED}=false",
                    )
                }
                return
            }

            val context = ShortCutActionsBridge.context
            if (context == null) {
                if (loggedMissingContext.compareAndSet(false, true)) {
                    module.log(
                        Log.WARN,
                        TAG,
                        "volume up double click: system context not captured " +
                            "(ShortCutActionsUtils#getInstance hook never called), action dropped",
                    )
                }
                return
            }

            val function = readConfiguredFunction(module, context) ?: return
            if (!isScreenOff(context, interactiveFallback)) return
            if (isAudioActive(module, context)) return

            wakeUp(module, context, function)
            if (ShortCutActionsBridge.trigger(function)) {
                if (loggedTrigger.compareAndSet(false, true)) {
                    module.log(Log.INFO, TAG, "volume up double click -> $function")
                }
            } else if (loggedTriggerFailure.compareAndSet(false, true)) {
                module.log(Log.WARN, TAG, "volume up double click: ShortCutActionsUtils#triggerFunction unavailable")
            }
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "volume up double click dispatch failed", t)
        }
    }

    /** 读取配置；空值 / "none" / 未知值都不触发。 */
    private fun readConfiguredFunction(module: XposedModule, context: Context): String? {
        val value = try {
            Settings.System.getString(context.contentResolver, SETTINGS_KEY)
        } catch (t: Throwable) {
            null
        }?.trim()
        if (value.isNullOrEmpty() || value == FUNCTION_NONE) return null
        if (value !in ALLOWED_FUNCTIONS) {
            if (loggedInvalidValue.compareAndSet(false, true)) {
                module.log(Log.WARN, TAG, "volume up double click: unsupported function value \"$value\"")
            }
            return null
        }
        return value
    }

    /**
     * 触发门控：仅屏幕灭时触发（真机反馈修复）。
     *
     * 第一版是「灭屏或锁屏」，真机上亮屏锁屏时误触太多；用户要求收紧为：
     * 除 AOD 外，亮屏（哪怕锁屏未解锁）一律不触发。AOD/doze 时 `PowerManager#isInteractive()` 为 false，
     * 因此这里只判断 `!isInteractive`，AOD 正好放行。
     */
    private fun isScreenOff(context: Context, interactiveFallback: Boolean): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = powerManager?.isInteractive ?: interactiveFallback
        return !interactive
    }

    /**
     * 媒体播放/通话中不触发（对齐 VolumeDownKeyRule#isAudioActive，VolumeDownKeyRule.java:128-142）：
     *  1) `AudioManager.getMode()` 落在 (0, 8) 开区间（通话/录音等）→ 视为活跃；
     *  2) 否则用 `AudioSystem.isStreamActive(stream, 0)` 检查（隐藏类，反射；跳过 stream type 1，与原实现一致）；
     *  3) AudioSystem 反射不可用时退化公开 API `AudioManager.isMusicActive()`。
     */
    private fun isAudioActive(module: XposedModule, context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            try {
                val mode = audioManager.mode
                if (mode > 0 && mode < 8) {
                    if (loggedAudioSkip.compareAndSet(false, true)) {
                        module.log(Log.INFO, TAG, "volume up double click skipped: audio mode=$mode")
                    }
                    return true
                }
            } catch (t: Throwable) {
                // 继续走 stream 检查
            }
        }
        val anyStreamActive = isAnyAudioStreamActive()
        val active = anyStreamActive ?: try {
            audioManager?.isMusicActive == true
        } catch (t: Throwable) {
            false
        }
        if (active && loggedAudioSkip.compareAndSet(false, true)) {
            module.log(Log.INFO, TAG, "volume up double click skipped: audio stream active")
        }
        return active
    }

    /**
     * 反射 `AudioSystem#getNumStreamTypes()` + `isStreamActive(int, int)`。
     * 返回 null 表示反射不可用（调用方退化 AudioManager.isMusicActive）。
     * 与原实现的遍历一致：stream type 1（STREAM_VOICE_CALL）跳过，其余任一活跃即视为音频活跃。
     */
    private fun isAnyAudioStreamActive(): Boolean? {
        val audioSystem = try {
            Class.forName("android.media.AudioSystem")
        } catch (t: Throwable) {
            null
        } ?: return null
        val numStreamTypes = try {
            audioSystem.getMethod("getNumStreamTypes").invoke(null) as? Int
        } catch (t: Throwable) {
            null
        } ?: return null
        val isStreamActive = try {
            audioSystem.getMethod(
                "isStreamActive",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        } catch (t: Throwable) {
            null
        } ?: return null
        for (streamType in 0 until numStreamTypes) {
            if (streamType == 1) continue
            val active = try {
                isStreamActive.invoke(null, streamType, 0) as? Boolean ?: false
            } catch (t: Throwable) {
                false
            }
            if (active) return true
        }
        return false
    }

    /**
     * 触发前唤醒（与 MIUI 相机手势一致）。
     *
     * MIUI 自己用的是隐藏 API `PowerManager#wakeUp(long, int, String)`（反编译
     * ShortCutActionsUtils.java:1810 等），公开 SDK 里没有，所以这里反射调用 3 参 / 1 参重载；
     * 反射被隐藏 API 策略拦截时降级为公开的 WakeLock API（ACQUIRE_CAUSES_WAKEUP 点亮屏幕）。
     */
    @Suppress("DEPRECATION")
    private fun wakeUp(module: XposedModule, context: Context, function: String) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        // MIUI 相机手势传 5（WAKE_REASON_CAMERA_LAUNCH）；其它动作传 4（WAKE_REASON_GESTURE）
        val reason = if (function == FUNCTION_LAUNCH_CAMERA) 5 else 4
        if (invokeHiddenWakeUp(powerManager, reason)) return
        try {
            // acquire(timeout) 超时自动释放；ACQUIRE_CAUSES_WAKEUP 会在获取时点亮屏幕
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "HyperDusk:doubleClickVolumeUp",
            ).apply {
                setReferenceCounted(false)
                acquire(1000L)
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "volume up double click: wakeUp unavailable", t)
        }
    }

    private fun invokeHiddenWakeUp(powerManager: PowerManager, reason: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val threeArgs = try {
            powerManager.javaClass.getMethod(
                "wakeUp",
                Long::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                String::class.java,
            )
        } catch (t: Throwable) {
            null
        }
        if (threeArgs != null) {
            try {
                threeArgs.invoke(powerManager, now, reason, "HyperDusk:doubleClickVolumeUp")
                return true
            } catch (_: Throwable) {
                // fall through to the 1-arg overload
            }
        }
        val oneArg = try {
            powerManager.javaClass.getMethod("wakeUp", Long::class.javaPrimitiveType!!)
        } catch (t: Throwable) {
            null
        }
        if (oneArg != null) {
            try {
                oneArg.invoke(powerManager, now)
                return true
            } catch (_: Throwable) {
                // fall through
            }
        }
        return false
    }

    /**
     * 复用 system_server 里的 ShortCutActionsUtils 单例。
     *
     * 实例由 MiuiPhoneWindowManager#systemReady 创建（MiuiPhoneWindowManager.java:154
     * 调 getInstance(context).systemReady()），随后 backtap / 按键手势都会再调 getInstance。
     * 这里 hook 公共静态方法 getInstance(Context) 顺便拿到 system_server 的 Context 和实例，
     * 完全不用反射内部字段。
     */
    private object ShortCutActionsBridge {

        @Volatile
        var context: Context? = null
            private set

        @Volatile
        private var instance: Any? = null

        @Volatile
        private var triggerMethod: Method? = null

        fun install(module: XposedModule, classLoader: ClassLoader) {
            val clazz = classLoader.findClassOrNull(CLASS_SHORT_CUT_ACTIONS)
            if (clazz == null) {
                module.log(Log.WARN, TAG, "shortcut bridge skipped, class not found: $CLASS_SHORT_CUT_ACTIONS")
                return
            }
            val getInstance = clazz.findMethodOrNull("getInstance", arrayOf(Context::class.java))
            if (getInstance == null) {
                module.log(Log.WARN, TAG, "shortcut bridge skipped, method not found: $CLASS_SHORT_CUT_ACTIONS#getInstance")
                return
            }
            module.hookMethod(HOOK_GET_SHORT_CUT_ACTIONS, getInstance) { chain ->
                val arg = chain.getArg(0)
                val result = chain.proceed()
                if (arg is Context) {
                    context = arg
                    attachInstance(clazz, result)
                }
                result
            }
        }

        private fun attachInstance(clazz: Class<*>, result: Any?) {
            if (result == null || instance === result) return
            instance = result
            triggerMethod = clazz.findMethodOrNull(
                "triggerFunction",
                arrayOf(
                    String::class.java,
                    String::class.java,
                    Bundle::class.java,
                    Boolean::class.javaPrimitiveType!!,
                ),
            )
        }

        /** 调用 ShortCutActionsUtils#triggerFunction(function, action, extras, hapticFeedback)。 */
        fun trigger(function: String): Boolean {
            val target = instance ?: return false
            val method = triggerMethod ?: return false
            return try {
                method.invoke(target, function, ACTION_REASON, null, true) as? Boolean ?: false
            } catch (t: Throwable) {
                false
            }
        }
    }
}
