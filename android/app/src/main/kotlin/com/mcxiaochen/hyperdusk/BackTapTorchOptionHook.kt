package com.mcxiaochen.hyperdusk

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 需求 C：给「背部轻敲」页的双击/三击下拉列表加「手电筒（turn_on_torch）」选项。
 *
 * 逆向依据（.research/notes/securitycore-gestures.md §3）：
 *  - 背部轻敲页 = `com.miui.miinput.gesture.backtap.BackTapSettingsActivity` + Fragment `h2.c`（jadx 输出里的
 *    `p042h2.c` 是 deobf 伪包名，§3.1）；
 *  - 两个下拉的选项唯一数据源 = `MiShortcutFeatureUtil#e(String[] actions, int orderType)`（§3.2/§3.4）：
 *    它把「功能 → (类型 → action 列表)」里命中查询 action 的功能收进 `LinkedHashMap<功能值, 标题>`；
 *    背部轻敲页用 `e(new String[]{"back_double_tap"}, 2)` / `e(new String[]{"back_triple_tap"}, 2)`（c.java:108-109）；
 *  - 当前系统配置里 `turn_on_torch` 只绑定了 `double_click_power_key`，没有绑定 back 系列，
 *    所以它不在返回 map 里 → 下拉里没有手电筒（§3.3）；
 *  - 顺序：`g(2)` 的固定顺序表里 `turn_on_torch` 排在 `screen_shot` 之后（§3.2），
 *    所以这里也插入到 `screen_shot` 之后，与「Settings 旧实现」的顺序一致。
 *
 * 只改 UI 不够：system_server 的 `MiuiBackTapGestureService#removeUnsupportedFunction()` 会把
 * back 手势上的 `turn_on_torch` 强制清回 none（system-gestures.md §1.3），该问题已由 task-4
 * 的 `SystemServerHooks.installBackTapTorchHook` 解决；这里只负责让用户能选到。
 *
 * 类名（真机修复）：jadx 输出里的 `p030f2.t` 是 deobf 伪包名，运行时不存在；真机 dex 里真实类名是
 * **`f2.t`**（`Lf2/t;`）。候选顺序 = [f2.t, p030f2.t]（后者仅兼容旧报告），每个候选都要通过同一套结构守卫：
 *  1) 存在 `public static t f(Context)` 单例工厂且返回类型就是本类（反编译 p030f2/t.java:94）；
 *  2) 存在实例方法 `e(String[], int)` 且返回 Map（p030f2/t.java:309，本类里该签名唯一）；
 *  3) 类里有非静态的 Context/Object 状态字段（对应字段 f7069a，p030f2/t.java:70/820-824）。
 * 任一守卫失败：只打 WARN，跳过本功能，不影响其它 hook。
 */
internal object BackTapTorchOptionHook {

    private const val TAG = HyperDuskModule.TAG
    private const val HOOK_ID = "securitycore:MiShortcutFeatureUtil#getFunctionAndValuesMap"

