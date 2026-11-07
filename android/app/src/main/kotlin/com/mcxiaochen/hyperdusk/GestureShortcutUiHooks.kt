package com.mcxiaochen.hyperdusk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 需求 A：在 securitycore「快捷手势」页的 `advanced_gesture` 分类里注入一行「双击音量上键」。
 *
 * 逆向依据（.research/notes/securitycore-gestures.md）：
 *  - 宿主 Activity `com.miui.miinput.gesture.GestureShortcutActivity` 类名未混淆（§2.1），
 *    其 onCreate 用普通异步 commit 提交页面 Fragment（D3.b.q → C0036a.d(false)，§5.1）；
 *    所以在 onCreate after-hook 里直接找 Fragment 还找不到，必须 post 到主线程（此时事务已执行、页面已 resumed）。
 *  - 页面 Fragment 被混淆成 p030f2.h，无法稳定硬编码；改用 preference key 识别页面：
 *    页面 XML = R.xml.gesture_shortcut_settings（§2.2），含 `function_shortcut` / `advanced_gesture` 两个稳定 key，
 *    这一层完全不需要混淆类名（§5.1 主选方案）。
 *  - `advanced_gesture` 分类里的「双击电源键」行是 `miuix.preference.TextPreference` + 静态 intent（§2.2）；
 *    新行用同一个类 + `setIntent` 指向「双击电源键」页并带 extra，由 [DoubleClickPowerKeyUiHooks] 把该页重定向成
 *    「双击音量上键」（多一次复用，选项天然与双击电源键完全一致，§4.4/§6.2）。
 *
 * 真机反馈修复（第二轮）：
 *  - 行 summary 原来一直是「未启用」：注入时就要 setSummary(当前配置的功能标题)，并在
 *    `GestureShortcutActivity#onResume` after 用 post+重试再刷新一次（页面 onResume 会把 summary 覆写）；
 *  - 新行原来追加在分类末尾：现在插到索引 1（「双击电源键」之后、「敲击手势/背部轻敲」之前；
 *    PreferenceGroup 没有公开索引 API，反射内部 Preference 列表后 move，见 SecurityCoreHooks.movePreferenceToIndex）。
 *
 * 真机反馈修复（第三轮，本轮）：
 *  - 行样式：`miuix.preference.TextPreference#h(String)` 是**右侧文本**（R.id.text_right，
 *    反编译 TextPreference.java:98-127），页面 `o()`（h.java:58-118）给「双击电源键」行设置的就是这个
 *    右文本 = 当前功能标题；我们此前用 setSummary 写成了第二行，导致「右侧未启用 + 第二行功能名」叠加。
 *    现在新行只用 `h(String)` 写右文本，与「双击电源键」行完全同源；行退化到普通 Preference 时才用 setSummary。
 *  - 刷新时机：页面 Fragment 的 onResume 会调 o() 把 advanced_gesture 里所有 TextPreference 的右文本重设，
 *    未知 key 走 else 分支显示「未启用」。所以在拿到页面 Fragment 实例后，动态 hook 它自己的 `onResume`
 *    （类名混淆，不硬编码）after 重新写右文本 + 防御性重排一次；Activity onResume 的刷新保留作兜底。
 *  - 排序：androidx adapter 重建时会 `Collections.sort(mPreferenceList)`（按 Preference.order），
 *    只改 list 顺序会被排回去，因此 move 的实现改为「重排所有兄弟行的 order」，见 movePreferenceToIndex。
 */
internal object GestureShortcutUiHooks {

    private const val TAG = HyperDuskModule.TAG
    private const val HOOK_ID = "securitycore:GestureShortcutActivity#onCreate"
    private const val HOOK_ID_RESUME = "securitycore:GestureShortcutActivity#onResume"
    private const val HOOK_ID_FRAGMENT_ON_RESUME = "securitycore:GestureShortcutSettingsFragment#onResume"

    /** 新行插在 advanced_gesture 分类索引 1：双击电源键(index 0) 之后、敲击/背部轻敲之前。 */
    private const val ROW_TARGET_INDEX = 1

    /** Fragment 事务是异步 commit，onCreate after 时 Fragment 还不存在；post + 少量重试（§5.1）。 */
    private const val MAX_ATTEMPTS = 25
    private const val RETRY_DELAY_MS = 16L

    private val loggedInjected = AtomicBoolean(false)
    private val loggedPageMissing = AtomicBoolean(false)
    private val loggedRowFailure = AtomicBoolean(false)
    private val loggedSummaryRefreshed = AtomicBoolean(false)
    private val fragmentResumeHookInstalled = AtomicBoolean(false)

