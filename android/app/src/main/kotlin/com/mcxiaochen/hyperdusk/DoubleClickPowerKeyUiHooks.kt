package com.mcxiaochen.hyperdusk

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 需求 B：复用「双击电源键」二级选择页作为「双击音量上键」的二级菜单。
 *
 * 逆向依据（.research/notes/securitycore-gestures.md §4 / §6.2）：
 *  - 页面 = `com.miui.miinput.gesture.powerkey.DoubleClickPowerKeySettingsActivity`（类名未混淆，§4.1）
 *    + Fragment `j2.b`（混淆名，log tag `DoubleClickPowerKeySettingsFragment`，§4.1）；
 *  - 选项列表来自 XML `res/GQ.xml` 的静态 SingleChoicePreference，用
 *    `t.f(ctx).j(1)` 过滤可见性（§4.2），所以复用本页 = 选项天然与双击电源键完全一致；
 *  - 写入点：`j2.b#r(String function, String displayKey, String displayValue)`（j2/b.java:287），
 *    原实现固定写 `Settings.System.double_click_power_key`（:289）；读当前值在 `onResume`（:102-125，:105）。
 *
 * 三个 hook：
 *  1) `DoubleClickPowerKeySettingsActivity#onCreate` after：intent 带
 *     `hyperdusk_target_key=hyperdusk_double_click_volume_up` 时把标题改成「双击音量上键」；
 *  2) `j2.b#onResume` after：本页按新 key 重新定位选中项（覆盖原生按 double_click_power_key 的选中）；
 *  3) `j2.b#r` replace：本页把写入目标改成 `hyperdusk_double_click_volume_up`（与 system_server 侧一致），
 *     非本页（intent 无该 extra）照常 `chain.proceed()`。
 *
 * 混淆名（`j2.b`）不是唯一依据：
 *  - 安装阶段先试类名候选 `j2.b` + 结构守卫（父类链是 androidx.preference 页面 Fragment、存在
 *    void r(String,String,String) 写方法、存在 onResume）；
 *  - 守卫失败时退回到「运行时 Fragment 发现」：Activity onCreate 是我们 hook 的稳定锚点，页面打开后
 *    用 `findFragmentById(android.R.id.content)` 拿到真实 Fragment 实例，再对它 actual 的 class 装 onResume/r hook；
 *  - 运行时还有内容守卫：Fragment 必须能取到「内容含 `miuix.preference.SingleChoicePreference` 的 List 字段」。
 */
internal object DoubleClickPowerKeyUiHooks {

    private const val TAG = HyperDuskModule.TAG
    private const val HOOK_ID_PAGE_ON_CREATE = "securitycore:DoubleClickPowerKeySettingsActivity#onCreate"
    private const val HOOK_ID_FRAGMENT_ON_RESUME = "securitycore:DoubleClickPowerKeyFragment#onResume"
    private const val HOOK_ID_FRAGMENT_WRITE = "securitycore:DoubleClickPowerKeyFragment#writeSettings"

    private const val MAX_ATTEMPTS = 25
    private const val RETRY_DELAY_MS = 16L

    private val fragmentHooksInstalled = AtomicBoolean(false)
    private val loggedTitle = AtomicBoolean(false)
    private val loggedSelection = AtomicBoolean(false)
    private val loggedWrite = AtomicBoolean(false)
    private val loggedPageMissing = AtomicBoolean(false)

    fun install(module: XposedModule, classLoader: ClassLoader) {
        installPageHook(module, classLoader)
        // 优先在包加载阶段用类名候选 + 结构守卫装 Fragment hook；失败则由 Activity onCreate 的
        // 主线程任务在页面打开时用真实 Fragment 类兜底安装。
        val candidate = classLoader.findClassOrNull(SecurityCoreHooks.CANDIDATE_DOUBLE_CLICK_FRAGMENT_CLASS)
        if (candidate != null && looksLikeDoubleClickFragmentClass(candidate)) {
            installFragmentHooks(module, classLoader, candidate)
        } else {
            module.log(
                Log.WARN,
                TAG,
                "double click fragment candidate " +
                    "${SecurityCoreHooks.CANDIDATE_DOUBLE_CLICK_FRAGMENT_CLASS} missing or guard failed; " +
                    "will install hooks from the live fragment class on first page open",
            )
        }
    }

