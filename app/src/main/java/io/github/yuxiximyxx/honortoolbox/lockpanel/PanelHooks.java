package io.github.yuxiximyxx.honortoolbox.lockpanel;
/*
 * 移植自 LockPanel（作者 chaosz）v1.20 的 PanelHooks：由 APK
 * 反编译代码移植，全部 hook 落点与逻辑零改（Resources
 * .getBoolean、isLiftBottomToolsSwitchOn、Magazine
 * isInterestedEvent/dispatchTouchEvent、isToolBoxDisabled、
 * isHideQuickView、ToolBox startActivityById/onFinishInflate/
 * refreshShortcutView5/updateFlashlightViewInner、Info
 * onThemeStlyeChange/changeChildrenViewState）。
 */

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/* JADX INFO: loaded from: classes.dex */
final class PanelHooks {
    static final String BOOL_NAME = "config_liftBottomToolsSwitch";
    static final String CLS_CONST = "com.hihonor.keyguard.util.BottomToolsConstant";
    static final String CLS_DISABLED = "com.hihonor.keyguard.util.DisabledFeatureUtils";
    static final String CLS_INFO = "com.hihonor.keyguard.view.widget.InfoCenterView";
    static final String CLS_KG_CFG = "com.hihonor.keyguard.KeyguardCfg";
    static final String CLS_MAGAZINE = "com.android.keyguard.hwlockscreen.magazine.MagazineDetailView";
    static final String CLS_TOOLBOX = "com.hihonor.keyguard.view.widget.ToolBoxView";
    private static final int ID_CHANGE_STYLE_FALLBACK = 2131362485;
    private static final int MODE_BROWSE = 4;
    private static final int MODE_LIFT = 6;
    static final String PKG_SYSTEMUI = "com.android.systemui";
    private static ClassLoader sCl;
    private static volatile boolean sDispatchingAction;
    private static volatile int sLiftBoolId;
    private static volatile boolean sRestoring;
    private static final String[] DEFAULT_ICONS = {"icsvg_public_recorder", "icsvg_hnunlock_calculator", "icsvg_public_flashlight_off_filled", "icsvg_public_timercircle_filled", "icsvg_public_hivision"};
    private static int sIdChangeStyle = 0;
    private static final List<Object> sInfoCenters = new ArrayList();
    private static final List<Object> sMagazines = new ArrayList();
    private static final List<Object> sToolBoxes = new ArrayList();
    private static final Map<Object, boolean[]> sCustomizedSlots = new WeakHashMap();
    private static final int[] sShortcutIds = new int[5];
    private static final int[] sCircleIds = new int[5];
    private static final Map<String, Bitmap> sCutoutCache = new HashMap();
    private static final Map<View, Integer> sHidden = new WeakHashMap();

    private PanelHooks() {
    }

