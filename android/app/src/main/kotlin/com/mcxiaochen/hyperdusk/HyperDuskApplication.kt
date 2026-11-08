package com.mcxiaochen.hyperdusk

import android.app.Application
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicReference

/**
 * 模块 App 进程的 Application：持有框架服务句柄，给 Flutter 桥提供状态读取与开关写入
 * （契约见 .research/notes/ui-redesign.md §2）。
 *
 * 注意：模块 App 自身不会被注入（API 101 起的行为），所以这里只能走
 * [XposedService.getRemotePreferences]（可写）；[HyperDuskPrefs] 那套 `XposedModule` API
 * 只在被 hook 进程里可用，这里只复用它的 `const val`（编译期内联，不会触发类加载）。
 */
class HyperDuskApplication : Application(), XposedServiceHelper.OnServiceListener {
    private val service = AtomicReference<XposedService?>(null)

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(boundService: XposedService) {
        service.set(boundService)
    }

    override fun onServiceDied(deadService: XposedService) {
        service.compareAndSet(deadService, null)
    }

    fun xposedStatus(): Map<String, Any?> {
        val activeService = service.get()
            ?: return mapOf(
                "connected" to false,
                "message" to "未连接到兼容的 Xposed 框架。",
            )

        return try {
            mapOf(
                "connected" to true,
                "message" to "框架服务已连接。",
                "frameworkName" to activeService.frameworkName,
                "frameworkVersion" to activeService.frameworkVersion,
                "apiVersion" to activeService.apiVersion,
            )
        } catch (error: RuntimeException) {
            mapOf(
                "connected" to false,
                "message" to (error.message ?: "读取框架状态失败。"),
            )
        }
    }

    /**
     * 模块状态（§2 `getModuleState`）：框架相关的开关读 RemotePreferences，其余读 Settings.System。
     *
     * 为什么每条都单独兜底而不是整体 try：设置值在框架未连接时也要能显示（§3.2），
     * 所以框架不可用只能让 `available=false`，不能连带把设置值一起丢掉。
     * 任何一项读取失败都降级成 false / null，**不允许抛回 Flutter**。
     */
    fun moduleState(): Map<String, Any?> {
        val prefs = remotePreferences()
        return mapOf<String, Any?>(
            "available" to (prefs != null),
            "enabled" to readModuleEnabled(prefs),
            "doubleClickVolumeUp" to readSystemSetting(KEY_DOUBLE_CLICK_VOLUME_UP),
            "backDoubleTap" to readSystemSetting(KEY_BACK_DOUBLE_TAP),
            "backTripleTap" to readSystemSetting(KEY_BACK_TRIPLE_TAP),
            "scopes" to MODULE_SCOPES,
        )
    }

    /**
     * 写总开关（§2 `setModuleEnabled`），返回框架是否真的写入成功。
     *
     * 为什么用 `commit()` 而不是 `apply()`：`RemotePreferences.Editor.apply()` 把 binder 调用丢给
     * 后台线程、且吞掉 RemoteException（只打日志），返回值只能反映「本地 map 改了」，
     * 框架没连上时也会返回成功，UI 会显示假的开关状态。`commit()` 是同步 binder 调用，
     * 能拿到真实结果；开销与 [xposedStatus] 里已有的框架调用同级。
     */
    fun setModuleEnabled(enabled: Boolean): Boolean {
        val prefs = remotePreferences() ?: return false
        return try {
            prefs.edit().putBoolean(KEY_MODULE_ENABLED, enabled).commit()
        } catch (t: Throwable) {
            Log.w(TAG, "write $KEY_MODULE_ENABLED=$enabled failed", t)
            false
        }
    }

    /**
     * 取框架侧的 RemotePreferences（模块 App 进程可写）。
     *
     * 失败来源：服务未连接（null）、服务已死（[XposedService.ServiceException]，RuntimeException）、
     * 框架不支持 remote 能力（UnsupportedOperationException，embedded 框架）。
     * 全部降级成 null，调用方据此把 `available` 置 false。
     */
    private fun remotePreferences(): SharedPreferences? {
        val activeService = service.get() ?: return null
        return try {
            activeService.getRemotePreferences(HyperDuskPrefs.GROUP)
        } catch (t: Throwable) {
            Log.w(TAG, "getRemotePreferences(${HyperDuskPrefs.GROUP}) failed", t)
            null
        }
    }

    /**
     * 总开关默认 true（§2 字段语义 + 被 hook 进程侧 `getBoolean(KEY, true)` 同源）。
     *
     * 读不到时也保持 true：可读性由 `available` 表达（UI 据此禁用开关），
     * 若这里降级成 false，会出现「UI 显示已关闭、system_server 侧却按开启执行」的矛盾状态。
     */
    private fun readModuleEnabled(prefs: SharedPreferences?): Boolean = try {
        prefs?.getBoolean(KEY_MODULE_ENABLED, true) ?: true
    } catch (t: Throwable) {
        true
    }

    /** Settings.System 读不需要框架（system_server 直接可见），未设置时返回 null。 */
    private fun readSystemSetting(key: String): String? = try {
        Settings.System.getString(contentResolver, key)
    } catch (t: Throwable) {
        null
    }

    private companion object {
        const val TAG = "HyperDusk"

        /**
         * group 名与总开关 key 复用 [HyperDuskPrefs] 的常量，避免两端拼写漂移。
         *
         * 这里能直接引用是因为它们都是 `const val`（编译期内联成字面量）：[HyperDuskPrefs] 引用了
         * compileOnly 的 `XposedModule`，那个类只在被 hook 进程里存在，App 进程不能真的加载它。
         */
        const val KEY_MODULE_ENABLED = HyperDuskPrefs.KEY_MODULE_ENABLED
        const val GROUP = HyperDuskPrefs.GROUP

        /** 与 VolumeUpDoubleClickHook 的 SETTINGS_KEY 同源（Settings.System）。 */
        const val KEY_DOUBLE_CLICK_VOLUME_UP = "hyperdusk_double_click_volume_up"

        /** MIUI「背部轻敲」配置项（securitycore 背部轻敲页读写的就是这两个 key）。 */
        const val KEY_BACK_DOUBLE_TAP = "back_double_tap"
        const val KEY_BACK_TRIPLE_TAP = "back_triple_tap"

        /** §2：作用域固定四行，顺序即 UI 展示顺序（与 META-INF/xposed/scope.list 一致）。 */
        val MODULE_SCOPES = listOf(
            "system",
            "com.android.settings",
            "com.android.systemui",
            "com.miui.securitycore",
        )
    }
}
