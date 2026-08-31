/*
 * This file is part of HyperDusk.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 HyperDusk Contributors
 */
package com.sevtinge.hyperceiler.libhook.base;

import io.github.libxposed.api.XposedInterface;

/** 仅保留 Hook 运行时所需的共享状态。 */
public final class BaseLoad {
    public static final String SYSTEM_SERVER = "system";
    private static XposedInterface xposed;
    private static PackageTarget target;

    private BaseLoad() {}

    public static void init(XposedInterface interfaceInstance) { xposed = interfaceInstance; }
    public static XposedInterface getXposed() { return xposed; }
    public static void setTarget(PackageTarget value) { target = value; }
    public static PackageTarget getTarget() { return target; }
    public static String getPackageName() { return target == null ? null : target.getPackageName(); }
    public static ClassLoader getClassLoader() { return target == null ? null : target.getClassLoader(); }
    public static boolean isSystemServer() { return target != null && target.isSystemServer(); }

    public static void recordHookInitializationFailure(String source, Throwable error) {
        // 失败已由 BaseHook 记录；保持 fail-closed，不传播到宿主进程。
    }
}