    private static boolean active() {
        return Cfg.isOn(Cfg.K_MASTER);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Boolean wantPanel() {
        if (!active()) {
            return null;
        }
        String str = Cfg.str(Cfg.K_PANEL, Cfg.MODE_FOLLOW);
        if (Cfg.MODE_ON.equals(str)) {
            return Boolean.TRUE;
        }
        if (Cfg.MODE_OFF.equals(str)) {
            return Boolean.FALSE;
        }
        return null;
    }

    static void install(ClassLoader classLoader) {
        sCl = classLoader;
        hookGetBoolean();
        hookLiftSwitch();
        hookIsInterestedEvent();
        hookDispatchTouch();
        hookToolBoxGates();
        hookToolBoxActions();
        hookToolBoxIcons();
        hookInfoCenter();
    }

    private static void hookGetBoolean() {
        hook("Resources.getBoolean", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.1
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(Resources.class, "getBoolean", new Object[]{Integer.TYPE, new XC_MethodHook() { // from class: com.chaosz.lockpanel.PanelHooks.1.1
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        Boolean boolWantPanel = PanelHooks.wantPanel();
                        if (boolWantPanel == null) {
                            return;
                        }
                        try {
                            Resources resources = (Resources) methodHookParam.thisObject;
                            int iIntValue = ((Integer) methodHookParam.args[0]).intValue();
                            if (PanelHooks.sLiftBoolId == 0) {
                                int unused = PanelHooks.sLiftBoolId = resources.getIdentifier(PanelHooks.BOOL_NAME, "bool", PanelHooks.PKG_SYSTEMUI);
                            }
                            if (PanelHooks.sLiftBoolId != 0 && iIntValue == PanelHooks.sLiftBoolId && PanelHooks.PKG_SYSTEMUI.equals(resources.getResourcePackageName(iIntValue)) && PanelHooks.BOOL_NAME.equals(resources.getResourceEntryName(iIntValue))) {
                                methodHookParam.setResult(boolWantPanel);
                            }
                        } catch (Throwable th) {
                        }
                    }
                }});
            }
        });
    }

    private static void hookLiftSwitch() {
        hook("BottomToolsConstant.isLiftBottomToolsSwitchOn", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.2
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_CONST, PanelHooks.sCl, "isLiftBottomToolsSwitchOn", new Object[]{Context.class, new XC_MethodHook() { // from class: com.chaosz.lockpanel.PanelHooks.2.1
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        Boolean boolWantPanel = PanelHooks.wantPanel();
                        if (boolWantPanel != null) {
                            methodHookParam.setResult(boolWantPanel);
                        }
                    }
                }});
            }
        });
    }

    private static void hookIsInterestedEvent() {
        hook("MagazineDetailView.isInterestedEvent", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.3
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_MAGAZINE, PanelHooks.sCl, "isInterestedEvent", new Object[]{MotionEvent.class, new XC_MethodHook() { // from class: com.chaosz.lockpanel.PanelHooks.3.1
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        PanelHooks.rememberMagazine(methodHookParam.thisObject);
                        PanelHooks.enforce(methodHookParam.thisObject, (MotionEvent) methodHookParam.args[0]);
                    }
                }});
            }
        });
    }

    private static void hookDispatchTouch() {
        hook("MagazineDetailView.dispatchTouchEvent", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.4
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_MAGAZINE, PanelHooks.sCl, "dispatchTouchEvent", new Object[]{MotionEvent.class, new XC_MethodHook() { // from class: com.chaosz.lockpanel.PanelHooks.4.1
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        PanelHooks.rememberMagazine(methodHookParam.thisObject);
                        PanelHooks.enforce(methodHookParam.thisObject, (MotionEvent) methodHookParam.args[0]);
                    }
                }});
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void enforce(Object obj, MotionEvent motionEvent) {
        Boolean boolWantPanel;
        if (obj == null || motionEvent == null || (boolWantPanel = wantPanel()) == null || motionEvent.getActionMasked() != 0) {
            return;
        }
        Cfg.ensureLoaded("touch");
        try {
            int intField = XposedHelpers.getIntField(obj, "mTouchMode");
            if (!boolWantPanel.booleanValue()) {
                if (intField == MODE_LIFT) {
                    XposedHelpers.setIntField(obj, "mTouchMode", MODE_BROWSE);
                    setToolBoxAlpha(obj, 0.0f);
                    Log.i("强制禁止: touchMode 6 -> 4");
                    return;
                }
                return;
            }
            if (intField != MODE_LIFT && !isLifted(obj) && inLiftRegion(obj, motionEvent)) {
                XposedHelpers.setIntField(obj, "mTouchMode", MODE_LIFT);
                setToolBoxAlpha(obj, 1.0f);
                Log.i("强制允许: touchMode " + intField + " -> 6（原厂没给）");
            }
        } catch (Throwable th) {
            Log.w("enforce failed: " + th);
        }
    }

    private static boolean isLifted(Object obj) {
        Object objectField = XposedHelpers.getObjectField(obj, "mLiftTransition");
        if (objectField != null) {
            Object objCallMethod = XposedHelpers.callMethod(objectField, "isLifted", new Object[0]);
            if (objCallMethod instanceof Boolean) {
                return ((Boolean) objCallMethod).booleanValue();
            }
        }
        try {
            return XposedHelpers.getFloatField(obj, "mLastProgress") > 0.0f;
        } catch (Throwable th) {
            return false;
        }
    }

    private static boolean inLiftRegion(Object obj, MotionEvent motionEvent) {
        int iIntValue;
        try {
            int height = ((View) obj).getHeight();
            if (height <= 0) {
                return false;
            }
            Object objectField = XposedHelpers.getObjectField(obj, "mAdapterNoAnimation");
            if (objectField == null) {
                iIntValue = 0;
            } else {
                iIntValue = ((Integer) XposedHelpers.callMethod(objectField, "getLiftRespondHeight", new Object[0])).intValue();
            }
            return (motionEvent.getY() + ((float) iIntValue)) + 50.0f > ((float) height);
        } catch (Throwable th) {
            return false;
        }
    }

    private static void setToolBoxAlpha(Object obj, float f) {
        Object objectField;
        try {
            objectField = XposedHelpers.getObjectField(obj, "mToolBox");
        } catch (Throwable th) {
            objectField = null;
        }
        if (!(objectField instanceof View)) {
            return;
        }
        try {
            callStatic("com.hihonor.keyguard.view.KgViewUtils", "setViewAlpha", objectField, Float.valueOf(f));
        } catch (Throwable th2) {
            ((View) objectField).setAlpha(f);
        }
    }

    private static Object callStatic(String str, String str2, Object... objArr) {
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass(str, sCl), str2, objArr);
    }

    private static void hookToolBoxGates() {
        hook("KeyguardCfg.isToolBoxDisabled", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.5
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_KG_CFG, PanelHooks.sCl, "isToolBoxDisabled", new Object[]{new XC_MethodHook() { // from class: com.chaosz.lockpanel.PanelHooks.5.1
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        if (Boolean.TRUE.equals(PanelHooks.wantPanel())) {
                            methodHookParam.setResult(Boolean.FALSE);
                        }
                    }
                }});
            }
        });
        hook("DisabledFeatureUtils.isHideQuickView", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.6
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_DISABLED, PanelHooks.sCl, "isHideQuickView", new Object[]{new XC_MethodHook() { // from class: com.chaosz.lockpanel.PanelHooks.6.1
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        if (Boolean.TRUE.equals(PanelHooks.wantPanel())) {
                            methodHookParam.setResult(Boolean.FALSE);
                        }
                    }
                }});
            }
        });
    }

    private static void hookToolBoxActions() {
        hook("ToolBoxView.startActivityById", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.7
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_TOOLBOX, PanelHooks.sCl, "startActivityById", new Object[]{Integer.TYPE, new XC_MethodHook() { // from class: com.chaosz.lockpanel.PanelHooks.7.1
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        if (PanelHooks.sDispatchingAction) {
                            return;
                        }
                        if (PanelHooks.handleSlotAction(methodHookParam.thisObject, ((Integer) methodHookParam.args[0]).intValue())) {
                            methodHookParam.setResult((Object) null);
                        }
                    }
                }});
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean handleSlotAction(Object obj, int i) {
        String str;
        Object objCallStatic;
        if (!(obj instanceof View) || i < 0 || i >= Cfg.ACTION_KEYS.length) {
            return false;
        }
        Context context = ((View) obj).getContext();
        Cfg.initLazy(context);
        Cfg.ensureLoaded("slot");
        if (!active()) {
            return false;
        }
        String str2 = Cfg.str(Cfg.ACTION_KEYS[i], "");
        if (str2.isEmpty()) {
            return false;
        }
        if (i == 2 && !Cfg.ACT_FLASHLIGHT.equals(str2)) {
            cancelFlashlightMonitor();
        }
        if (str2.startsWith(Cfg.PREFIX_BUILTIN)) {
            try {
                int i2 = Integer.parseInt(str2.substring(Cfg.PREFIX_BUILTIN.length()).trim());
                if (i2 < 0 || i2 > MODE_BROWSE || i2 == i) {
                    return false;
                }
                sDispatchingAction = true;
                try {
                    XposedHelpers.callMethod(obj, "startActivityById", new Object[]{Integer.valueOf(i2)});
                    Log.i("slot " + (i + 1) + " -> builtin:" + i2);
                    sDispatchingAction = false;
                    return true;
                } catch (Throwable th) {
                    try {
                        Log.w("builtin action failed: " + th);
                        return false;
                    } finally {
                        sDispatchingAction = false;
                    }
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (Cfg.ACT_CAMERA.equals(str2)) {
            if (!checkAndRecordClick(obj, "com.hihonor.camera", 1000L)) {
                return true;
            }
            vibrateClick();
            try {
                callStatic(CLS_KG_CFG, "setCameraExists", Boolean.TRUE);
                Object objCallStatic2 = callStatic("com.hihonor.keyguard.HnKeyguardUpdateMonitorImpl", "getInstance", new Object[0]);
                if (objCallStatic2 != null) {
                    Integer num = 1;
                    if (num.equals(XposedHelpers.callMethod(objCallStatic2, "transitionToCamera", new Object[0])) && (objCallStatic = callStatic("com.hihonor.keyguard.inf.HwKeyguardPolicy", "getInst", new Object[0])) != null) {
                        XposedHelpers.callMethod(objCallStatic, "dismiss", new Object[0]);
                    }
                }
            } catch (Throwable th2) {
                Log.w("camera action failed: " + th2);
            }
            dismissPanel(obj);
            Log.i("slot " + (i + 1) + " -> camera");
            return true;
        }
        String str3 = Cfg.PREFIX_APP;
        if (!str2.startsWith(Cfg.PREFIX_APP) && !str2.startsWith(Cfg.PREFIX_ACT)) {
            return false;
        }
        boolean zStartsWith = str2.startsWith(Cfg.PREFIX_APP);
        String strSubstring = str2.substring(zStartsWith ? Cfg.PREFIX_APP.length() : Cfg.PREFIX_ACT.length());
        int iIndexOf = strSubstring.indexOf(9);
        if (iIndexOf >= 0) {
            strSubstring = strSubstring.substring(0, iIndexOf);
        }
        String strTrim = strSubstring.trim();
        if (strTrim.isEmpty()) {
            return false;
        }
        int iIndexOf2 = strTrim.indexOf(47);
        if (iIndexOf2 >= 0) {
            String strTrim2 = strTrim.substring(0, iIndexOf2).trim();
            String strTrim3 = strTrim.substring(iIndexOf2 + 1).trim();
            if (!strTrim3.startsWith(".")) {
                str = strTrim3;
                strTrim = strTrim2;
            } else {
                str = strTrim2 + strTrim3;
                strTrim = strTrim2;
            }
        } else {
            str = "";
        }
        if (strTrim.isEmpty()) {
            return false;
        }
        if (!checkAndRecordClick(obj, strTrim, 1000L)) {
            return true;
        }
        launchCustomComponent(obj, context, strTrim, str, zStartsWith);
        StringBuilder sbAppend = new StringBuilder().append("slot ").append(i + 1).append(" -> ");
        if (!zStartsWith) {
            str3 = Cfg.PREFIX_ACT;
        }
        Log.i(sbAppend.append(str3).append(strTrim).append(str.isEmpty() ? "" : "/" + str).toString());
        return true;
    }

    private static boolean checkAndRecordClick(Object obj, String str, long j) {
        try {
            long jUptimeMillis = SystemClock.uptimeMillis();
            long longField = XposedHelpers.getLongField(obj, "mNextClickTime");
            Object objectField = XposedHelpers.getObjectField(obj, "mLastPackageName");
            boolean zEqualsIgnoreCase = "com.hihonor.flashlight".equalsIgnoreCase(objectField instanceof String ? (String) objectField : "");
            boolean zEqualsIgnoreCase2 = "com.hihonor.flashlight".equalsIgnoreCase(str);
            if ((!zEqualsIgnoreCase || zEqualsIgnoreCase2) && jUptimeMillis < longField) {
                return false;
            }
            XposedHelpers.setLongField(obj, "mNextClickTime", jUptimeMillis + j);
            XposedHelpers.setObjectField(obj, "mLastPackageName", str);
            return true;
        } catch (Throwable th) {
            return true;
        }
    }

    private static void vibrateClick() {
        try {
            Object objCallStatic = callStatic("com.hihonor.systemui.magic.HwAbsVibrateEx", "getVibrator", new Object[0]);
            if (objCallStatic != null) {
                XposedHelpers.callMethod(objCallStatic, "vibrate", new Object[]{"haptic.lockscreen.unlock_click", Boolean.FALSE});
            }
        } catch (Throwable th) {
        }
    }

    private static void cancelFlashlightMonitor() {
        try {
            Object objCallStatic = callStatic("com.hihonor.keyguard.monitor.StateMonitor", "getInst", new Object[0]);
            if (objCallStatic != null) {
                XposedHelpers.callMethod(objCallStatic, "cancelEvent", new Object[]{511});
            }
        } catch (Throwable th) {
        }
    }

    private static void dismissPanel(Object obj) {
        try {
            XposedHelpers.callMethod(obj, "dimissInfoCenter", new Object[0]);
        } catch (Throwable th) {
        }
    }

    private static void launchCustomComponent(Object obj, Context context, String str, String str2, boolean z) {
        Intent launchIntentForPackage;
        vibrateClick();
        if (!str2.isEmpty()) {
            launchIntentForPackage = new Intent();
            if (z) {
                launchIntentForPackage.setAction("android.intent.action.MAIN");
                launchIntentForPackage.addCategory("android.intent.category.LAUNCHER");
            }
            launchIntentForPackage.setComponent(new ComponentName(str, str2));
        } else if (context != null) {
            try {
                launchIntentForPackage = context.getPackageManager().getLaunchIntentForPackage(str);
            } catch (Throwable th) {
                launchIntentForPackage = null;
            }
        } else {
            launchIntentForPackage = null;
        }
        if (launchIntentForPackage == null) {
            launchIntentForPackage = new Intent("android.intent.action.MAIN");
            launchIntentForPackage.addCategory("android.intent.category.LAUNCHER");
            launchIntentForPackage.setPackage(str);
        }
        launchIntentForPackage.addFlags(337641472);
        try {
            Object objCallStatic = callStatic("com.hihonor.keyguard.inf.HwKeyguardPolicy", "getInst", new Object[0]);
            if (objCallStatic != null) {
                XposedHelpers.callMethod(objCallStatic, "startActivity", new Object[]{launchIntentForPackage, Boolean.TRUE});
                dismissPanel(obj);
                return;
            }
        } catch (Throwable th2) {
            Log.w("HwKeyguardPolicy.startActivity failed: " + th2);
        }
        dismissPanel(obj);
        if (context != null) {
            try {
                context.startActivity(launchIntentForPackage);
            } catch (Throwable th3) {
                Log.w("ctx.startActivity failed: " + th3);
            }
        }
    }

    private static void hookToolBoxIcons() {
        final XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.chaosz.lockpanel.PanelHooks.8
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                PanelHooks.rememberToolBox(methodHookParam.thisObject);
                PanelHooks.applyIcons(methodHookParam.thisObject, false);
            }
        };
        hook("ToolBoxView.onFinishInflate", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.9
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_TOOLBOX, PanelHooks.sCl, "onFinishInflate", new Object[]{xC_MethodHook});
            }
        });
        hook("ToolBoxView.refreshShortcutView5", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.10
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_TOOLBOX, PanelHooks.sCl, "refreshShortcutView5", new Object[]{xC_MethodHook});
            }
        });
        hook("ToolBoxView.updateFlashlightViewInner", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.11
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_TOOLBOX, PanelHooks.sCl, "-$$Nest$mupdateFlashlightViewInner", new Object[]{PanelHooks.CLS_TOOLBOX, List.class, new XC_MethodHook() { // from class: com.chaosz.lockpanel.PanelHooks.11.1
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                        if (methodHookParam.args.length > 0) {
                            PanelHooks.rememberToolBox(methodHookParam.args[0]);
                            PanelHooks.applyIcons(methodHookParam.args[0], false);
                        }
                    }
                }});
            }
        });
    }

    /* JADX WARN: Code duplicated, block: B:47:0x009b  */
    static void applyIcons(Object obj, boolean z) {
        View view;
        Context context;
        boolean z2;
        boolean z3;
        int iShortcutId;
        boolean[] zArr;
        if (!(obj instanceof View) || (context = (view = (View) obj).getContext()) == null) {
            return;
        }
        Cfg.initLazy(context);
        Cfg.ensureLoaded("panel");
        boolean zActive = active();
        boolean[] zArr2 = sCustomizedSlots.get(obj);
        int iFgColor = fgColor(obj, context);
        boolean zIsFlashlightOn = isFlashlightOn(obj);
        if (zActive) {
            ensureSlotVisibility(obj, context);
        }
        for (int i = 0; i < Cfg.ICON_KEYS.length; i++) {
            String str = zActive ? Cfg.str(Cfg.ICON_KEYS[i], "") : "";
            String str2 = zActive ? Cfg.str(Cfg.ACTION_KEYS[i], "") : "";
            boolean z4 = !str.isEmpty();
            if (!Cfg.ACT_FLASHLIGHT.equals(str2) && (i != 2 || !str2.isEmpty())) {
                z2 = false;
            } else {
                z2 = true;
            }
            if (!z4 && ((i != 2 || z2) && (i == 2 || !z2))) {
                z3 = false;
            } else {
                z3 = true;
            }
            if ((z3 || (z && zArr2 != null && zArr2[i])) && (iShortcutId = shortcutId(context, i + 1)) != 0) {
                View viewFindViewById = view.findViewById(iShortcutId);
                if (viewFindViewById instanceof ImageView) {
                    ImageView imageView = (ImageView) viewFindViewById;
                    if (z3) {
                        if (zArr2 == null) {
                            zArr2 = new boolean[Cfg.ICON_KEYS.length];
                            sCustomizedSlots.put(obj, zArr2);
                        }
                        zArr2[i] = true;
                        zArr = zArr2;
                    } else {
                        zArr2[i] = false;
                        zArr = zArr2;
                    }
                    applySlotView(obj, view, imageView, context, i, z3, z4 ? str : null, z2, iFgColor, zIsFlashlightOn);
                    zArr2 = zArr;
                }
            }
        }
    }

    /* JADX WARN: Code duplicated, block: B:51:0x00ae  */
    private static void ensureSlotVisibility(Object obj, Context context) {
        View[] viewArr;
        int dimension;
        String str;
        try {
            Object objectField = XposedHelpers.getObjectField(obj, "mShortCutLayout");
            if (!(objectField instanceof View[])) {
                return;
            }
            View[] viewArr2 = (View[]) objectField;
            if (viewArr2.length != 5) {
                return;
            }
            boolean z = false;
            for (int i = 0; i < 5; i++) {
                if (!Cfg.str(Cfg.ACTION_KEYS[i], "").isEmpty() && viewArr2[i] != null && viewArr2[i].getVisibility() != 0) {
                    viewArr2[i].setVisibility(0);
                    z = true;
                }
            }
            if (!z) {
                return;
            }
            boolean booleanField = XposedHelpers.getBooleanField(obj, "mIsNeedChangeOrder");
            if (booleanField) {
                viewArr2 = (View[]) XposedHelpers.getObjectField(obj, "mOrderShortCutLayout");
            }
            if (booleanField) {
                viewArr = (View[]) XposedHelpers.getObjectField(obj, "mOrderIconExpandViews");
            } else {
                viewArr = (View[]) XposedHelpers.getObjectField(obj, "mIconExpandViews");
            }
            if (viewArr2 != null && viewArr != null && viewArr2.length == 5) {
                int i2 = 0;
                int i3 = 0;
                for (int i4 = MODE_BROWSE; i4 >= 0; i4--) {
                    if (viewArr2[i4] != null && viewArr2[i4].getVisibility() == 0) {
                        i2++;
                        i3 = i4;
                    }
                }
                if (i2 < 5 && context != null) {
                    if (i2 > 3) {
                        str = "toolbox_four_pending_width";
                    } else {
                        str = "toolbox_three_pending_width";
                    }
                    int identifier = context.getResources().getIdentifier(str, "dimen", PKG_SYSTEMUI);
                    if (identifier != 0) {
                        dimension = (int) context.getResources().getDimension(identifier);
                    } else {
                        dimension = 0;
                    }
                } else {
                    dimension = 0;
                }
                for (int i5 = 0; i5 < viewArr.length && i5 < MODE_BROWSE; i5++) {
                    View view = viewArr[i5];
                    if (view != null) {
                        int i6 = 8;
                        if (i5 < i3 || i2 == 5) {
                            view.setVisibility(8);
                        } else {
                            int i7 = i5 + 1;
                            if (viewArr2[i7] != null && viewArr2[i7].getVisibility() == 0) {
                                i6 = 0;
                            }
                            view.setVisibility(i6);
                            if (view.getLayoutParams() != null) {
                                view.getLayoutParams().width = dimension;
                            }
                        }
                    }
                }
            }
        } catch (Throwable th) {
        }
    }

    private static void applySlotView(Object obj, View view, ImageView imageView, Context context, int i, boolean z, String str, boolean z2, int i2, boolean z3) {
        Drawable drawable;
        String str2;
        if (i == 2 && !z) {
            if (!sRestoring) {
                try {
                    sRestoring = true;
                    XposedHelpers.callMethod(obj, "updateFlashlightView$1", new Object[0]);
                } catch (Throwable th) {
                    try {
                        Log.w("restore flashlight failed: " + th);
                    } finally {
                        sRestoring = false;
                    }
                }
                return;
            }
            return;
        }
        if (i == 2) {
            try {
                Object objectField = XposedHelpers.getObjectField(obj, "mUpdateAvailableState");
                if (objectField instanceof Runnable) {
                    view.removeCallbacks((Runnable) objectField);
                }
            } catch (Throwable th2) {
            }
        }
        if (i == MODE_BROWSE && str == null) {
            if (sRestoring) {
                return;
            }
            try {
                sRestoring = true;
                XposedHelpers.callMethod(obj, "refreshShortcutView5", new Object[0]);
            } catch (Throwable th3) {
                try {
                    Log.w("refreshShortcutView5 failed: " + th3);
                } catch (Throwable th4) {
                    sRestoring = false;
                    throw th4;
                }
            }
            sRestoring = false;
            Drawable drawable2 = imageView.getDrawable();
            drawable = drawable2 != null ? drawable2.mutate() : null;
            str2 = "slot5_default";
        } else {
            if (str == null) {
                str = DEFAULT_ICONS[i];
            }
            drawable = Icons.drawable(context, str);
            str2 = str;
        }
        if (drawable == null) {
            Log.w("icon not found: " + str2);
            return;
        }
        boolean z4 = z2 && z3;
        ImageView imageViewFindCircleView = findCircleView(view, context, i + 1);
        if (z4) {
            if (imageViewFindCircleView != null) {
                Drawable drawableSysDrawable = sysDrawable(context, "ic_circle_pressed");
                if (drawableSysDrawable != null) {
                    imageViewFindCircleView.setImageDrawable(drawableSysDrawable);
                }
                imageViewFindCircleView.setAlpha(0.01f);
                imageViewFindCircleView.setVisibility(0);
                paint(imageViewFindCircleView, i2);
            }
            Drawable drawableFlashlightOnDrawable = flashlightOnDrawable(context, drawable, str2);
            if (drawableFlashlightOnDrawable != null) {
                drawable = drawableFlashlightOnDrawable;
            }
            imageView.setImageDrawable(drawable);
            paint(imageView, i2);
            return;
        }
        if (imageViewFindCircleView != null) {
            Drawable drawableSysDrawable2 = sysDrawable(context, "ic_circle");
            if (drawableSysDrawable2 != null) {
                imageViewFindCircleView.setImageDrawable(drawableSysDrawable2);
            }
            imageViewFindCircleView.setAlpha(1.0f);
            imageViewFindCircleView.setVisibility(0);
            paint(imageViewFindCircleView, i2);
        }
        imageView.setImageDrawable(drawable);
        paint(imageView, i2);
    }

    private static Drawable flashlightOnDrawable(Context context, Drawable drawable, String str) {
        Drawable drawableSysDrawable;
        if (str != null && str.startsWith("icsvg_public_flashlight") && (drawableSysDrawable = sysDrawable(context, "ic_unlock_flashlight_on")) != null) {
            return drawableSysDrawable;
        }
        return makeCutoutDrawable(context, drawable, str);
    }

    private static Drawable makeCutoutDrawable(Context context, Drawable drawable, String str) {
        Bitmap bitmap;
        Drawable drawableMutate;
        if (context == null || drawable == null) {
            return drawable;
        }
        try {
            DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
            float f = (displayMetrics == null || displayMetrics.density <= 0.0f) ? 3.0f : displayMetrics.density;
            int iMax = Math.max(50, Math.round(50.0f * f));
            int iMax2 = Math.max(24, Math.round(f * 24.0f));
            StringBuilder sb = new StringBuilder();
            if (str == null) {
                str = "anon";
            }
            String string = sb.append(str).append("@").append(iMax).toString();
            synchronized (sCutoutCache) {
                bitmap = sCutoutCache.get(string);
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                return new BitmapDrawable(context.getResources(), bitmap);
            }
            Bitmap bitmapCreateBitmap = Bitmap.createBitmap(iMax2, iMax2, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmapCreateBitmap);
            if (drawable.getConstantState() != null) {
                drawableMutate = drawable.getConstantState().newDrawable(context.getResources()).mutate();
            } else {
                drawableMutate = drawable.mutate();
            }
            drawableMutate.setBounds(0, 0, iMax2, iMax2);
            drawableMutate.setColorFilter(new PorterDuffColorFilter(-1, PorterDuff.Mode.SRC_IN));
            drawableMutate.draw(canvas);
            drawableMutate.clearColorFilter();
            Bitmap bitmapCreateBitmap2 = Bitmap.createBitmap(iMax, iMax, Bitmap.Config.ARGB_8888);
            Canvas canvas2 = new Canvas(bitmapCreateBitmap2);
            Paint paint = new Paint(1);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(-1);
            float f2 = iMax / 2.0f;
            canvas2.drawCircle(f2, f2, f2, paint);
            Paint paint2 = new Paint(3);
            paint2.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            float f3 = (iMax - iMax2) / 2.0f;
            canvas2.drawBitmap(bitmapCreateBitmap, f3, f3, paint2);
            bitmapCreateBitmap.recycle();
            synchronized (sCutoutCache) {
                if (sCutoutCache.size() >= 16) {
                    sCutoutCache.clear();
                }
                sCutoutCache.put(string, bitmapCreateBitmap2);
            }
            return new BitmapDrawable(context.getResources(), bitmapCreateBitmap2);
        } catch (Throwable th) {
            Log.w("makeCutoutDrawable failed: " + th);
            return drawable;
        }
    }

    private static Drawable sysDrawable(Context context, String str) {
        Drawable drawable;
        if (context == null || str == null || str.isEmpty()) {
            return null;
        }
        try {
            Resources resources = context.getResources();
            int identifier = resources.getIdentifier(str, "drawable", PKG_SYSTEMUI);
            if (identifier == 0 || (drawable = resources.getDrawable(identifier, context.getTheme())) == null) {
                return null;
            }
            return drawable.mutate();
        } catch (Throwable th) {
            return null;
        }
    }

    private static boolean isFlashlightOn(Object obj) {
        Object objCallStatic = callStatic("com.android.systemui.plugins.Dependency", "get", XposedHelpers.findClass("com.android.systemui.statusbar.policy.FlashlightController", sCl));
        if (objCallStatic != null) {
            Object objCallMethod = XposedHelpers.callMethod(objCallStatic, "isEnabled", new Object[0]);
            if (objCallMethod instanceof Boolean) {
                return ((Boolean) objCallMethod).booleanValue();
            }
        }
        try {
            return XposedHelpers.getBooleanField(obj, "mIsEnabled");
        } catch (Throwable th) {
            return false;
        }
    }

    private static void paint(ImageView imageView, int i) {
        if (imageView == null) {
            return;
        }
        try {
            imageView.clearColorFilter();
            imageView.setColorFilter(i);
        } catch (Throwable th) {
            Log.w("paint failed: " + th);
        }
    }

    /* JADX WARN: Code duplicated, block: B:9:0x002e  */
    private static int fgColor(Object obj, Context context) {
        int iIntValue;
        try {
            Object objCallStatic = callStatic("com.hihonor.keyguard.view.effect.ColorPickManager", "getCurrentPairColor", context, 8);
            if (objCallStatic != null) {
                Object objCallMethod = XposedHelpers.callMethod(objCallStatic, "getFgColor", new Object[0]);
                if (objCallMethod instanceof Integer) {
                    iIntValue = ((Integer) objCallMethod).intValue();
                } else {
                    iIntValue = 0;
                }
            } else {
                iIntValue = 0;
            }
        } catch (Throwable th) {
            iIntValue = 0;
        }
        try {
            Object objCallStatic2 = callStatic("com.hihonor.keyguard.MusicKeyguardController", "getInstance", new Object[0]);
            if (objCallStatic2 != null && Boolean.TRUE.equals(XposedHelpers.callMethod(objCallStatic2, "isInMusicKeyguardMode", new Object[0]))) {
                iIntValue = -1;
            }
        } catch (Throwable th2) {
        }
        try {
            Object objCallStatic3 = callStatic("com.hihonor.keyguard.wallpaper.DimAmountManager", "getInstance", context);
            if (objCallStatic3 != null && Boolean.TRUE.equals(XposedHelpers.callMethod(objCallStatic3, "isInDimMode", new Object[0]))) {
                iIntValue = XposedHelpers.getBooleanField(obj, "mLightText") ? -1 : -16777216;
            }
        } catch (Throwable th3) {
        }
        if (iIntValue != 0) {
            return iIntValue;
        }
        return -1315861;
    }

    private static int shortcutId(Context context, int i) {
        if (i < 1 || i > 5) {
            return 0;
        }
        int i2 = i - 1;
        int i3 = sShortcutIds[i2];
        if (i3 != 0) {
            return i3;
        }
        try {
            int identifier = context.getResources().getIdentifier("info_center_shortcut_" + i, "id", PKG_SYSTEMUI);
            if (identifier != 0) {
                sShortcutIds[i2] = identifier;
            }
            return identifier;
        } catch (Throwable th) {
            return 0;
        }
    }

    private static ImageView findCircleView(View view, Context context, int i) {
        if (view == null || context == null || i < 1 || i > 5) {
            return null;
        }
        int i2 = i - 1;
        int identifier = sCircleIds[i2];
        if (identifier == 0) {
            try {
                identifier = context.getResources().getIdentifier("ic_circle_shortcut_" + i, "id", PKG_SYSTEMUI);
                if (identifier != 0) {
                    sCircleIds[i2] = identifier;
                }
            } catch (Throwable th) {
            }
        }
        if (identifier == 0) {
            return null;
        }
        View viewFindViewById = view.findViewById(identifier);
        if (viewFindViewById instanceof ImageView) {
            return (ImageView) viewFindViewById;
        }
        return null;
    }

    private static void hookInfoCenter() {
        final XC_MethodHook xC_MethodHook = new XC_MethodHook() { // from class: com.chaosz.lockpanel.PanelHooks.12
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                if (methodHookParam.thisObject instanceof View) {
                    PanelHooks.remember(methodHookParam.thisObject);
                    PanelHooks.apply(methodHookParam.thisObject);
                }
            }
        };
        hook("InfoCenterView.onThemeStlyeChange", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.13
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_INFO, PanelHooks.sCl, "onThemeStlyeChange", new Object[]{xC_MethodHook});
            }
        });
        hook("InfoCenterView.changeChildrenViewState", new Runnable() { // from class: com.chaosz.lockpanel.PanelHooks.14
            @Override // java.lang.Runnable
            public void run() {
                XposedHelpers.findAndHookMethod(PanelHooks.CLS_INFO, PanelHooks.sCl, "changeChildrenViewState", new Object[]{Boolean.TYPE, xC_MethodHook});
            }
        });
    }

    static void apply(Object obj) {
        if (!(obj instanceof View)) {
            return;
        }
        View view = (View) obj;
        Cfg.initLazy(view.getContext());
        Cfg.ensureLoaded("panel");
        boolean z = active() && Cfg.isOn(Cfg.K_HIDE_STYLE);
        try {
            View viewFindViewById = view.findViewById(idChangeStyle(view.getContext()));
            if (viewFindViewById == null) {
                return;
            }
            if (z) {
                if (viewFindViewById.getVisibility() != 8) {
                    sHidden.put(viewFindViewById, Integer.valueOf(viewFindViewById.getVisibility()));
                    viewFindViewById.setVisibility(8);
                    return;
                }
                return;
            }
            Integer numRemove = sHidden.remove(viewFindViewById);
            if (numRemove != null) {
                viewFindViewById.setVisibility(numRemove.intValue());
            }
        } catch (Throwable th) {
            Log.w("apply failed: " + th);
        }
    }

    private static int idChangeStyle(Context context) {
        if (sIdChangeStyle != 0) {
            return sIdChangeStyle;
        }
        int identifier = 0;
        if (context != null) {
            try {
                identifier = context.getResources().getIdentifier("change_lockscreen_style", "id", PKG_SYSTEMUI);
            } catch (Throwable th) {
            }
        }
        if (identifier == 0) {
            identifier = ID_CHANGE_STYLE_FALLBACK;
        }
        sIdChangeStyle = identifier;
        return identifier;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void remember(Object obj) {
        rememberIn(sInfoCenters, obj, 8);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void rememberToolBox(Object obj) {
        rememberIn(sToolBoxes, obj, MODE_BROWSE);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void rememberMagazine(Object obj) {
        rememberIn(sMagazines, obj, MODE_BROWSE);
    }

    private static void rememberIn(List<Object> list, Object obj, int i) {
        if (obj == null) {
            return;
        }
        synchronized (list) {
            Iterator<Object> it = list.iterator();
            while (it.hasNext()) {
                if (it.next() == obj) {
                    return;
                }
            }
            list.add(obj);
            while (list.size() > i) {
                list.remove(0);
            }
        }
    }

    static void onCfgChanged() {
        ArrayList arrayList;
        ArrayList arrayList2;
        Log.i("cfg applied: " + Cfg.describe());
        resetMagazines();
        synchronized (sInfoCenters) {
            arrayList = new ArrayList(sInfoCenters);
        }
        Iterator it = arrayList.iterator();
        while (it.hasNext()) {
            apply(it.next());
        }
        synchronized (sToolBoxes) {
            arrayList2 = new ArrayList(sToolBoxes);
        }
        Iterator it2 = arrayList2.iterator();
        while (it2.hasNext()) {
            applyIcons(it2.next(), true);
        }
    }

    private static void resetMagazines() {
        ArrayList arrayList;
        synchronized (sMagazines) {
            arrayList = new ArrayList(sMagazines);
        }
        for (Object obj : arrayList) {
            try {
                XposedHelpers.setIntField(obj, "mTouchMode", 0);
                setToolBoxAlpha(obj, 0.0f);
                XposedHelpers.callMethod(obj, "setLiftReset", new Object[]{Boolean.TRUE});
            } catch (Throwable th) {
                Log.w("reset magazine failed: " + th);
            }
        }
    }

    private static void hook(String str, Runnable runnable) {
        try {
            runnable.run();
            Log.i("hooked " + str);
        } catch (Throwable th) {
            Log.w("skip " + str + ": " + th);
        }
    }
}
