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
 */
internal object GestureShortcutUiHooks {

    private const val TAG = HyperDuskModule.TAG
    private const val HOOK_ID = "securitycore:GestureShortcutActivity#onCreate"
    private const val HOOK_ID_RESUME = "securitycore:GestureShortcutActivity#onResume"

    /** 新行插在 advanced_gesture 分类索引 1：双击电源键(index 0) 之后、敲击/背部轻敲之前。 */
    private const val ROW_TARGET_INDEX = 1

    /** Fragment 事务是异步 commit，onCreate after 时 Fragment 还不存在；post + 少量重试（§5.1）。 */
    private const val MAX_ATTEMPTS = 25
    private const val RETRY_DELAY_MS = 16L

    private val loggedInjected = AtomicBoolean(false)
    private val loggedPageMissing = AtomicBoolean(false)
    private val loggedRowFailure = AtomicBoolean(false)
    private val loggedSummaryRefreshed = AtomicBoolean(false)

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
        updateRowSummary(module, classLoader, activity, row)
        return true
    }

    /** 按当前 Settings 配置给新行写 summary（功能标题；未配置显示“无操作”），并通知列表刷新。 */
    private fun updateRowSummary(
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
        val summary = SecurityCoreHooks.functionTitle(context, rawFunction)
        if (!setProperty(row, listOf("setSummary"), summary)) return
        ScReflect.call(row, listOf("notifyChanged"))
        if (loggedSummaryRefreshed.compareAndSet(false, true)) {
            module.log(Log.INFO, TAG, "gesture row summary = \"$summary\" (raw=$rawFunction)")
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
        if (!SecurityCoreHooks.movePreferenceToIndex(category, row, ROW_TARGET_INDEX)) {
            module.log(Log.WARN, TAG, "move new row to index $ROW_TARGET_INDEX failed, keep appended position")
        }
        // summary 显示当前配置的功能标题（页面 onResume 之后还会再刷新一次）。
        updateRowSummary(module, classLoader, activity, row)

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
