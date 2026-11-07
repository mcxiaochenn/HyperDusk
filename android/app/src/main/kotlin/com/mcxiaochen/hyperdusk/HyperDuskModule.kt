package com.mcxiaochen.hyperdusk

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * libxposed API 102 模块入口（全限定名必须与 META-INF/xposed/java_init.list 中的一行完全一致）。
 *
 * 生命周期（API 102）：
 *  - onModuleLoaded：模块被载入目标进程时一次（system_server 也会先触发），只在这里做进程过滤；
 *  - onSystemServerStarting：仅 system_server，替代首个包的 onPackageLoaded/onPackageReady，用来装 system_server hook；
 *  - onPackageReady：普通 App 进程（Settings / SystemUI / securitycore）的 classloader 就绪、Application 创建前；
 *  - 未命中的进程直接 detach() 停止后续生命周期回调。
 *
 * 注意：scope.list 是包级作用域，`com.android.settings` 会带出 `:remote` 等子进程，
 * `com.miui.securitycore` 会带出 `:daily_job_service` / `com.miui.securitycore.remote` 等子进程，
 * 所以必须按进程名过滤，只保留主进程（见 [isTargetProcess]）。
 */
class HyperDuskModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (!isTargetProcess(param)) {
            log(Log.INFO, TAG, "loaded in ${param.processName}, not a target process, detached")
            detach()
            return
        }
        log(
            Log.INFO,
            TAG,
            "loaded in ${param.processName} (systemServer=${param.isSystemServer}), " +
                "framework=$frameworkName($frameworkVersionCode) api=$apiVersion",
        )
        // 只读配置桥；框架不支持 remote preferences 时内部降级为默认值，不影响 hook 安装。
        HyperDuskPrefs.attach(this)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        installSafely(PROCESS_SYSTEM_SERVER) { SystemServerHooks.install(this, param.classLoader) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // system_server 内首个包的这两个回调被 onSystemServerStarting 取代，这里只会收到后续加载的包，忽略。
        if (!param.isFirstPackage) return

        when (param.packageName) {
            PKG_SETTINGS -> installSafely(PKG_SETTINGS) {
                AppProcessHooks.installSettings(this, param.classLoader)
            }

            PKG_SYSTEMUI -> installSafely(PKG_SYSTEMUI) {
                AppProcessHooks.installSystemUi(this, param.classLoader)
            }

            PKG_SECURITYCORE -> installSafely(PKG_SECURITYCORE) {
                AppProcessHooks.installSecurityCore(this, param.classLoader)
            }
        }
    }

    /** system_server 的进程名是 "android"，只能靠 isSystemServer 判断；App 进程按包名/进程名判断。 */
    private fun isTargetProcess(param: ModuleLoadedParam): Boolean =
        param.isSystemServer ||
            param.processName == PKG_SETTINGS ||
            param.processName == PKG_SYSTEMUI ||
            param.processName == PKG_SECURITYCORE

    /**
     * hook 安装必须自行兜底：目标类/方法在 HyperOS 不同版本上可能不存在，
     * 模块自己的解析异常不能拖垮目标进程（ExceptionMode 只保护 hooker 运行期异常，不保护安装阶段）。
     */
    private inline fun installSafely(tag: String, block: () -> Unit) {
        try {
            block()
            log(Log.INFO, TAG, "hooks installed: $tag")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hooks install failed: $tag", t)
        }
    }

    companion object {
        const val TAG = "HyperDusk"
        const val PKG_SETTINGS = "com.android.settings"
        const val PKG_SYSTEMUI = "com.android.systemui"
        const val PKG_SECURITYCORE = "com.miui.securitycore"
        const val PROCESS_SYSTEM_SERVER = "system_server"
    }
}