    fun install(module: XposedModule, classLoader: ClassLoader, international: Boolean) {
        if (international) {
            // 国际版 DoubleClickPowerKeySettingsActivity 在 onCreate 里直接 finish()（§4.1），新行点了就关，不注入。
            module.log(
                Log.INFO,
                TAG,
                "gesture row injection skipped: international build " +
                    "(double click power key page finishes immediately)",
            )
            return
        }
        val activityClass = classLoader.findClassOrNull(SecurityCoreHooks.CLASS_GESTURE_SHORTCUT_ACTIVITY)
        if (activityClass == null) {
            module.log(
                Log.WARN,
                TAG,
                "gesture row injection skipped, activity not found: ${SecurityCoreHooks.CLASS_GESTURE_SHORTCUT_ACTIVITY}",
            )
            return
        }
        val onCreate = activityClass.findMethodOrNull("onCreate", arrayOf(Bundle::class.java))
        if (onCreate == null) {
            module.log(Log.WARN, TAG, "gesture row injection skipped, onCreate not found")
            return
        }
        module.hookMethod(HOOK_ID, onCreate) { chain ->
            // 先让原生 onCreate 跑完（包括提交 Fragment 事务），再安排主线程注入。
            val result = chain.proceed()
            try {
                val activity = chain.getThisObject() as? Activity
                if (activity != null) {
                    scheduleInjection(module, classLoader, activity)
                }
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "gesture row injection schedule failed", t)
            }
            result
        }
        installResumeHook(module, classLoader, activityClass)
    }

    /**
     * 页面 onResume 后刷新新行 summary。
     *
     * 注意 `GestureShortcutActivity` 自己没有声明 onResume（继承自 `p002a2.b#onResume`），
     * 所以这里 hook 到的是基类方法，会被 securitycore 所有同类 Activity 共用；
     * hooker 里按 `activity.javaClass.name == CLASS_GESTURE_SHORTCUT_ACTIVITY` 过滤，
     * 只有快捷手势页才 post 刷新任务。
     */
    private fun installResumeHook(module: XposedModule, classLoader: ClassLoader, activityClass: Class<*>) {
        val onResume = activityClass.findMethodOrNull("onResume")
        if (onResume == null) {
            module.log(Log.WARN, TAG, "gesture summary refresh skipped, onResume not found")
            return
        }
        module.hookMethod(HOOK_ID_RESUME, onResume) { chain ->
            val result = chain.proceed()
            try {
                val activity = chain.getThisObject() as? Activity
                if (activity != null &&
                    activity.javaClass.name == SecurityCoreHooks.CLASS_GESTURE_SHORTCUT_ACTIVITY
                ) {
                    scheduleSummaryRefresh(module, classLoader, activity)
                }
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "gesture summary refresh schedule failed", t)
            }
            result
        }
    }

    private fun scheduleInjection(module: XposedModule, classLoader: ClassLoader, activity: Activity) {
        val handler = Handler(Looper.getMainLooper())
        var attempt = 0
        val task = object : Runnable {
            override fun run() {
                attempt++
                if (activity.isFinishing || activity.isDestroyed) return
                val done = try {
                    tryInjectRow(module, classLoader, activity)
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "gesture row injection failed", t)
                    true
                }
                if (!done && attempt < MAX_ATTEMPTS) {
                    handler.postDelayed(this, RETRY_DELAY_MS)
                } else if (!done && loggedPageMissing.compareAndSet(false, true)) {
                    module.log(
                        Log.WARN,
                        TAG,
                        "gesture shortcut page fragment not found after $attempt attempts, row not injected",
                    )
                }
            }
        }
        handler.post(task)
    }

    /**
     * 页面 onResume 之后刷新一次 summary（可重试）。
     * 页面 Fragment 的 onResume 会把新行 summary 覆写成「未启用」（p030f2/h.java:58-118 的 o()），
     * 所以这里在 Activity onResume 之后（post）重新按配置写回正确标题。
     */
    private fun scheduleSummaryRefresh(module: XposedModule, classLoader: ClassLoader, activity: Activity) {
        val handler = Handler(Looper.getMainLooper())
        var attempt = 0
        val task = object : Runnable {
            override fun run() {
                attempt++
                if (activity.isFinishing || activity.isDestroyed) return
                val done = try {
                    tryRefreshRowSummary(module, classLoader, activity)
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "gesture row summary refresh failed", t)
                    true
                }
                if (!done && attempt < MAX_ATTEMPTS) {
                    handler.postDelayed(this, RETRY_DELAY_MS)
                }
            }
        }
        handler.post(task)
    }

    /** 返回 true 表示处理结束（成功/确定不可处理），false 表示 Fragment 还没就绪、需要重试。 */
    private fun tryRefreshRowSummary(module: XposedModule, classLoader: ClassLoader, activity: Activity): Boolean {
        val fragment = SecurityCoreHooks.findContentFragment(classLoader, activity) ?: return false
        if (SecurityCoreHooks.findPreference(fragment, SecurityCoreHooks.PREF_KEY_FUNCTION_SHORTCUT) == null) {
            return false
        }
        var row = SecurityCoreHooks.findPreference(fragment, SecurityCoreHooks.ROW_KEY_VOLUME_UP)
        if (row == null) {
            // 行还没注入（例如首次注入失败/时序偏晚），这里补一次注入再刷新
            if (!tryInjectRow(module, classLoader, activity)) return false
            row = SecurityCoreHooks.findPreference(fragment, SecurityCoreHooks.ROW_KEY_VOLUME_UP)
        }
        refreshRow(module, classLoader, fragment, activity)
        return true
    }

    /**
     * 刷新新行：右文本（当前功能标题）+ 防御性重排一次。
     * 页面 Fragment onResume 里会调 o() 重设 advanced_gesture 所有 TextPreference 的右文本，
     * 我们的行 key 不在它的白名单里 → 会被写成「未启用」，所以每次都要覆盖回来。
     */
    private fun refreshRow(
        module: XposedModule,
        classLoader: ClassLoader,
        fragment: Any?,
        context: Context,
    ) {
        val row = SecurityCoreHooks.findPreference(fragment, SecurityCoreHooks.ROW_KEY_VOLUME_UP) ?: return
        updateRowFunctionText(module, classLoader, context, row)
        // adapter 重建按 order 排序；正常不会被冲掉，这里防御性再钉一次（失败只 WARN）
        val category = SecurityCoreHooks.findPreference(fragment, SecurityCoreHooks.PREF_KEY_ADVANCED_GESTURE)
        if (category != null) {
            SecurityCoreHooks.movePreferenceToIndex(module, category, row, ROW_TARGET_INDEX)
        }
    }

    /**
     * 写新行的「功能标题」（与「双击电源键」行完全同源）。
     *
     * 反编译依据：`miuix.preference.TextPreference#h(String)` 是**右侧文本**（TextPreference.java:98-110 存字段
     * f11214j，onBindViewHolder 里 setText 到 R.id.text_right:113-127）；页面 `p030f2.h#o()`
     * （h.java:58-118）给「双击电源键」行设置的就是 `textPreference.h(当前功能标题)`。
     * 所以新行优先调用 `h(String)`（避免出现「右侧未启用 + 第二行功能名」叠加）；
     * 只有行退化到普通 androidx Preference（没有 h 方法）时才用 setSummary。
     */
    private fun updateRowFunctionText(
        module: XposedModule,
        classLoader: ClassLoader,
        context: Context,
        row: Any?,
    ) {
        if (row == null) return
        val rawFunction = SecurityCoreHooks.readSystemString(
            classLoader,
            context,
            SecurityCoreHooks.SETTINGS_KEY_VOLUME_UP,
        )
        val title = SecurityCoreHooks.functionTitle(context, rawFunction)
        val viaRightText = setProperty(row, listOf("h"), title)
        if (!viaRightText) {
            setProperty(row, listOf("setSummary"), title)
        }
        ScReflect.call(row, listOf("notifyChanged"))
        if (loggedSummaryRefreshed.compareAndSet(false, true)) {
            module.log(
                Log.INFO,
                TAG,
                "gesture row text = \"$title\" (raw=$rawFunction, rightText=$viaRightText)",
            )
        }
    }

    /**
     * 页面 Fragment 的 onResume after：刷新右文本 + 重排。
     *
     * Fragment 类名被混淆（jadx 输出 `p030f2.h`，真机真实类名同包系列如 `f2.h`），所以不硬编码类名，
     * 用注入时拿到的真实 Fragment 实例的 `javaClass` 动态安装（只需装一次，后续 onResume 都会走）。
     */
    private fun installFragmentResumeHook(module: XposedModule, fragment: Any) {
        if (fragmentResumeHookInstalled.get()) return
        synchronized(this) {
            if (fragmentResumeHookInstalled.get()) return
            val onResume = fragment.javaClass.findMethodOrNull("onResume")
            if (onResume == null) {
                module.log(Log.WARN, TAG, "gesture fragment onResume hook skipped: method not found")
                return
            }
            module.hookMethod(HOOK_ID_FRAGMENT_ON_RESUME, onResume) { chain ->
                val result = chain.proceed()
                try {
                    val pageFragment = chain.getThisObject()
                    val context = pageFragment?.let {
                        ScReflect.call(it, listOf("getContext")) as? Context
                            ?: ScReflect.call(it, listOf("getActivity")) as? Context
                    }
                    // Fragment 的 classLoader 就是 securitycore 的 app classloader
                    val fragmentClassLoader = pageFragment?.javaClass?.classLoader
                    if (pageFragment != null && context != null && fragmentClassLoader != null &&
                        SecurityCoreHooks.findPreference(pageFragment, SecurityCoreHooks.PREF_KEY_FUNCTION_SHORTCUT) != null
                    ) {
                        refreshRow(module, fragmentClassLoader, pageFragment, context)
                    }
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "gesture row refresh on fragment resume failed", t)
                }
                result
            }
            fragmentResumeHookInstalled.set(true)
            module.log(Log.INFO, TAG, "gesture fragment onResume hook installed on ${fragment.javaClass.name}")
        }
    }

    /** 返回 true 表示注入流程结束（成功 / 确定不可注入），false 表示 Fragment 还没就绪、需要重试。 */
    private fun tryInjectRow(module: XposedModule, classLoader: ClassLoader, activity: Activity): Boolean {
        val fragment = SecurityCoreHooks.findContentFragment(classLoader, activity) ?: return false
        // 用页面 XML 的稳定 key 识别「快捷手势」页 Fragment（不依赖混淆类名）。
        if (SecurityCoreHooks.findPreference(fragment, SecurityCoreHooks.PREF_KEY_FUNCTION_SHORTCUT) == null) {
            return false
        }
        // 幂等：页面重建时已经注入过就不再加。
        if (SecurityCoreHooks.findPreference(fragment, SecurityCoreHooks.ROW_KEY_VOLUME_UP) != null) return true

        val category = SecurityCoreHooks.findPreference(fragment, SecurityCoreHooks.PREF_KEY_ADVANCED_GESTURE)
        if (category == null) {
            if (loggedRowFailure.compareAndSet(false, true)) {
                module.log(Log.WARN, TAG, "advanced_gesture category not found, row not injected")
            }
            return true
        }

        val row = SecurityCoreHooks.createTextPreference(classLoader, activity)
        if (row == null) {
            if (loggedRowFailure.compareAndSet(false, true)) {
                module.log(Log.WARN, TAG, "cannot create preference row (miuix/androidx TextPreference not found)")
            }
            return true
        }

        val title = SecurityCoreHooks.localized("双击音量上键", "Double-tap Volume up")
        val intent = Intent()
            .setClassName(
                SecurityCoreHooks.PKG_SECURITYCORE,
                SecurityCoreHooks.CLASS_DOUBLE_CLICK_POWER_KEY_ACTIVITY,
            )
            .putExtra(SecurityCoreHooks.EXTRA_TARGET_KEY, SecurityCoreHooks.SETTINGS_KEY_VOLUME_UP)

        // androidx.preference.Preference#setKey/setTitle/setIntent 在本 build 保留了方法名
        // （androidx/preference/Preference.java:773/777/850）；任何一个失败都放弃注入，避免出现无法点击的空行。
        val configured = setProperty(row, listOf("setKey"), SecurityCoreHooks.ROW_KEY_VOLUME_UP) &&
            setProperty(row, listOf("setTitle"), title) &&
            setProperty(row, listOf("setIntent"), intent)
        if (!configured) {
            if (loggedRowFailure.compareAndSet(false, true)) {
                module.log(Log.WARN, TAG, "preference row API not found (setKey/setTitle/setIntent), row not injected")
            }
            return true
        }

        if (!SecurityCoreHooks.addPreference(classLoader, category, row)) {
            if (loggedRowFailure.compareAndSet(false, true)) {
                module.log(Log.WARN, TAG, "addPreference failed, row not injected")
            }
            return true
        }

        // 真机反馈：新行要排在「双击电源键」之后（advanced_gesture 索引 1），而不是追加在分类末尾。
        // movePreferenceToIndex 内部会重排所有兄弟行的 order（adapter 重建时按 order 排序）并打日志。
        if (!SecurityCoreHooks.movePreferenceToIndex(module, category, row, ROW_TARGET_INDEX)) {
            module.log(Log.WARN, TAG, "move new row to index $ROW_TARGET_INDEX failed, keep appended position")
        }
        // 右文本 = 当前配置的功能标题（与「双击电源键」行同一显示位）；页面 onResume 之后会再刷新一次。
        updateRowFunctionText(module, classLoader, activity, row)
        // 页面 Fragment onResume 会调 o() 覆写右文本，动态 hook 它的 onResume after 做覆盖。
        installFragmentResumeHook(module, fragment)

        if (loggedInjected.compareAndSet(false, true)) {
            module.log(
                Log.INFO,
                TAG,
                "gesture shortcut row injected: \"$title\" -> ${SecurityCoreHooks.CLASS_DOUBLE_CLICK_POWER_KEY_ACTIVITY} " +
                    "(extra=${SecurityCoreHooks.EXTRA_TARGET_KEY})",
            )
        }
        return true
    }

    private fun setProperty(target: Any, names: List<String>, vararg args: Any?): Boolean {
        val method = ScReflect.findInstanceMethod(target.javaClass, names, args) ?: return false
        return try {
            method.invoke(target, *args)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
