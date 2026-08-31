/*
 * This file is part of HyperDusk.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 HyperDusk Contributors
 */
package com.sevtinge.hyperceiler.libhook.base;

import android.content.pm.ApplicationInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/** Xposed 目标进程的最小描述。 */
public final class PackageTarget {
    private final String packageName;
    @Nullable private final ApplicationInfo applicationInfo;
    private final ClassLoader classLoader;
    private final boolean systemServer;

    private PackageTarget(String packageName, ApplicationInfo applicationInfo,
                          ClassLoader classLoader, boolean systemServer) {
        this.packageName = packageName;
        this.applicationInfo = applicationInfo;
        this.classLoader = classLoader;
        this.systemServer = systemServer;
    }

    public static PackageTarget ofSystemServer(@NonNull SystemServerStartingParam param) {
        return new PackageTarget(BaseLoad.SYSTEM_SERVER, null, param.getClassLoader(), true);
    }

    public static PackageTarget ofPackageReady(@NonNull PackageReadyParam param) {
        return new PackageTarget(param.getPackageName(), param.getApplicationInfo(),
            param.getClassLoader(), false);
    }

    @NonNull public String getPackageName() { return packageName; }
    @Nullable public ApplicationInfo getApplicationInfo() { return applicationInfo; }
    @NonNull public ClassLoader getClassLoader() { return classLoader; }
    public boolean isSystemServer() { return systemServer; }
}
