package io.github.yuxiximyxx.honortoolbox.lockpanel;
/*
 * 移植自 LockPanel（作者 chaosz）v1.20 的 CfgProvider：query
 * 供出 Cfg.KEYS 全键值（MatrixCursor key/val 两列）的逻辑
 * 零改；数据源是工具箱镜像偏好 lockpanel_settings（经 Cfg
 * 的 read/readStr 同口径读取），authority 在清单里声明为
 * io.github.yuxiximyxx.honortoolbox.lockpanel。
 */

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/* JADX INFO: loaded from: classes.dex */
public final class LockPanelConfigProvider extends ContentProvider {
    @Override // android.content.ContentProvider
    public boolean onCreate() {
        return true;
    }

    @Override // android.content.ContentProvider
    public Cursor query(Uri uri, String[] strArr, String str, String[] strArr2, String str2) {
        MatrixCursor matrixCursor = new MatrixCursor(new String[]{"key", "val"}, Cfg.KEYS.length);
        Context context = getContext();
        if (context != null) {
            for (String str3 : Cfg.KEYS) {
                matrixCursor.addRow(new Object[]{str3, valueOf(context, str3)});
            }
        }
        return matrixCursor;
    }

    private static String valueOf(Context context, String str) {
        if (Cfg.K_PANEL.equals(str)) {
            return Cfg.readStr(context, Cfg.K_PANEL, Cfg.MODE_FOLLOW);
        }
        for (String str2 : Cfg.ICON_KEYS) {
            if (str2.equals(str)) {
                return Cfg.readStr(context, str, "");
            }
        }
        for (String str3 : Cfg.ACTION_KEYS) {
            if (str3.equals(str)) {
                return Cfg.readStr(context, str, "");
            }
        }
        return Cfg.read(context, str) ? "1" : "0";
    }

    @Override // android.content.ContentProvider
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.lockpanel.kv";
    }

    @Override // android.content.ContentProvider
    public Uri insert(Uri uri, ContentValues contentValues) {
        return null;
    }

    @Override // android.content.ContentProvider
    public int delete(Uri uri, String str, String[] strArr) {
        return 0;
    }

    @Override // android.content.ContentProvider
    public int update(Uri uri, ContentValues contentValues, String str, String[] strArr) {
        return 0;
    }
}
