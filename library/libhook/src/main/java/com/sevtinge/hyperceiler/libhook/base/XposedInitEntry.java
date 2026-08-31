/*
 * This file is part of HyperDusk.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 HyperDusk Contributors
 */
package com.sevtinge.hyperceiler.libhook.base;

import androidx.annotation.NonNull;

import com.sevtinge.hyperceiler.common.log.LogStatusManager;
import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.rules.systemframework.VolumeUpTorch;

import io.github.libxposed.api.XposedModule;

/** libxposed API 102 入口；只向 system_server 注入 MVP 规则。 */
public final class XposedInitEntry extends XposedModule {
    private static final String TAG = "HyperDusk";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        PrefsBridge.initForHook(getRemotePreferences(PrefsBridge.REMOTE_PREFS_GROUP));
        LogStatusManager.syncLogLevelFromPrefs();
        BaseLoad.init(this);
        XposedLog.i(TAG, param.getProcessName(), "HyperDusk API 102 module loaded");
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        PackageTarget target = PackageTarget.ofSystemServer(param);
        BaseLoad.setTarget(target);
        new VolumeUpTorch().onLoad(target);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        // 作用域严格限定为 system；其它进程不安装任何 Hook。
    }
}
