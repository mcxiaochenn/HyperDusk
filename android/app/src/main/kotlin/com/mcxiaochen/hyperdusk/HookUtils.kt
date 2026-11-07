package com.mcxiaochen.hyperdusk

import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 反射 + hook 的统一工具。
 *
 * 约定（来自 .research/notes/libxposed-api.md 第 4 节）：
 *  - 目标类必须用生命周期参数里的 ClassLoader + `Class.forName(name, false, loader)` 解析，
 *    不要用模块自己的默认 ClassLoader；
 *  - 类/方法不存在时只记日志并返回 null，绝不抛出（HyperOS 各版本差异大）；
 *  - 安装阶段抛出的异常不会被 ExceptionMode.PROTECTIVE 保护，必须自己 try-catch；
 *  - 热路径 hook 统一使用 PROTECTIVE，避免模块异常打崩目标进程。
 */

/** system_server / App 进程的 ClassLoader 里按全名找类；找不到返回 null。 */
internal fun ClassLoader.findClassOrNull(className: String): Class<*>? = try {
    Class.forName(className, false, this)
} catch (t: Throwable) {
    null
}

/** 查找方法（先 getDeclaredMethod，再 getMethod 以兼容继承的 public 方法）；找不到返回 null。 */
internal fun Class<*>.findMethodOrNull(methodName: String, parameterTypes: Array<Class<*>> = emptyArray()): Method? {
    try {
        return getDeclaredMethod(methodName, *parameterTypes)
    } catch (_: Throwable) {
        // fall through
    }
    return try {
        getMethod(methodName, *parameterTypes)
    } catch (t: Throwable) {
        null
    }
}

/** 查找字段并开启访问（目标类都是 system_server 内部类，字段普遍是 private）。 */
internal fun Class<*>.findFieldOrNull(fieldName: String): Field? = try {
    getDeclaredField(fieldName).apply { isAccessible = true }
} catch (t: Throwable) {
    null
}

/**
 * 安装 PROTECTIVE hook。
 *
 * @param hookId API 102 的 hook 唯一 id；同 id 再次 hook 会原子替换旧 hook。
 */
internal fun XposedModule.hookMethod(
    hookId: String,
    method: Method,
    hooker: Hooker,
): HookHandle? = try {
    hook(method)
        .setId(hookId)
        .setExceptionMode(ExceptionMode.PROTECTIVE)
        .intercept(hooker)
        .also { log(Log.INFO, HyperDuskModule.TAG, "hooked ${method.declaringClass.name}#${method.name} ($hookId)") }
} catch (t: Throwable) {
    log(Log.ERROR, HyperDuskModule.TAG, "hook failed: $hookId", t)
    null
}

/**
 * 目标方法可能被 AOT/JIT 内联进调用方，导致 hook 不回调；deoptimize 调用方让运行时退回不内联调用。
 * 只在确认目标方法是短方法时对已知调用方使用（代价是性能）。
 */
internal fun XposedModule.tryDeoptimize(method: Method?, reason: String) {
    if (method == null) return
    try {
        val deoptimized = deoptimize(method)
        log(
            if (deoptimized) Log.INFO else Log.WARN,
            HyperDuskModule.TAG,
            "deoptimize ${method.declaringClass.name}#${method.name} = $deoptimized ($reason)",
        )
    } catch (t: Throwable) {
        log(Log.WARN, HyperDuskModule.TAG, "deoptimize failed: ${method.name} ($reason)", t)
    }
}
