package io.github.yuxiximyxx.honortoolbox.lockpanel;
/*
 * 移植自 LockPanel（作者 chaosz）v1.20 的 MainHook：入口逻辑
 * 零改——Application.attach 后 Cfg.init、PanelHooks 无条件
 * 安装（idle until cfg loads，行为由配置里的 master 决定，
 * 不经工具箱 FeatureGate，与原版一致）。由 ToolboxHook 在
 * com.android.systemui 宿主分支分发到这里。
 */

import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/* JADX INFO: loaded from: classes.dex */
public final class LockPanelHook implements IXposedHookLoadPackage {
    private static final String SYSTEMUI = "com.android.systemui";
    static final String VERSION = "1.20";

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!SYSTEMUI.equals(loadPackageParam.packageName)) {
            return;
        }
        Log.i("──── LockPanel v1.20 ────");
        Log.i("loading in " + loadPackageParam.packageName + " (process=" + loadPackageParam.processName + ")");
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", new Object[]{Context.class, new XC_MethodHook() { // from class: com.chaosz.lockpanel.MainHook.1
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                    if (methodHookParam.thisObject instanceof Application) {
                        Cfg.init((Application) methodHookParam.thisObject);
                    }
                }
            }});
        } catch (Throwable th) {
            Log.w("Application.attach hook failed: " + th);
        }
        PanelHooks.install(loadPackageParam.classLoader);
        Log.i("hooks installed (idle until cfg loads)");
    }
}
