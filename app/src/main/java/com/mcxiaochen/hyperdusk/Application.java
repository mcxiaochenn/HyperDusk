/*
 * This file is part of HyperDusk.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 HyperDusk Contributors
 */
package com.mcxiaochen.hyperdusk;

import android.content.Context;
import androidx.annotation.NonNull;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** 设置进程只负责偏好读写和 LSPosed 远程偏好同步。 */
public final class Application extends android.app.Application
    implements XposedServiceHelper.OnServiceListener {
    public static volatile boolean moduleActivated;

    @Override
    protected void attachBaseContext(Context base) { super.attachBaseContext(base); }

    @Override
    public void onCreate() {
        super.onCreate();
        PrefsBridge.initForApp(this);
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(@NonNull XposedService service) {
        moduleActivated = true;
        PrefsBridge.setRemotePrefs(service.getRemotePreferences(PrefsBridge.REMOTE_PREFS_GROUP));
    }

    @Override
    public void onServiceDied(@NonNull XposedService service) {
        moduleActivated = false;
        PrefsBridge.setRemotePrefs(null);
    }
}
