package com.mcxiaochen.hyperdusk

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 普通 App 进程的 hook 安装器。
 *
 * - com.android.settings / com.android.systemui：本版本还没有业务 UI hook（scope 已声明为注入目标），
 *   这里装一个 Application#onCreate 冒烟 hook：每进程只打一条日志，证明模块注入成功、给后续任务预留结构。
 * - com.miui.securitycore：「快捷手势」页面宿主（securitycore 主进程，uid=1000），业务 hook 见
 *   [SecurityCoreHooks]（快捷手势 list 注入 + 双击电源键页复用 + 背部轻敲加手电筒）。
 */
internal object AppProcessHooks {

    private const val TAG = HyperDuskModule.TAG
    private const val CLASS_APPLICATION = "android.app.Application"

    fun installSettings(module: XposedModule, classLoader: ClassLoader) {
        installInjectionSmokeHook(module, classLoader, "settings")
    }

    fun installSystemUi(module: XposedModule, classLoader: ClassLoader) {
        installInjectionSmokeHook(module, classLoader, "systemui")
    }

    /** securitycore 主进程：装快捷手势页面的 UI 注入 hook（没有冒烟 hook，安装器自己打加载日志）。 */
    fun installSecurityCore(module: XposedModule, classLoader: ClassLoader) {
        SecurityCoreHooks.install(module, classLoader)
    }

    private fun installInjectionSmokeHook(module: XposedModule, classLoader: ClassLoader, processTag: String) {
        val target = classLoader.findClassOrNull(CLASS_APPLICATION)?.findMethodOrNull("onCreate")
        if (target == null) {
            module.log(Log.WARN, TAG, "injection smoke hook skipped: $CLASS_APPLICATION#onCreate not found")
            return
        }
        val logged = AtomicBoolean(false)
        module.hookMethod("$processTag:$CLASS_APPLICATION#onCreate", target) { chain ->
            if (logged.compareAndSet(false, true)) {
                module.log(Log.INFO, TAG, "$processTag injected, module is active in this process")
            }
            chain.proceed()
        }
    }
}
