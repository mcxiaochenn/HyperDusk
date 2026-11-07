package com.mcxiaochen.hyperdusk

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * securitycore（`com.miui.securitycore`，HyperOS 快捷手势页面宿主，uid=1000）进程的 hook 安装器。
 *
 * 逆向依据：`.research/notes/securitycore-gestures.md`
 *  - 页面宿主 = `com.miui.miinput.gesture.GestureShortcutActivity`（类名未混淆，主进程，§2.1/§7）；
 *  - 「双击电源键」页 = `...gesture.powerkey.DoubleClickPowerKeySettingsActivity` + Fragment `j2.b`（混淆，§4.1）；
 *  - 快捷手势 list 的 Fragment 被混淆为 `p030f2.h`，但页面/分类可以用 preference key 识别（§5.1），
 *    所以 A 项完全不依赖混淆类名；
 *  - 背部轻敲选项数据源 = `MiShortcutFeatureUtil#e(String[], int)`（§3.2/§3.4）。⚠ jadx 把它的包名
 *    deobf 成伪包名 `p030f2`，**真机真实类名是 `f2.t`**（dex 证据：`Lf2/t;`，同样有
 *    `static f(Context)` 返回自身与 `e(String[],int)` 返回 LinkedHashMap）；运行时 `p030f2.t` 不存在。
 *    代码里两类名都保留为候选，真实名优先；
 *  - securitycore 自身所有手势设置都写 `Settings.System` / `MiuiSettings.System`（§6.2），
 *    本进程 hook 里写设置不需要模块 App 自己有权限。
 *
 * 设计原则（任务硬约束）：
 *  - 稳定类名（Activity）+ 运行时 Fragment 发现 作为主锚点；
 *  - 混淆类名（`j2.b` / `f2.t`）只在「结构守卫」通过后使用，守卫 = 父类链 + 方法签名 + 字段/实例内容，
 *    绝不以类名作为唯一依据；守卫失败只降级该功能并打 WARN，页面本身不能崩；
 *  - 每处 hook 独立 try-catch，hook 内部异常统一吞掉（PROTECTIVE 之外的二次保险）。
 */
internal object SecurityCoreHooks {

    private const val TAG = HyperDuskModule.TAG

    /** scope.list 第 4 行；主进程名 == 包名（其余 :xxx / remote 子进程在 HyperDuskModule 里被 detach） */
    const val PKG_SECURITYCORE = "com.miui.securitycore"

    // ---- 稳定锚点（未混淆的 Activity 类名，§2.1 / §4.1） ----
    const val CLASS_GESTURE_SHORTCUT_ACTIVITY = "com.miui.miinput.gesture.GestureShortcutActivity"
    const val CLASS_DOUBLE_CLICK_POWER_KEY_ACTIVITY =
        "com.miui.miinput.gesture.powerkey.DoubleClickPowerKeySettingsActivity"

    // ---- 混淆名候选（只作为查找起点，必须通过 [DoubleClickPowerKeyUiHooks] / [BackTapTorchOptionHook] 的守卫） ----
    const val CANDIDATE_DOUBLE_CLICK_FRAGMENT_CLASS = "j2.b"

    /**
     * `MiShortcutFeatureUtil` 类名候选。
     * 真机 dex 里真实类名是 `f2.t`（`Lf2/t;`）；jadx 输出里的 `p030f2.t` 是 deobf 伪包名，运行时不存在，
     * 仅作为兼容旧报告的兜底候选。解析方 [BackTapTorchOptionHook] 对每个候选都跑同一套结构守卫。
     */
    val CANDIDATE_SHORTCUT_UTIL_CLASSES = listOf("f2.t", "p030f2.t")

    // ---- A/B/C 三项共用的 key / extra / 文案 ----
    /** Settings.System key：必须与 system_server 侧 task-4 的 VolumeUpDoubleClickHook 完全一致 */
    const val SETTINGS_KEY_VOLUME_UP = "hyperdusk_double_click_volume_up"
    /** 快捷手势 list 里新行的 preference key（同 Settings key 即可，便于反查状态） */
    const val ROW_KEY_VOLUME_UP = "hyperdusk_double_click_volume_up"
    /** 复用「双击电源键」页时携带的标记 extra */
    const val EXTRA_TARGET_KEY = "hyperdusk_target_key"
    /** 快捷手势 XML 里的稳定 key（§2.2） */
    const val PREF_KEY_ADVANCED_GESTURE = "advanced_gesture"
    const val PREF_KEY_FUNCTION_SHORTCUT = "function_shortcut"
    const val PREF_KEY_BACK_DOUBLE_TAP = "back_double_tap"
    const val PREF_KEY_BACK_TRIPLE_TAP = "back_triple_tap"

