/*
 * This file is part of HyperDusk.
 * Copyright (C) 2026 HyperDusk Contributors
 */
package com.mcxiaochen.hyperdusk;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;

/** MVP 设置页：状态、目标系统和双击音量上键动作。 */
public final class MainActivity extends Activity {
    private static final String PREF_KEY = "hyperdusk_volume_up_double_press_action";
    private static final String[] VALUES = {"none", "toggle_torch"};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        TextView title = text("HyperDusk", 24);
        root.addView(title);
        root.addView(text(Application.moduleActivated ? "模块状态：已连接 LSPosed" : "模块状态：未连接 LSPosed", 16));
        root.addView(text("目标系统：Xiaomi 17 Pro / HyperOS 4（OS4.0.0.27.XBLCNXM）\n仅作用于 system_server，启用后重启一次设备。", 14));
        root.addView(text("双击音量上键动作", 16));
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"未设置（保持原行为）", "切换手电筒"}));
        String current = PrefsBridge.getString(PREF_KEY, "none");
        spinner.setSelection("toggle_torch".equals(current) ? 1 : 0);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                PrefsBridge.putString(PREF_KEY, VALUES[position]);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        root.addView(spinner);
        setContentView(root);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setGravity(Gravity.START);
        view.setPadding(0, 16, 0, 16);
        return view;
    }
}
