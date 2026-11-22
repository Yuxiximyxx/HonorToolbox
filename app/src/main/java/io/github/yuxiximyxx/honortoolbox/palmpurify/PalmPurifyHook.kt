package io.github.yuxiximyxx.honortoolbox.palmpurify

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.yuxiximyxx.honortoolbox.palmpurify.immersive.ActivityHooks
import io.github.yuxiximyxx.honortoolbox.palmpurify.scout.ScoutHooks
import io.github.yuxiximyxx.honortoolbox.palmpurify.slim.SlimHooks
import io.github.yuxiximyxx.honortoolbox.palmpurify.splash.SplashAdHooks
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 掌盟净化入口（v1.6.0 合入荣耀工具箱，原 PalmPurifyModule 的
 * 传统 API 等价物）：由 ToolboxHook 按宿主包名分发到这里。
 *
 * 主进程里给每个 Activity 套透明沉浸。子进程只留一行加载日志，不改窗口。
 * 不挂钩掌盟的业务方法，也不碰账号、战绩、资产、活动的数据。
 *
 * 与其余合入功能同口径：不经 FeatureGate——9 个设置项各有
 * 自己的开关与默认值（缺省即开启），hook 内部现读现判，
 * 落盘语义与独立版一致。
 */
object PalmPurifyHook {

    private val logged = AtomicBoolean(false)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != ModulePrefs.TARGET_PACKAGE) return
        if (!logged.compareAndSet(false, true)) return
        val process = lpparam.processName ?: ModulePrefs.TARGET_PACKAGE
        val version = readHostVersion(lpparam.packageName)
        XposedModule.log(
            Log.INFO,
            ModulePrefs.LOG_TAG,
            "PalmPurify 已加载 package=${lpparam.packageName} process=$process version=$version",
        )
        if (process != ModulePrefs.TARGET_PACKAGE) {
            XposedModule.log(Log.INFO, ModulePrefs.LOG_TAG, "非主进程，不安装界面钩子 process=$process")
            return
        }
        // 旧版独立 App 的配置迁移：先把旧值导出到公共落盘通道，
        // 设置页打开掌盟子页时导入（见 PalmMigration）。失败静默。
        PalmMigration.exportOldConfigFromHook()
        ActivityHooks.install(XposedModule)
        SplashAdHooks.install(XposedModule, lpparam.appInfo, lpparam.classLoader)
        ScoutHooks.install(XposedModule, lpparam.appInfo)
        SlimHooks.install(XposedModule)
    }

    /** 只读已安装包的版本号，用来拼加载日志。 */
    private fun readHostVersion(packageName: String): String {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val context = activityThreadClass.getMethod("getSystemContext").invoke(thread) as Context
            val info = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
            val name = info.versionName ?: "unknown"
            "$name(${info.longVersionCode})"
        } catch (_: Throwable) {
            "unknown"
        }
    }
}
