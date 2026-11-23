package io.github.yuxiximyxx.honortoolbox.lockpanel;
/*
 * 移植自 LockPanel（作者 chaosz）v1.20 的 Cfg：由 APK 反编译
 * 代码移植，配置加载/观察/重试/读写逻辑零改。合入适配只有
 * 通道三处：Provider authority 改为
 * io.github.yuxiximyxx.honortoolbox.lockpanel、CFG_CHANGED
 * 广播 action 改工具箱包名前缀、镜像偏好文件名改为
 * lockpanel_settings；13 个键名、默认值、取值格式逐字不变。
 * 原独立版配置不迁移（见 LockPanelSettings 的播种口径）。
 * 另有两处 jadx 反编译伪影的最小修复（语义与原版一致）：
 * query() 的异常处理被 jadx 拍平后引用了未声明变量，按原
 * 日志/返回值/关游标路径重写为 try/catch/finally；init()
 * 对 final 参数的赋值改为本地变量承接。
 */

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;

/* JADX INFO: loaded from: classes.dex */
public final class Cfg {
    public static final String ACTION_CFG_CHANGED = "io.github.yuxiximyxx.honortoolbox.lockpanel.CFG_CHANGED";
    public static final String ACT_CALCULATOR = "builtin:1";
    public static final String ACT_CAMERA = "camera";
    public static final String ACT_CLOCK = "builtin:3";
    public static final String ACT_FLASHLIGHT = "builtin:2";
    public static final String ACT_RECORDER = "builtin:0";
    public static final String ACT_SCANNER = "builtin:4";
    public static final String AUTHORITY = "io.github.yuxiximyxx.honortoolbox.lockpanel";
    public static final String MODE_FOLLOW = "follow";
    public static final String MODE_OFF = "off";
    public static final String MODE_ON = "on";
    public static final String PREFIX_ACT = "act:";
    public static final String PREFIX_APP = "app:";
    public static final String PREFIX_BUILTIN = "builtin:";
    public static final String PREFS_FILE = "lockpanel_settings";
    private static final int RECEIVER_EXPORTED = 2;
    private static final String SYSTEMUI_PKG = "com.android.systemui";
    private static volatile Context sCtx;
    private static long sLastLazy;
    private static boolean sLoading;
    private static boolean sReloadPending;
    private static int sRetryIndex;
    private static boolean sRetryPending;
    private static volatile Map<String, String> sSnap;
    private static volatile boolean sStarted;
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/kv");
    public static final String K_ICON_1 = "icon_1";
    public static final String K_ICON_2 = "icon_2";
    public static final String K_ICON_3 = "icon_3";
    public static final String K_ICON_4 = "icon_4";
    public static final String K_ICON_5 = "icon_5";
    public static final String[] ICON_KEYS = {K_ICON_1, K_ICON_2, K_ICON_3, K_ICON_4, K_ICON_5};
    public static final String K_ACTION_1 = "action_1";
    public static final String K_ACTION_2 = "action_2";
    public static final String K_ACTION_3 = "action_3";
    public static final String K_ACTION_4 = "action_4";
    public static final String K_ACTION_5 = "action_5";
    public static final String[] ACTION_KEYS = {K_ACTION_1, K_ACTION_2, K_ACTION_3, K_ACTION_4, K_ACTION_5};
    public static final String K_MASTER = "master";
    public static final String K_PANEL = "panel_mode";
    public static final String K_HIDE_STYLE = "hide_change_style";
    public static final String[] KEYS = {K_MASTER, K_PANEL, K_HIDE_STYLE, K_ICON_1, K_ICON_2, K_ICON_3, K_ICON_4, K_ICON_5, K_ACTION_1, K_ACTION_2, K_ACTION_3, K_ACTION_4, K_ACTION_5};
    private static final Object LOCK = new Object();
    private static final long[] RETRY_DELAYS = {1000, 3000, 10000};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable RETRY = new Runnable() { // from class: com.chaosz.lockpanel.Cfg.1
        @Override // java.lang.Runnable
        public void run() {
            synchronized (Cfg.LOCK) {
                boolean unused = Cfg.sRetryPending = false;
            }
            Cfg.reloadAsync("retry");
        }
    };