    // ---------------------------------------------------------------
    // hook 1：页面 onCreate → 标题 + 触发「运行时兜底安装 + 选中态修正」
    // ---------------------------------------------------------------

    private fun installPageHook(module: XposedModule, classLoader: ClassLoader) {
        val activityClass = classLoader.findClassOrNull(SecurityCoreHooks.CLASS_DOUBLE_CLICK_POWER_KEY_ACTIVITY)
        if (activityClass == null) {
            module.log(
                Log.WARN,
                TAG,
                "double click page hook skipped, activity not found: " +
                    SecurityCoreHooks.CLASS_DOUBLE_CLICK_POWER_KEY_ACTIVITY,
            )
            return
        }
        val onCreate = activityClass.findMethodOrNull("onCreate", arrayOf(Bundle::class.java))
        if (onCreate == null) {
            module.log(Log.WARN, TAG, "double click page hook skipped, onCreate not found")
            return
        }
        module.hookMethod(HOOK_ID_PAGE_ON_CREATE, onCreate) { chain ->
            val result = chain.proceed()
            try {
                val activity = chain.getThisObject() as? Activity
                if (activity != null && targetKeyOf(activity) == SecurityCoreHooks.SETTINGS_KEY_VOLUME_UP) {
                    // 覆盖原生 onCreate 里的 setTitle(R.string.double_click_power_key)
                    // （DoubleClickPowerKeySettingsActivity.java:27）
                    activity.setTitle(SecurityCoreHooks.localized("双击音量上键", "Double-tap Volume up"))
                    if (loggedTitle.compareAndSet(false, true)) {
                        module.log(Log.INFO, TAG, "double click page reused as volume-up page (title overridden)")
                    }
                    schedulePageAdjust(module, classLoader, activity)
                }
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "double click page title override failed", t)
            }
            result
        }
    }

    private fun schedulePageAdjust(module: XposedModule, classLoader: ClassLoader, activity: Activity) {
        val handler = Handler(Looper.getMainLooper())
        var attempt = 0
        val task = object : Runnable {
            override fun run() {
                attempt++
                if (activity.isFinishing || activity.isDestroyed) return
                val done = try {
                    adjustPage(module, classLoader, activity)
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "double click page adjust failed", t)
                    true
                }
                if (!done && attempt < MAX_ATTEMPTS) {
                    handler.postDelayed(this, RETRY_DELAY_MS)
                } else if (!done && loggedPageMissing.compareAndSet(false, true)) {
                    module.log(
                        Log.WARN,
                        TAG,
                        "double click page fragment not found after $attempt attempts",
                    )
                }
            }
        }
        handler.post(task)
    }

    private fun adjustPage(module: XposedModule, classLoader: ClassLoader, activity: Activity): Boolean {
        val fragment = SecurityCoreHooks.findContentFragment(classLoader, activity) ?: return false
        if (!looksLikeDoubleClickFragment(classLoader, fragment)) return false
        // 混淆名守卫失败时的运行时兜底：用真实 Fragment 类装 onResume / r hook。
        installFragmentHooks(module, classLoader, fragment.javaClass)
        // 首次打开时 Fragment 的 onResume 可能早于兜底 hook 安装，这里直接补一次选中态。
        applySelectedFunction(module, classLoader, fragment)
        return true
    }

    // ---------------------------------------------------------------
    // hook 2/3：Fragment（j2.b）onResume / r(String,String,String)
    // ---------------------------------------------------------------

    private fun installFragmentHooks(module: XposedModule, classLoader: ClassLoader, fragmentClass: Class<*>) {
        if (fragmentHooksInstalled.get()) return
        synchronized(this) {
            if (fragmentHooksInstalled.get()) return
            val onResume = fragmentClass.findMethodOrNull("onResume")
            if (onResume == null) {
                module.log(Log.WARN, TAG, "double click fragment onResume not found: ${fragmentClass.name}")
                return
            }
            val writeMethod = resolveWriteMethod(fragmentClass)
            if (writeMethod == null) {
                module.log(
                    Log.WARN,
                    TAG,
                    "double click fragment write method (String,String,String) not found: ${fragmentClass.name}",
                )
                return
            }

            module.hookMethod(HOOK_ID_FRAGMENT_ON_RESUME, onResume) { chain ->
                val result = chain.proceed()
                try {
                    val fragment = chain.getThisObject()
                    if (targetKeyOfFragment(fragment) == SecurityCoreHooks.SETTINGS_KEY_VOLUME_UP) {
                        applySelectedFunction(module, classLoader, fragment)
                    }
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "double click fragment onResume adjust failed", t)
                }
                result
            }

            module.hookMethod(HOOK_ID_FRAGMENT_WRITE, writeMethod) { chain ->
                val fragment = chain.getThisObject()
                val targetKey = try {
                    targetKeyOfFragment(fragment)
                } catch (t: Throwable) {
                    null
                }
                if (targetKey != SecurityCoreHooks.SETTINGS_KEY_VOLUME_UP) {
                    // 普通「双击电源键」页：完全保持原逻辑
                    chain.proceed()
                } else {
                    try {
                        redirectWrite(module, classLoader, fragment, chain)
                    } catch (t: Throwable) {
                        // 失败也不能放行原方法：原方法会写 double_click_power_key，把音量上键的配置写坏
                        module.log(Log.WARN, TAG, "redirect write failed, original write suppressed", t)
                        null
                    }
                }
            }

            fragmentHooksInstalled.set(true)
            module.log(Log.INFO, TAG, "double click fragment hooks installed on ${fragmentClass.name}")
        }
    }

    /**
     * 把原方法 `r(function, displayKey, displayValue)`（j2/b.java:287-299）重定向到新 key：
     *  - 主写入：`Settings.System.hyperdusk_double_click_volume_up = function`；
     *  - 背屏展示位置（可选参数）：与原生一样写 displayKey/displayValue；
     *  - 原生这里还有「付款码通知时间重置」逻辑（:290-294），仅对支付宝/微信付款码生效且选项通常不可见，
     *    本版不复刻（见实现说明风险项）。
     */
    private fun redirectWrite(
        module: XposedModule,
        classLoader: ClassLoader,
        fragment: Any?,
        chain: io.github.libxposed.api.XposedInterface.Chain,
    ) {
        val function = chain.getArg(0) as? String ?: return
        val context = fragmentContext(fragment) ?: return
        val displayKey = chain.getArg(1) as? String
        val displayValue = chain.getArg(2) as? String

        val written = SecurityCoreHooks.writeSystemString(
            classLoader,
            context,
            SecurityCoreHooks.SETTINGS_KEY_VOLUME_UP,
            function,
        )
        if (displayKey != null && displayValue != null) {
            SecurityCoreHooks.writeSystemString(classLoader, context, displayKey, displayValue)
        }
        if (written && loggedWrite.compareAndSet(false, true)) {
            module.log(
                Log.INFO,
                TAG,
                "write redirected: double_click_power_key -> ${SecurityCoreHooks.SETTINGS_KEY_VOLUME_UP} " +
                    "(function=$function)",
            )
        }
    }

    /**
     * 按新 key 的当前值重设选中项，覆盖原生 onResume（j2/b.java:102-125）按 double_click_power_key 的选中。
     * 选中列表字段名 `f`（j2/b.java:39 `public final ArrayList f`）；字段名混淆时按内容兜底：
     * 找第一个「元素含 miuix.preference.SingleChoicePreference」的 List 字段。
     */
    private fun applySelectedFunction(module: XposedModule, classLoader: ClassLoader, fragment: Any?) {
        val context = fragmentContext(fragment) ?: return
        val list = findSingleChoiceList(classLoader, fragment) ?: return
        val raw = SecurityCoreHooks.readSystemString(
            classLoader,
            context,
            SecurityCoreHooks.SETTINGS_KEY_VOLUME_UP,
        )?.trim().orEmpty()
        val availableKeys = list.mapNotNull { ScReflect.call(it, listOf("getKey")) as? String }
        // 与原生语义一致：当前值不在可选项里时按 none 显示
        val effective = if (raw.isNotEmpty() && availableKeys.contains(raw)) raw else SecurityCoreHooks.FUNCTION_NONE
        for (preference in list) {
            val key = ScReflect.call(preference, listOf("getKey")) as? String
            try {
                ScReflect.call(preference, listOf("setChecked"), key == effective)
            } catch (t: Throwable) {
                // 单个项失败不影响其它项
            }
        }
        if (loggedSelection.compareAndSet(false, true)) {
            module.log(
                Log.INFO,
                TAG,
                "selection restored from ${SecurityCoreHooks.SETTINGS_KEY_VOLUME_UP}: $effective",
            )
        }
    }

    // ---------------------------------------------------------------
    // 结构守卫 / 查找工具
    // ---------------------------------------------------------------

    /** 安装阶段的结构守卫：父类 + 方法签名（不依赖混淆类名本身）。 */
    private fun looksLikeDoubleClickFragmentClass(clazz: Class<*>): Boolean {
        if (!SecurityCoreHooks.isPreferenceFragment(clazz)) return false
        if (resolveWriteMethod(clazz) == null) return false
        return clazz.declaredMethods.any { it.name == "onResume" && it.parameterCount == 0 }
    }

    /** 运行时守卫：在真实 Fragment 实例上再验一遍，并确认 SingleChoicePreference 列表字段存在。 */
    private fun looksLikeDoubleClickFragment(classLoader: ClassLoader, fragment: Any): Boolean {
        val clazz = fragment.javaClass
        if (!SecurityCoreHooks.isPreferenceFragment(clazz)) return false
        if (resolveWriteMethod(clazz) == null) return false
        return findSingleChoiceList(classLoader, fragment) != null
    }

    /**
     * 写方法定位：`j2.b#r(String,String,String)` 名字混淆时按「void + 三个 String 参数」签名兜底
     * （反编译 j2/b.java:287）。
     */
    private fun resolveWriteMethod(clazz: Class<*>): Method? {
        val stringTriple = arrayOf(String::class.java, String::class.java, String::class.java)
        clazz.declaredMethods.firstOrNull { method ->
            method.name == "r" &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 3 &&
                method.parameterTypes.contentEquals(stringTriple)
        }?.let { return it }
        return clazz.declaredMethods.firstOrNull { method ->
            method.returnType == Void.TYPE &&
                method.parameterCount == 3 &&
                method.parameterTypes.contentEquals(stringTriple)
        }
    }

    private fun findSingleChoiceList(classLoader: ClassLoader, fragment: Any?): List<*>? {
        if (fragment == null) return null
        val singleChoiceClass = classLoader.findClassOrNull(SecurityCoreHooks.CLASS_SINGLE_CHOICE_PREFERENCE)
        val known = ScReflect.fieldValue(fragment, "f")
        if (known is List<*> && known.isNotEmpty() &&
            (singleChoiceClass == null || known.any { singleChoiceClass.isInstance(it) })
        ) {
            return known
        }
        if (singleChoiceClass == null) return null
        val byContent = ScReflect.firstFieldValue(fragment) { _, value ->
            value is List<*> && value.any { singleChoiceClass.isInstance(it) }
        }
        return byContent as? List<*>
    }

    private fun targetKeyOf(activity: Activity): String? =
        activity.intent?.getStringExtra(SecurityCoreHooks.EXTRA_TARGET_KEY)

    private fun targetKeyOfFragment(fragment: Any?): String? {
        val activity = ScReflect.call(fragment, listOf("getActivity")) as? Activity ?: return null
        return targetKeyOf(activity)
    }

    private fun fragmentContext(fragment: Any?): android.content.Context? =
        ScReflect.call(fragment, listOf("getContext")) as? android.content.Context
            ?: ScReflect.call(fragment, listOf("getActivity")) as? android.content.Context
}
