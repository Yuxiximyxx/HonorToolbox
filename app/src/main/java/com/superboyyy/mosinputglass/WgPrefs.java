package com.superboyyy.mosinputglass;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 配置快照（荣耀工具箱合入适配，v1.8.0）：原版 hook 端读的是
 * LSPosed 远程配置（getRemotePreferences，设置 App 写、框架
 * 跨进程同步并推送变更监听）。合入后换成工具箱的
 * ContentProvider 直供范式（与快服务改向、小黑盒增强同款）：
 * 工具箱设置页把设置写进工具箱私有镜像偏好，Provider 供出，
 * 本类经 ContentResolver.call 拉全量快照到内存，Main 的
 * prop()/allPrefs() 读法不变（仍走 SharedPreferences 接口）。
 *
 * 与原版的差别：远程配置的变更监听不再有推送——快照在安装
 * 时拉一次，之后每次 safeApply（键盘显示/输入开始等时机）
 * 节流重拉（2 秒内不重复），设置改动在下次呼出键盘时生效；
 * 原版「写 restart_ime 时间戳让输入法自杀重启」的通道随之
 * 停用，改由工具箱设置页的「重启输入法」行 root 强停两个
 * 输入法包等效实现。拉取失败保留旧快照，再失败则为空快照、
 * prop() 自然回落 Config.DEFAULTS。
 */
final class WgPrefs implements SharedPreferences {

    static final Uri URI = Uri.parse("content://io.github.yuxiximyxx.honortoolbox.wetypeglass");
    static final String METHOD_GET_ALL = "getAll";
    private static final long REFRESH_THROTTLE_MS = 2000;

    private final Context context;
    private volatile Map<String, String> snapshot = new HashMap<>();
    private final CopyOnWriteArrayList<OnSharedPreferenceChangeListener> listeners =
            new CopyOnWriteArrayList<>();
    private long lastRefreshMs = 0;

    WgPrefs(Context context) {
        this.context = context;
    }

    /** 拉一份最新快照；成功返回 true，失败保留旧快照。 */
    synchronized boolean refresh() {
        try {
            Bundle out = context.getContentResolver().call(URI, METHOD_GET_ALL, null, null);
            if (out == null) return false;
            Map<String, String> m = new HashMap<>();
            for (String k : out.keySet()) {
                Object v = out.get(k);
                if (v instanceof String) m.put(k, (String) v);
            }
            snapshot = m;
            lastRefreshMs = System.currentTimeMillis();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 节流重拉：距上次成功拉取不足 2 秒直接跳过。 */
    void refreshThrottled() {
        if (System.currentTimeMillis() - lastRefreshMs < REFRESH_THROTTLE_MS) return;
        refresh();
    }

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(snapshot);
    }

    @Override
    public String getString(String key, String defValue) {
        String v = snapshot.get(key);
        return v != null ? v : defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        try {
            String v = snapshot.get(key);
            return v != null ? Integer.parseInt(v.trim()) : defValue;
        } catch (Throwable t) {
            return defValue;
        }
    }

    @Override
    public long getLong(String key, long defValue) {
        try {
            String v = snapshot.get(key);
            return v != null ? Long.parseLong(v.trim()) : defValue;
        } catch (Throwable t) {
            return defValue;
        }
    }

    @Override
    public float getFloat(String key, float defValue) {
        try {
            String v = snapshot.get(key);
            return v != null ? Float.parseFloat(v.trim()) : defValue;
        } catch (Throwable t) {
            return defValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        String v = snapshot.get(key);
        if (v == null) return defValue;
        if ("1".equals(v)) return true;
        if ("0".equals(v)) return false;
        return Boolean.parseBoolean(v);
    }

    @Override
    public boolean contains(String key) {
        return snapshot.containsKey(key);
    }

    @Override
    public Editor edit() {
        // hook 端从不写配置（原版同口径：写入只发生在设置 App
        // 侧）；这里给一个只改本地快照的最小实现兜底。
        return new Editor() {
            private final Map<String, String> pending = new HashMap<>();
            private boolean clear = false;

            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> values) { return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, String.valueOf(value)); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, String.valueOf(value)); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, String.valueOf(value)); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value ? "1" : "0"); return this; }
            @Override public Editor remove(String key) { pending.put(key, null); return this; }
            @Override public Editor clear() { clear = true; return this; }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                Map<String, String> m = new HashMap<>(snapshot);
                if (clear) m.clear();
                for (Map.Entry<String, String> e : pending.entrySet()) {
                    if (e.getValue() == null) m.remove(e.getKey());
                    else m.put(e.getKey(), e.getValue());
                }
                snapshot = m;
            }
        };
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        // 无推送通道：监听器只登记不触发（见类头说明）。
        if (listener != null) listeners.add(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        listeners.remove(listener);
    }
}