    private Cfg() {
    }

    public static boolean isOn(String str) {
        Map<String, String> map = sSnap;
        return map != null && truthy(map.get(str));
    }

    public static String str(String str, String str2) {
        String str3;
        Map<String, String> map = sSnap;
        return (map == null || (str3 = map.get(str)) == null || str3.isEmpty()) ? str2 : str3;
    }

    public static boolean truthy(String str) {
        return str != null && ("1".equals(str) || "true".equalsIgnoreCase(str));
    }

    public static String describe() {
        Map<String, String> map = sSnap;
        return map == null ? "(未加载)" : describeOf(map);
    }

    public static synchronized void init(final Context context) {
        if (!sStarted && context != null) {
            sStarted = true;
            Context appContext = context;
            if (appContext.getApplicationContext() != null) {
                appContext = appContext.getApplicationContext();
            }
            sCtx = appContext;
            final Context captured = appContext;
            new Handler(Looper.getMainLooper()).post(new Runnable() { // from class: com.chaosz.lockpanel.Cfg.2
                @Override // java.lang.Runnable
                public void run() {
                    Cfg.registerObserver(captured);
                    Cfg.registerBroadcast(captured);
                    Cfg.reloadAsync();
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void registerObserver(Context context) {
        try {
            context.getContentResolver().registerContentObserver(URI, false, new ContentObserver(new Handler(Looper.getMainLooper())) { // from class: com.chaosz.lockpanel.Cfg.3
                @Override // android.database.ContentObserver
                public void onChange(boolean z) {
                    Cfg.reloadAsync();
                }
            });
            Log.i("cfg: observer registered (io.github.yuxiximyxx.honortoolbox.lockpanel)");
        } catch (Throwable th) {
            Log.w("cfg: registerContentObserver failed (仅靠广播): " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void registerBroadcast(Context context) {
        registerCfgReceiver(context);
        registerWakeReceiver(context);
    }

    private static void registerCfgReceiver(Context context) {
        try {
            BroadcastReceiver broadcastReceiver = new BroadcastReceiver() { // from class: com.chaosz.lockpanel.Cfg.4
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context2, Intent intent) {
                    Cfg.reloadAsync("broadcast");
                }
            };
            IntentFilter intentFilter = new IntentFilter(ACTION_CFG_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(broadcastReceiver, intentFilter, RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(broadcastReceiver, intentFilter);
            }
            Log.i("cfg: broadcast receiver registered (io.github.yuxiximyxx.honortoolbox.lockpanel.CFG_CHANGED)");
        } catch (Throwable th) {
            Log.w("cfg: registerReceiver failed (仅靠观察者): " + th);
        }
    }

    private static void registerWakeReceiver(Context context) {
        try {
            BroadcastReceiver broadcastReceiver = new BroadcastReceiver() { // from class: com.chaosz.lockpanel.Cfg.5
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context2, Intent intent) {
                    String action = intent != null ? intent.getAction() : null;
                    if ("android.intent.action.USER_UNLOCKED".equals(action)) {
                        Cfg.ensureLoaded("unlock");
                    } else if ("android.intent.action.SCREEN_ON".equals(action)) {
                        Cfg.ensureLoaded("screen");
                    }
                }
            };
            IntentFilter intentFilter = new IntentFilter("android.intent.action.USER_UNLOCKED");
            intentFilter.addAction("android.intent.action.SCREEN_ON");
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(broadcastReceiver, intentFilter, RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(broadcastReceiver, intentFilter);
            }
            Log.i("cfg: unlock/screen receiver registered");
        } catch (Throwable th) {
            Log.w("cfg: registerWakeReceiver failed: " + th);
        }
    }

    public static void initLazy(Context context) {
        if (!sStarted && context != null) {
            init(context);
        }
    }

    public static void reloadAsync() {
        reloadAsync("observer");
    }

    public static void ensureLoaded(String str) {
        if (sSnap != null) {
            return;
        }
        long jElapsedRealtime = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (jElapsedRealtime - sLastLazy < 3000) {
                return;
            }
            sLastLazy = jElapsedRealtime;
            sRetryIndex = 0;
            reloadAsync(str);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void reloadAsync(final String str) {
        final Context context = sCtx;
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            if (sLoading) {
                sReloadPending = true;
                return;
            }
            sLoading = true;
            Thread thread = new Thread(new Runnable() { // from class: com.chaosz.lockpanel.Cfg.6
                @Override // java.lang.Runnable
                public void run() {
                    boolean z;
                    try {
                        Cfg.applyIfChanged(Cfg.query(context), str);
                        synchronized (Cfg.LOCK) {
                            boolean unused = Cfg.sLoading = false;
                            z = Cfg.sReloadPending;
                            boolean unused2 = Cfg.sReloadPending = false;
                        }
                        if (z) {
                            Cfg.reloadAsync(str);
                        }
                    } catch (Throwable th) {
                        synchronized (Cfg.LOCK) {
                            boolean unused3 = Cfg.sLoading = false;
                            boolean unused4 = Cfg.sReloadPending;
                            boolean unused5 = Cfg.sReloadPending = false;
                            throw th;
                        }
                    }
                }
            }, "LockPanelCfg");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void applyIfChanged(Map<String, String> map, String str) {
        if (map == null) {
            scheduleRetry();
            return;
        }
        cancelRetry();
        synchronized (LOCK) {
            Map<String, String> map2 = sSnap;
            if (map2 == null || !map2.equals(map)) {
                sSnap = map;
                Log.i("cfg changed (" + str + "): " + (map2 == null ? "(首次)" : describeOf(map2)) + " -> " + describeOf(map));
                new Handler(Looper.getMainLooper()).post(new Runnable() { // from class: com.chaosz.lockpanel.Cfg.7
                    @Override // java.lang.Runnable
                    public void run() {
                        PanelHooks.onCfgChanged();
                    }
                });
            }
        }
    }

    private static void scheduleRetry() {
        synchronized (LOCK) {
            if (!sRetryPending && sRetryIndex < RETRY_DELAYS.length) {
                long j = RETRY_DELAYS[sRetryIndex];
                sRetryIndex++;
                int i = sRetryIndex;
                sRetryPending = true;
                MAIN.postDelayed(RETRY, j);
                Log.w("cfg: load failed, retry " + i + "/" + RETRY_DELAYS.length + " in " + j + "ms");
            }
        }
    }

    private static void cancelRetry() {
        synchronized (LOCK) {
            if (sRetryPending) {
                MAIN.removeCallbacks(RETRY);
            }
            sRetryPending = false;
            sRetryIndex = 0;
        }
    }

    private static String describeOf(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("master=").append(truthy(map.get(K_MASTER)) ? "开" : "关").append(" panel=").append(orDefault(map.get(K_PANEL), MODE_FOLLOW)).append(" hideStyle=").append(truthy(map.get(K_HIDE_STYLE)) ? "开" : "关");
        for (int i = 0; i < ICON_KEYS.length; i++) {
            String str = map.get(ICON_KEYS[i]);
            if (str != null && !str.isEmpty()) {
                sb.append(" icon").append(i + 1).append('=').append(str);
            }
        }
        for (int i2 = 0; i2 < ACTION_KEYS.length; i2++) {
            String strSubstring = map.get(ACTION_KEYS[i2]);
            if (strSubstring != null && !strSubstring.isEmpty()) {
                int iIndexOf = strSubstring.indexOf(9);
                StringBuilder sbAppend = sb.append(" act").append(i2 + 1).append('=');
                if (iIndexOf >= 0) {
                    strSubstring = strSubstring.substring(0, iIndexOf);
                }
                sbAppend.append(strSubstring);
            }
        }
        return sb.toString();
    }

    public static String actionLabel(String str, String str2) {
        int i;
        if (str == null || str.isEmpty()) {
            return "原厂 · " + str2;
        }
        if (ACT_RECORDER.equals(str)) {
            return "录音机";
        }
        if (ACT_CALCULATOR.equals(str)) {
            return "计算器";
        }
        if (ACT_FLASHLIGHT.equals(str)) {
            return "手电筒";
        }
        if (ACT_CLOCK.equals(str)) {
            return "时钟";
        }
        if (ACT_SCANNER.equals(str)) {
            return "扫描";
        }
        if (ACT_CAMERA.equals(str)) {
            return "相机";
        }
        if (str.startsWith(PREFIX_APP)) {
            String strSubstring = str.substring(PREFIX_APP.length());
            int iIndexOf = strSubstring.indexOf(9);
            if (iIndexOf > 0 && (i = iIndexOf + 1) < strSubstring.length()) {
                String strSubstring2 = strSubstring.substring(0, iIndexOf);
                int iIndexOf2 = strSubstring2.indexOf(47);
                if (iIndexOf2 > 0) {
                    strSubstring2 = strSubstring2.substring(0, iIndexOf2);
                }
                return strSubstring.substring(i) + " · " + strSubstring2;
            }
            return "应用 · " + strSubstring;
        }
        if (str.startsWith(PREFIX_ACT)) {
            return "Activity · " + str.substring(PREFIX_ACT.length());
        }
        return str;
    }

    private static String orDefault(String str, String str2) {
        return (str == null || str.isEmpty()) ? str2 : str;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Map<String, String> query(Context context) {
        if (context == null) {
            return null;
        }
        Cursor cursorQuery = null;
        try {
            cursorQuery = context.getContentResolver().query(URI, null, null, null, null);
            if (cursorQuery == null) {
                Log.w("cfg: provider returned null cursor");
                return null;
            }
            HashMap map = new HashMap();
            while (cursorQuery.moveToNext()) {
                if (cursorQuery.getColumnCount() >= RECEIVER_EXPORTED) {
                    map.put(cursorQuery.getString(0), cursorQuery.getString(1));
                }
            }
            return map;
        } catch (Throwable th) {
            Log.w("cfg: query failed: " + th);
            return null;
        } finally {
            if (cursorQuery != null) {
                try {
                    cursorQuery.close();
                } catch (Throwable th2) {
                }
            }
        }
    }

    public static boolean read(Context context, String str) {
        return context != null && context.getSharedPreferences(PREFS_FILE, 0).getBoolean(str, false);
    }

    public static String readStr(Context context, String str, String str2) {
        if (context == null) {
            return str2;
        }
        return context.getSharedPreferences(PREFS_FILE, 0).getString(str, str2);
    }

    public static void write(Context context, String str, boolean z) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS_FILE, 0).edit().putBoolean(str, z).apply();
        notifyChanged(context);
    }

    public static void writeStr(Context context, String str, String str2) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS_FILE, 0).edit().putString(str, str2).apply();
        notifyChanged(context);
    }

    public static void resetAll(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences.Editor editorEdit = context.getSharedPreferences(PREFS_FILE, 0).edit();
        editorEdit.putBoolean(K_MASTER, false).putString(K_PANEL, MODE_FOLLOW).putBoolean(K_HIDE_STYLE, false);
        for (String str : ICON_KEYS) {
            editorEdit.putString(str, "");
        }
        for (String str2 : ACTION_KEYS) {
            editorEdit.putString(str2, "");
        }
        editorEdit.apply();
        notifyChanged(context);
    }

    private static void notifyChanged(Context context) {
        try {
            context.getContentResolver().notifyChange(URI, null);
        } catch (Throwable th) {
        }
        try {
            context.sendBroadcast(new Intent(ACTION_CFG_CHANGED).setPackage(SYSTEMUI_PKG));
        } catch (Throwable th2) {
        }
    }
}