    const val FUNCTION_NONE = "none"
    const val FUNCTION_TURN_ON_TORCH = "turn_on_torch"
    const val FUNCTION_SCREEN_SHOT = "screen_shot"

    // ---- 页面 Fragment / 偏好库的类名（androidx/miuix 的类名在本 build 保留，成员名部分被 R8 混淆） ----
    const val CLASS_PREFERENCE = "androidx.preference.Preference"
    const val CLASS_FRAGMENT = "androidx.fragment.app.Fragment"
    const val CLASS_TEXT_PREFERENCE = "miuix.preference.TextPreference"
    const val CLASS_SINGLE_CHOICE_PREFERENCE = "miuix.preference.SingleChoicePreference"

    /** androidx.preference 页面 Fragment 的基类链（父类守卫用；`androidx.preference.z` = PreferenceFragmentCompat） */
    private val PREFERENCE_FRAGMENT_BASES = setOf(
        "androidx.preference.z",
        "miuix.preference.x",
        "androidx.preference.PreferenceFragmentCompat",
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!HyperDuskPrefs.getBoolean(HyperDuskPrefs.KEY_MODULE_ENABLED, true)) {
            module.log(Log.INFO, TAG, "module disabled by remote preference, skip securitycore hooks")
            return
        }
        val international = isInternationalBuild(classLoader)
        module.log(
            Log.INFO,
            TAG,
            "installing securitycore gesture UI hooks (international=$international)",
        )
        safely(module, "gesture shortcut page") {
            GestureShortcutUiHooks.install(module, classLoader, international)
        }
        safely(module, "double click power key page") {
            DoubleClickPowerKeyUiHooks.install(module, classLoader)
        }
        safely(module, "back tap torch option") {
            BackTapTorchOptionHook.install(module, classLoader)
        }
        module.log(Log.INFO, TAG, "securitycore gesture UI hooks installed")
    }

    /** 单个安装器失败不影响其它安装器（任务要求每处独立兜底）。 */
    private fun safely(module: XposedModule, name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "securitycore hook install failed: $name", t)
        }
    }

    // ---------------------------------------------------------------------
    // 页面/偏好公共工具（反射，避免编译期依赖 androidx/miuix）
    // ---------------------------------------------------------------------

    /**
     * 取 Activity 内容容器（android.R.id.content）里的 support Fragment。
     *
     * Activity 的 `getSupportFragmentManager()` 名字保留，但 androidx FragmentManager 的
     * `findFragmentById(int)` 被 R8 混淆成单字母（反编译：`androidx/fragment/app/FragmentManager.java:1637`
     * `public final Fragment u(int)`），所以按「参数 int、返回 androidx.fragment.app.Fragment」签名定位，
     * 不依赖单字母方法名。页面事务由 `D3.b.q(...)` → `C0036a.i(containerId,...)` 设置 mFragmentId，
     * 容器 id 就是 android.R.id.content（GestureShortcutActivity.java:24 / DoubleClickPowerKeySettingsActivity.java:44）。
     */
    fun findContentFragment(classLoader: ClassLoader, activity: Activity): Any? {
        val manager = ScReflect.call(activity, listOf("getSupportFragmentManager")) ?: return null
        val fragmentClass = classLoader.findClassOrNull(CLASS_FRAGMENT)
        val findMethod = manager.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                (fragmentClass == null || method.returnType == fragmentClass)
        } ?: return null
        return try {
            findMethod.invoke(manager, android.R.id.content)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 在 Fragment（androidx.preference.z，方法名保留）或 PreferenceGroup 上按 key 找 Preference。
     * PreferenceGroup 的 `findPreference` 在本 build 被混淆成 `h(CharSequence)`
     * （反编译：`androidx/preference/PreferenceGroup.java:133`），所以两个名字都试。
     */
    fun findPreference(owner: Any?, key: CharSequence): Any? =
        ScReflect.call(owner, listOf("findPreference"), key)
            ?: ScReflect.call(owner, listOf("h"), key)

    /**
     * 往 PreferenceGroup（PreferenceScreen / PreferenceCategory）里加一个 Preference。
     * `addPreference` 在本 build 被混淆成 `g(Preference)`（`PreferenceGroup.java:80`），两个名字都试。
     */
    fun addPreference(classLoader: ClassLoader, group: Any?, preference: Any?): Boolean {
        if (group == null || preference == null) return false
        val method = ScReflect.findInstanceMethod(
            group.javaClass,
            listOf("addPreference", "g"),
            arrayOf<Any?>(preference),
        ) ?: return false
        return try {
            method.invoke(group, preference)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 新建一行：优先 `miuix.preference.TextPreference(Context)`（与页面其它行同款，§5.3），失败退化 androidx Preference。 */
    fun createTextPreference(classLoader: ClassLoader, context: Context): Any? {
        for (className in arrayOf(CLASS_TEXT_PREFERENCE, CLASS_PREFERENCE)) {
            val clazz = classLoader.findClassOrNull(className) ?: continue
            try {
                return clazz.getConstructor(Context::class.java).newInstance(context)
            } catch (t: Throwable) {
                // 试下一个候选
            }
        }
        return null
    }

    /**
     * 把 PreferenceGroup 里已有的一个 Preference 移动到指定索引（真机反馈：新行要插在「双击电源键」之后）。
     *
     * PreferenceGroup 没有公开的「按索引插入」API（`addPreference` 只按 order 追加/插入），
     * 所以先正常 `addPreference` 完成挂载/排序，再反射拿内部 Preference 列表（`f4509c`，字段名被 R8 混淆，
     * 用「List 且包含目标 Preference」定位）做一次 move，最后 `notifyHierarchyChanged` 让列表刷新。
     */
    @Suppress("UNCHECKED_CAST")
    fun movePreferenceToIndex(group: Any?, preference: Any?, targetIndex: Int): Boolean {
        if (group == null || preference == null || targetIndex < 0) return false
        val internalList = ScReflect.firstFieldValue(group) { _, value ->
            value is List<*> && value.any { it === preference }
        } as? MutableList<Any?> ?: return false
        val currentIndex = internalList.indexOfFirst { it === preference }
        if (currentIndex < 0) return false
        if (currentIndex == targetIndex) {
            notifyPreferenceHierarchyChanged(group)
            return true
        }
        return try {
            val item = internalList.removeAt(currentIndex)
            internalList.add(targetIndex.coerceAtMost(internalList.size), item)
            notifyPreferenceHierarchyChanged(group)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 通知 PreferenceGroup/Preference 刷新列表（两者方法名在本 build 都保留，存在哪个用哪个）。 */
    fun notifyPreferenceHierarchyChanged(target: Any?) {
        ScReflect.call(target, listOf("notifyHierarchyChanged"))
        ScReflect.call(target, listOf("notifyChanged"))
    }

    /** 父类守卫：类属于 androidx.preference 页面 Fragment 家族（androidx.preference.z / miuix.preference.x）。 */
    fun isPreferenceFragment(clazz: Class<*>): Boolean {
        var current: Class<*>? = clazz
        while (current != null) {
            if (PREFERENCE_FRAGMENT_BASES.contains(current.name)) return true
            current = current.superclass
        }
        return false
    }

    /** 中英文文案（没有现成资源可用时）；设备中文占绝大多数。 */
    fun localized(zh: String, en: String): String =
        if (Locale.getDefault().language.startsWith("zh")) zh else en

    // ---- 功能值 → 显示标题（A 项新行 summary） ----

    /** 资源解析失败时的中文兜底（真机只测了中国版）。 */
    private val FUNCTION_TITLE_FALLBACK = mapOf(
        FUNCTION_NONE to "无操作",
        FUNCTION_TURN_ON_TORCH to "手电筒",
        "launch_camera" to "相机",
        "mi_pay" to "小米钱包",
        "launch_smarthome" to "小米一指连",
    )

    /**
     * 把 Settings 里的功能值解析成显示标题（用于「双击音量上键」行 summary）。
     *
     * 取标题的方式与 securitycore 自己一致：按资源名从 securitycore 字符串资源解析
     * （功能值就是资源名；`none` 对应 `key_none`；`mi_pay` 按 NFC 能力对应
     * `mi_pay_summary` / `mi_pay_summary_without_nfc`，与 j2.b 页面标题同源，见报告 §4.3）。
     * 资源解析失败时退中文兜底，再不行直接显示原值；空值按 `none` 处理。
     */
    @Suppress("DEPRECATION")
    fun functionTitle(context: Context, rawFunction: String?): String {
        val function = rawFunction?.trim().orEmpty()
        val normalized = if (function.isEmpty()) FUNCTION_NONE else function
        val resourceNames = when (normalized) {
            FUNCTION_NONE -> listOf("key_none")
            "mi_pay" -> if (hasNfc(context)) {
                listOf("mi_pay_summary", "mi_pay")
            } else {
                listOf("mi_pay_summary_without_nfc", "mi_pay")
            }

            else -> listOf(normalized)
        }
        val resources = context.resources
        for (resourceName in resourceNames) {
            val resourceId = try {
                resources.getIdentifier(resourceName, "string", PKG_SECURITYCORE)
            } catch (t: Throwable) {
                0
            }
            if (resourceId != 0) {
                val title = try {
                    resources.getString(resourceId)
                } catch (t: Throwable) {
                    null
                }
                if (!title.isNullOrEmpty()) return title
            }
        }
        return FUNCTION_TITLE_FALLBACK[normalized] ?: normalized
    }

    private fun hasNfc(context: Context): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
    } catch (t: Throwable) {
        false
    }

    /**
     * 国际版守卫：`DoubleClickPowerKeySettingsActivity#onCreate` 在国际版会直接 finish()
     * （DoubleClickPowerKeySettingsActivity.java:23-26），此时注入新行只会点了就关，所以国际版跳过 A 项。
     */
    fun isInternationalBuild(classLoader: ClassLoader): Boolean {
        return try {
            val buildClass = classLoader.findClassOrNull("miui.os.Build") ?: return false
            buildClass.getField("IS_INTERNATIONAL_BUILD").getBoolean(null)
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------------
    // Settings.System 读写（securitycore 身份是 system uid，写入无需额外权限）
    // ---------------------------------------------------------------------

    /** 读 Settings.System 字符串；优先 hidden 的 `*ForUser(..., -2)`（与 securitycore 自身语义一致），再退化公开 API。 */
    fun readSystemString(classLoader: ClassLoader, context: Context, key: String): String? {
        val resolver = context.contentResolver
        val miuiSystem = classLoader.findClassOrNull("android.provider.MiuiSettings\$System")
        if (miuiSystem != null) {
            val method = ScReflect.findStaticMethod(
                miuiSystem,
                listOf("getStringForUser"),
                arrayOf<Any?>(resolver, key, -2),
            )
            if (method != null) {
                val value = try {
                    method.invoke(null, resolver, key, -2)
                } catch (t: Throwable) {
                    null
                }
                if (value is String) return value
            }
        }
        val aospSystem = classLoader.findClassOrNull("android.provider.Settings\$System")
        if (aospSystem != null) {
            val method = ScReflect.findStaticMethod(
                aospSystem,
                listOf("getStringForUser"),
                arrayOf<Any?>(resolver, key, -2),
            )
            if (method != null) {
                val value = try {
                    method.invoke(null, resolver, key, -2)
                } catch (t: Throwable) {
                    null
                }
                if (value is String) return value
            }
        }
        return try {
            Settings.System.getString(resolver, key)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 写 Settings.System 字符串。
     * securitycore 自己的写入口是 `MiuiSettings.System.putStringForUser(cr,key,value,-2)`
     * （反编译：j2/b.java:289 / p042h2/c.java:84），这里同样优先它；失败再退 AOSP hidden API / 公开 API。
     */
    fun writeSystemString(classLoader: ClassLoader, context: Context, key: String, value: String): Boolean {
        val resolver = context.contentResolver
        val miuiSystem = classLoader.findClassOrNull("android.provider.MiuiSettings\$System")
        if (miuiSystem != null) {
            val method = ScReflect.findStaticMethod(
                miuiSystem,
                listOf("putStringForUser"),
                arrayOf<Any?>(resolver, key, value, -2),
            )
            if (method != null) {
                try {
                    method.invoke(null, resolver, key, value, -2)
                    return true
                } catch (t: Throwable) {
                    // 退化到下面的通道
                }
            }
        }
        val aospSystem = classLoader.findClassOrNull("android.provider.Settings\$System")
        if (aospSystem != null) {
            val method = ScReflect.findStaticMethod(
                aospSystem,
                listOf("putStringForUser"),
                arrayOf<Any?>(resolver, key, value, -2),
            )
            if (method != null) {
                try {
                    method.invoke(null, resolver, key, value, -2)
                    return true
                } catch (t: Throwable) {
                    // 退化到下面的通道
                }
            }
        }
        return try {
            Settings.System.putString(resolver, key, value)
        } catch (t: Throwable) {
            false
        }
    }
}

/**
 * 反射调用工具：securitycore 里的 androidx/miuix 库部分成员名（以及若干 App 类）被 R8 混淆，
 * 统一用「名字候选 + 签名/参数类型匹配」定位，并兼容继承来的方法。
 */
internal object ScReflect {

    fun call(target: Any?, names: List<String>, vararg args: Any?): Any? {
        val instance = target ?: return null
        val method = findInstanceMethod(instance.javaClass, names, args) ?: return null
        return method.invoke(instance, *args)
    }

    fun callStatic(clazz: Class<*>?, names: List<String>, vararg args: Any?): Any? {
        val target = clazz ?: return null
        val method = findStaticMethod(target, names, args) ?: return null
        return method.invoke(null, *args)
    }

    fun findInstanceMethod(clazz: Class<*>, names: List<String>, args: Array<out Any?>): Method? =
        findMethod(clazz, names, args, static = false)

    fun findStaticMethod(clazz: Class<*>, names: List<String>, args: Array<out Any?>): Method? =
        findMethod(clazz, names, args, static = true)

    private fun findMethod(
        clazz: Class<*>,
        names: List<String>,
        args: Array<out Any?>,
        static: Boolean,
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            for (name in names) {
                val match = current.declaredMethods.firstOrNull { method ->
                    method.name == name &&
                        Modifier.isStatic(method.modifiers) == static &&
                        matches(method, args)
                }
                if (match != null) {
                    try {
                        match.isAccessible = true
                    } catch (t: Throwable) {
                        // 记录：个别方法不允许 setAccessible 时继续尝试 invoke（public 方法通常也能成功）
                    }
                    return match
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun matches(method: Method, args: Array<out Any?>): Boolean {
        if (method.parameterCount != args.size) return false
        val types = method.parameterTypes
        for (index in args.indices) {
            if (!argumentMatches(types[index], args[index])) return false
        }
        return true
    }

    private fun argumentMatches(type: Class<*>, arg: Any?): Boolean = when {
        arg == null -> !type.isPrimitive
        type.isPrimitive -> when (type) {
            java.lang.Boolean.TYPE -> arg is Boolean
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Long.TYPE -> arg is Long
            java.lang.Float.TYPE -> arg is Float
            java.lang.Double.TYPE -> arg is Double
            java.lang.Short.TYPE -> arg is Short
            java.lang.Byte.TYPE -> arg is Byte
            java.lang.Character.TYPE -> arg is Char
            else -> false
        }
        else -> type.isInstance(arg)
    }

    /** 按字段名取值（本类找不到再沿父类链找）；字段名混淆时可配合 [firstFieldValue]。 */
    fun fieldValue(target: Any?, name: String): Any? {
        val instance = target ?: return null
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            val field = try {
                current.getDeclaredField(name).apply { isAccessible = true }
            } catch (t: Throwable) {
                null
            }
            if (field != null) {
                return try {
                    field.get(instance)
                } catch (t: Throwable) {
                    null
                }
            }
            current = current.superclass
        }
        return null
    }

    /** 找第一个满足条件的实例字段值（用于混淆字段名兜底，例如 j2.b 的 SingleChoicePreference 列表）。 */
    fun firstFieldValue(target: Any?, predicate: (Field, Any?) -> Boolean): Any? {
        val instance = target ?: return null
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val value = try {
                    field.isAccessible = true
                    field.get(instance)
                } catch (t: Throwable) {
                    continue
                }
                if (predicate(field, value)) return value
            }
            current = current.superclass
        }
        return null
    }
}
