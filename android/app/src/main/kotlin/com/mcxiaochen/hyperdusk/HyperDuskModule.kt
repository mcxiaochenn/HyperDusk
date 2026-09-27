package com.mcxiaochen.hyperdusk

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class HyperDuskModule : XposedModule() {
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Log.i(TAG, "HyperDusk module entry loaded in ${param.processName}; no hooks installed")
    }

    private companion object {
        const val TAG = "HyperDusk"
    }
}
