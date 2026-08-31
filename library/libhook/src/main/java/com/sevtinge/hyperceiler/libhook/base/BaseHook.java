/*
 * This file is part of HyperDusk.
 *
 * HyperDusk is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 HyperDusk Contributors
 */
package com.sevtinge.hyperceiler.libhook.base;

import androidx.annotation.NonNull;

import com.sevtinge.hyperceiler.common.log.XposedLog;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.Hooks;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** HyperDusk Hook 基类。所有规则都必须显式绑定 system_server。 */
public abstract class BaseHook {
    public final String TAG = getClass().getSimpleName();

    public final void onLoad(@NonNull PackageTarget target) {
        try {
            init(target);
        } catch (Throwable t) {
            XposedLog.e(TAG, target.getPackageName(), "Hook 初始化失败，保持系统原行为", t);
            BaseLoad.recordHookInitializationFailure(TAG, t);
        }
    }

    protected abstract void init(@NonNull PackageTarget target) throws Throwable;

    protected final void hookMethod(Class<?> type, String name, Object... parameterTypesAndHook) {
        Hooks.findAndHookMethod(type, name, parameterTypesAndHook);
    }

    protected final IMethodHook before(java.util.function.Consumer<HookParam> consumer) {
        return new IMethodHook() {
            @Override public void before(HookParam param) { consumer.accept(param); }
        };
    }
}
