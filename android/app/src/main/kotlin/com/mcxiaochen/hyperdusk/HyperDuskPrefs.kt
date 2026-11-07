package com.mcxiaochen.hyperdusk

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * 被 hook 进程内的 RemotePreferences 只读桥。
 *
 * - 被 hook 进程：`XposedModule.getRemotePreferences(group)` 返回框架维护的 SharedPreferences，只读；
 *   模块 App 侧通过 `XposedService.getRemotePreferences(group)` 写入后会同步过来（API 102）。
 * - group 名两端必须一致（这里是 "hyperdusk"）。
 * - 框架不支持 remote 能力（embedded framework）时抛 UnsupportedOperationException，这里降级为默认值。
 *
 * 本版本只在 hook 安装阶段读取一次 `module.enabled` 作为总开关（默认开启），
 * 后续 UI（下一个任务）写入配置后无需重启进程即可通过监听器扩展。
 */
internal object HyperDuskPrefs {

    /** 模块 App 与所有被 hook 进程共用的 group 名 */
    const val GROUP = "hyperdusk"

    /** 总开关：false 时三个进程都不装 hook */
    const val KEY_MODULE_ENABLED = "module.enabled"

    private const val TAG = HyperDuskModule.TAG

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 在 onModuleLoaded 里调用一次；同一进程重复调用直接返回。 */
    fun attach(module: XposedModule) {
        if (prefs != null) return
        prefs = try {
            module.getRemotePreferences(GROUP)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "remote preferences unavailable, fall back to defaults", t)
            null
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs?.getBoolean(key, defaultValue) ?: defaultValue

    fun getString(key: String, defaultValue: String?): String? =
        prefs?.getString(key, defaultValue) ?: defaultValue
}
