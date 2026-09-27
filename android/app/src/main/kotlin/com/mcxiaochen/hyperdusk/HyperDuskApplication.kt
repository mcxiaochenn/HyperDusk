package com.mcxiaochen.hyperdusk

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicReference

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
}