    private val loggedInjected = AtomicBoolean(false)

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val utilClass = resolveShortcutUtilClass(classLoader)
        if (utilClass == null) {
            module.log(
                Log.WARN,
                TAG,
                "back tap torch option skipped: none of " +
                    "${SecurityCoreHooks.CANDIDATE_SHORTCUT_UTIL_CLASSES} found or structural guard failed",
            )
            return
        }
        val method = findFunctionMapMethod(utilClass)
        if (method == null) {
            module.log(
                Log.WARN,
                TAG,
                "back tap torch option skipped: e(String[], int) not found on ${utilClass.name}",
            )
            return
        }
        module.hookMethod(HOOK_ID, method) { chain ->
            val result = chain.proceed()
            try {
                if (isBackTapQuery(chain.getArg(0))) {
                    augmentTorchOption(module, chain.getThisObject(), result) ?: result
                } else {
                    result
                }
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "back tap torch option injection failed", t)
                result
            }
        }
        module.log(Log.INFO, TAG, "back tap torch option hook installed on ${utilClass.name}")
    }

    /** 只处理背部轻敲页的查询（c.java:108-109 的两次调用）。 */
    private fun isBackTapQuery(query: Any?): Boolean {
        val array = query as? Array<*> ?: return false
        return array.any {
            it == SecurityCoreHooks.PREF_KEY_BACK_DOUBLE_TAP || it == SecurityCoreHooks.PREF_KEY_BACK_TRIPLE_TAP
        }
    }

    /**
     * 把 turn_on_torch 插进 `LinkedHashMap<功能值, 标题>`：位置在 `screen_shot` 之后（§3.2 的顺序表），
     * 已经存在（系统配置更新后原生支持）则不改；返回 null 表示无需修改。
     */
    private fun augmentTorchOption(module: XposedModule, utilInstance: Any?, result: Any?): Any? {
        val map = result as? LinkedHashMap<*, *> ?: return null
        if (map.containsKey(SecurityCoreHooks.FUNCTION_TURN_ON_TORCH)) return null
        val title = resolveTorchTitle(utilInstance)
        val merged = LinkedHashMap<Any?, Any?>(map.size + 1)
        var inserted = false
        for ((key, value) in map) {
            merged[key] = value
            if (!inserted && key == SecurityCoreHooks.FUNCTION_SCREEN_SHOT) {
                merged[SecurityCoreHooks.FUNCTION_TURN_ON_TORCH] = title
                inserted = true
            }
        }
        if (!inserted) {
            merged[SecurityCoreHooks.FUNCTION_TURN_ON_TORCH] = title
        }
        if (loggedInjected.compareAndSet(false, true)) {
            module.log(
                Log.INFO,
                TAG,
                "back tap list augmented: inserted ${SecurityCoreHooks.FUNCTION_TURN_ON_TORCH} " +
                    "(title=\"$title\", after=${SecurityCoreHooks.FUNCTION_SCREEN_SHOT})",
            )
        }
        return merged
    }

    /**
     * 标题与页面其它项同源：`t.e` 里用 `resources.getString(R.string.turn_on_torch)`（t.java:430-432），
     * 这里按资源名取同一份字符串；取不到时退中英文硬编码。
     */
    private fun resolveTorchTitle(utilInstance: Any?): String {
        val context = findContext(utilInstance)
        if (context != null) {
            try {
                val resources = context.resources
                val id = resources.getIdentifier(
                    SecurityCoreHooks.FUNCTION_TURN_ON_TORCH,
                    "string",
                    context.packageName,
                )
                if (id != 0) return resources.getString(id)
            } catch (t: Throwable) {
                // 退化到硬编码文案
            }
        }
        return SecurityCoreHooks.localized("手电筒", "Flashlight")
    }

    /** t 实例的 Context 字段（p030f2/t.java:820-824，字段名混淆时按「值是 Context」兜底）。 */
    private fun findContext(utilInstance: Any?): Context? {
        if (utilInstance == null) return null
        val known = ScReflect.fieldValue(utilInstance, "f7069a") as? Context
        if (known != null) return known
        return ScReflect.firstFieldValue(utilInstance) { _, value -> value is Context } as? Context
    }

    // ---------------------------------------------------------------
    // 结构守卫
    // ---------------------------------------------------------------

    /**
     * 按候选类名逐个尝试 [SecurityCoreHooks.CANDIDATE_SHORTCUT_UTIL_CLASSES]，每个候选跑同一套结构守卫。
     * 真机真实名 `f2.t` 排第一；`p030f2.t`（jadx deobf 伪名）仅作兼容兜底。
     */
    private fun resolveShortcutUtilClass(classLoader: ClassLoader): Class<*>? {
        for (className in SecurityCoreHooks.CANDIDATE_SHORTCUT_UTIL_CLASSES) {
            val clazz = classLoader.findClassOrNull(className) ?: continue
            if (passesStructuralGuards(clazz)) return clazz
        }
        return null
    }

    private fun passesStructuralGuards(clazz: Class<*>): Boolean {
        // 守卫 1：静态单例工厂 f(Context) 返回自身（p030f2/t.java:94）
        val hasFactory = clazz.declaredMethods.any { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "f" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Context::class.java &&
                method.returnType == clazz
        }
        if (!hasFactory) return false
        // 守卫 2：e(String[], int) → Map（p030f2/t.java:309；本类里该签名唯一）
        if (findFunctionMapMethod(clazz) == null) return false
        // 守卫 3：确实带 Context 状态的字段（p030f2/t.java:70/815-824）
        return clazz.declaredFields.any { field ->
            !Modifier.isStatic(field.modifiers) &&
                (field.type == Context::class.java || field.type == Any::class.java)
        }
    }

    private fun findFunctionMapMethod(clazz: Class<*>): Method? {
        val byName = clazz.declaredMethods.firstOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == "e" &&
                matchesFunctionMapSignature(method)
        }
        if (byName != null) return byName
        // 名字再次混淆时按签名兜底（本类里 void/其它返回值的 (String[],int) 方法不存在）
        return clazz.declaredMethods.firstOrNull { method ->
            !Modifier.isStatic(method.modifiers) && matchesFunctionMapSignature(method)
        }
    }

    private fun matchesFunctionMapSignature(method: Method): Boolean =
        method.parameterCount == 2 &&
            method.parameterTypes[0] == Array<String>::class.java &&
            method.parameterTypes[1] == Int::class.javaPrimitiveType &&
            Map::class.java.isAssignableFrom(method.returnType)
}
