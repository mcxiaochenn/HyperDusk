/*
 * This file is part of HyperDusk.
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 HyperDusk Contributors
 */
package com.sevtinge.hyperceiler.libhook.rules.systemframework;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.base.PackageTarget;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

/**
 * 在 HyperOS 4 原生单键规则管理器中复制音量减规则到音量加，复用系统调度。
 * 任何类/签名变化均记录并停止安装，不拦截全局按键。
 */
public final class VolumeUpTorch extends BaseHook {
    public static final String PREF_KEY = "hyperdusk_volume_up_double_press_action";
    public static final String ACTION_NONE = "none";
    public static final String ACTION_TORCH = "toggle_torch";
    private static final int VOLUME_DOWN = 25;
    private static final int VOLUME_UP = 24;
    private static final int MAX_DOUBLE_PRESS = 2;
    private static final String RULE_MANAGER =
        "com.android.server.input.shortcut.singlekeyrule.MiuiSingleKeyRuleManager";
    private static final String RULE_INFO =
        "com.android.server.input.shortcut.singlekeyrule.MiuiSingleKeyInfo";
    private static final String VOLUME_RULE =
        "com.android.server.input.shortcut.singlekeyrule.VolumeDownKeyRule";

    private boolean installed;

    @Override
    protected void init(@NonNull PackageTarget target) throws Throwable {
        if (!target.isSystemServer()) return;
        ClassLoader loader = target.getClassLoader();
        Class<?> manager = Class.forName(RULE_MANAGER, false, loader);
        Class<?> volumeRule = Class.forName(VOLUME_RULE, false, loader);
        hookMethod(volumeRule, "getMiuiMaxMultiPressCount", before(this::onMaxCount));
        hookMethod(volumeRule, "onMiuiMultiPress", Object.class, long.class, int.class, int.class,
            before(this::onMultiPress));
        hookMethod(manager, "addRule", Class.forName(RULE_INFO, false, loader),
            Class.forName("com.android.server.policy.MiuiSingleKeyRule", false, loader),
            before(this::onAddRule));
        XposedLog.i(TAG, "system", "HyperOS 4 MiuiSingleKeyRuleManager hooks installed");
    }

    private void onMaxCount(HookParam param) {
        Object rule = param.getThis();
        if (primaryKey(rule) == VOLUME_UP) {
            param.setResult(isTorchEnabled() ? MAX_DOUBLE_PRESS : 1);
        }
    }

    private void onMultiPress(HookParam param) {
        Object rule = param.getThis();
        Object[] args = param.getArgs();
        if (primaryKey(rule) != VOLUME_UP || args.length < 3 || !isTorchEnabled()) return;
        if (args[2] instanceof Integer count && count == MAX_DOUBLE_PRESS) {
            triggerTorch(rule);
            param.setResult(null);
        }
    }

    private void onAddRule(HookParam param) {
        if (installed) return;
        Object info = param.getArgs()[0];
        if (primaryKey(info) != VOLUME_DOWN) return;
        try {
            Object originalRule = param.getArgs()[1];
            Object clonedInfo = cloneInfo(info);
            Object clonedRule = cloneVolumeRule(originalRule, clonedInfo);
            Method addRule = param.getThis().getClass().getMethod("addRule", info.getClass(),
                originalRule.getClass().getSuperclass());
            installed = true;
            addRule.invoke(param.getThis(), clonedInfo, clonedRule);
            XposedLog.i(TAG, "system", "Registered native volume-up double-press rule");
        } catch (Throwable t) {
            XposedLog.e(TAG, "system", "Native volume-up rule unavailable; fail closed", t);
        }
    }

    private Object cloneInfo(Object source) throws Exception {
        Class<?> type = source.getClass();
        int key = VOLUME_UP;
        Map<?, ?> actionDefaults = mapValue(source, "getActionAndDefaultFunctionMap");
        Map<?, ?> actionTypes = mapValue(source, "getActionMapForType");
        Map<?, ?> maxCounts = mapValue(source, "getActionMaxCountMap");
        long timeout = numberValue(source, "getLongPressTimeout", 300L);
        Map<Object, Object> mutableMax = new HashMap<>(maxCounts);
        mutableMax.put("volumekey_launch_camera", MAX_DOUBLE_PRESS);
        Constructor<?> ctor = type.getConstructor(int.class, Map.class, long.class, Map.class, Map.class);
        return ctor.newInstance(key, new HashMap<>(actionDefaults), timeout,
            new HashMap<>(actionTypes), mutableMax);
    }

    private Object cloneVolumeRule(Object source, Object info) throws Exception {
        Class<?> type = source.getClass();
        Context context = (Context) fieldValue(source, "mContext");
        Handler handler = (Handler) fieldValue(source, "mHandler");
        Object helper = fieldValue(source, "mMiuiShortcutTriggerHelper");
        int policyFlag = ((Number) fieldValue(source, "mPolicyFlag", 0)).intValue();
        for (Constructor<?> ctor : type.getConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length == 5 && p[0].isInstance(context) && p[1].isInstance(handler)
                && p[2].isInstance(info) && p[3].isInstance(helper) && p[4] == int.class) {
                return ctor.newInstance(context, handler, info, helper, policyFlag);
            }
        }
        throw new NoSuchMethodException("VolumeDownKeyRule constructor");
    }

    private void triggerTorch(Object rule) {
        try {
            Context context = (Context) fieldValue(rule, "mContext");
            Class<?> actions = Class.forName("com.miui.server.input.util.ShortCutActionsUtils",
                false, rule.getClass().getClassLoader());
            Object instance = actions.getMethod("getInstance", Context.class).invoke(null, context);
            actions.getMethod("triggerFunction", String.class, String.class, Bundle.class, boolean.class)
                .invoke(instance, "turn_on_torch", "double_click_volume_up", Bundle.EMPTY, false);
        } catch (Throwable t) {
            XposedLog.e(TAG, "system", "Native torch action failed", t);
        }
    }

    private static boolean isTorchEnabled() {
        return isTorchAction(PrefsBridge.getString(PREF_KEY, ACTION_NONE));
    }

    /** 严格白名单解析，未知值和空值均回到未设置。 */
    public static boolean isTorchAction(String value) { return ACTION_TORCH.equals(value); }

    private static int primaryKey(Object value) {
        try {
            Method method = value.getClass().getMethod("getPrimaryKey");
            return ((Number) method.invoke(value)).intValue();
        } catch (Throwable ignored) { return -1; }
    }

    private static Map<?, ?> mapValue(Object value, String methodName) throws Exception {
        return (Map<?, ?>) value.getClass().getMethod(methodName).invoke(value);
    }

    private static long numberValue(Object value, String methodName, long fallback) {
        try { return ((Number) value.getClass().getMethod(methodName).invoke(value)).longValue(); }
        catch (Throwable ignored) { return fallback; }
    }

    private static Object fieldValue(Object value, String name) throws Exception {
        return fieldValue(value, name, null);
    }

    private static Object fieldValue(Object value, String name, Object fallback) throws Exception {
        Class<?> type = value.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(value);
            } catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        if (fallback != null) return fallback;
        throw new NoSuchFieldException(name);
    }
}
