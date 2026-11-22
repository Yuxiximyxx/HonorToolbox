package io.github.yuxiximyxx.honortoolbox.quickgesture

import io.github.yuxiximyxx.honortoolbox.BuildConfig

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * 快服务手势改向（v1.0.0）：把荣耀快服务「从屏幕底角斜上滑」
 * 手势的落点改向到用户在设置里选好的应用/activity。
 *
 * 机制定案（静态分析，证据在 servicecenter-probe/jadx-out）：
 * 手势不是快服务自己检测的——快服务只写 Settings.Global 键
 * service_center_switch_value（2=开/3=关）；底角斜滑由桌面
 * 启动器检测，触发后拉起快服务首页
 * com.hihonor.servicecardcenter.activity.LauncherActivity
 * （singleTask、半透明主题），Intent extra from_tag=
 * "LauncherScroll"，随后启动器经 AIDL IControlCenterGesture
 * 把触摸流传给它的 positionHandler 做跟手动画。
 * LauncherActivity 本体就是判定消费方：其 onNewIntent
 * （jadx 行 680 起）行 683-684 直接 getStringExtra("from_tag")
 * 判 "LauncherScroll"/"LauncherScrollAgreement"，其静态方法
 * L(Intent)（行 190 起）同样以 from_tag=="LauncherScroll"
 * 打 gesture_slide_up_entrance 渠道标记。基类 b
 * （BaseLauncherActivity）onCreate 也读 from_tag，但消费点
 * 分散在协议/埋点逻辑里，没有比 LauncherActivity 本体更早的
 * 统一落点，故 hook 点就定在 LauncherActivity 的
 * onCreate/onNewIntent 两个方法本身。
 *
 * 改向时机取舍（不自创第二套机制）：
 * - onNewIntent（singleTask 复用）：before 拦下，改向成功就
 *   setResult(null) 跳过原处理——实例已建好，跳过一次
 *   onNewIntent 的内部消费无副作用；改向失败则放行原处理。
 * - onCreate（冷启）：after 执行——原 onCreate 先跑完，我方
 *   随即 startActivity 目标并 finish 自己。Activity 在
 *   onCreate 里 finish 就不会走到 resume/首帧绘制，面板不
 *   露面；若在 before 里跳过原 onCreate，FragmentActivity
 *   的初始化没跑完、后续生命周期会把宿主带崩，得不偿失。
 * 提前 finish 后启动器侧的触摸流由 ServiceCenterService 的
 * onUnbind 复位收尾（静态分析预判），残影与否待真机验。
 *
 * 放行口径（任何一条不满足都完整放行快服务原行为）：
 * from_tag 不是 "LauncherScroll"（正常点开快服务）、配置读
 * 不到/解析失败、总开关关、目标未配置、目标启动失败。
 * 诊断写 /sdcard/Download/HonorToolbox/quickgesture.log
 * （v1.6.2 起的主落点；旧路径 /sdcard/Download/
 * quickgesture_probe.log 继续双写）+ LSPosed 日志，前缀
 * [QuickGesture]；全部异常吞掉，不许崩快服务。
 * 作用域：仅 com.hihonor.servicecenter（见 xposedscope）。
 */
class QuickGestureHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[QuickGesture]"
        private const val TARGET_PACKAGE = "com.hihonor.servicecenter"
        private const val LAUNCHER_ACTIVITY =
            "com.hihonor.servicecardcenter.activity.LauncherActivity"
        private const val FROM_TAG_KEY = "from_tag"
        private const val FROM_TAG_GESTURE = "LauncherScroll"
        private val LOG_FILE = File("/sdcard/Download/quickgesture_probe.log")

        // v1.6.2：诊断主落点收进工具箱目录（与 navwake.log /
        // palmpurify.log 同处，方便一并取证）；旧路径继续双写，
        // 老口径找日志的人不受影响。
        private val LOG_FILE_TOOLBOX = File("/sdcard/Download/HonorToolbox/quickgesture.log")
        private val logLock = Any()

        /** 诊断输出：XposedBridge.log + 文件双通道；写文件失败不影响宿主。 */
        fun diag(message: String) {
            try {
                XposedBridge.log("$TAG $message")
            } catch (t: Throwable) {
                // ignore
            }
            val line = "${System.currentTimeMillis()} $TAG $message\n"
            synchronized(logLock) {
                for (file in listOf(LOG_FILE_TOOLBOX, LOG_FILE)) {
                    try {
                        file.parentFile?.mkdirs()
                        file.appendText(line)
                    } catch (t: Throwable) {
                        // 写失败不影响 hook
                    }
                }
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        try {
            val clazz = XposedHelpers.findClassIfExists(
                LAUNCHER_ACTIVITY, lpparam.classLoader,
            )
            if (clazz == null) {
                diag("install FAILED: $LAUNCHER_ACTIVITY not found in $TARGET_PACKAGE")
                return
            }
            XposedHelpers.findAndHookMethod(
                clazz, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as? Activity ?: return
                            tryRedirect(activity, activity.intent, "onCreate")
                        } catch (t: Throwable) {
                            diag("onCreate hook error: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                clazz, "onNewIntent", Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as? Activity ?: return
                            val intent = param.args.getOrNull(0) as? Intent
                            if (tryRedirect(activity, intent, "onNewIntent")) {
                                // 改向已成：跳过原 onNewIntent 的手势消费，
                                // 实例随即被 finish，不再铺快服务面板。
                                param.result = null
                            }
                        } catch (t: Throwable) {
                            diag("onNewIntent hook error: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                },
            )
            diag(
                "installed in $TARGET_PACKAGE " +
                    "(module ${BuildConfig.VERSION_NAME}), hooks: " +
                    "LauncherActivity.onCreate/onNewIntent"
            )
            // v1.6.2：安装时把 hook 侧读到的配置原样记一行——
            // 设置页显示与 hook 实际读到的是否一致，一眼可查
            // （v1.6.1 真机反馈改向不生效，先要分清是没装上、
            // 没读到配置，还是 from_tag 没命中）。
            // v1.6.4：带上配置来源——快服务进程读不到
            // Download 文件，配置改走跨进程通道（见
            // ConfigStore）；v1.6.5 起读序为 Provider →
            // Settings.Global → 文件，来源记
            // provider/global/file/none。安装时刻经
            // ActivityThread 取宿主 Application 作 Context，
            // 取不到就只走文件通道、来源记 file/none。
            val atInstall = ConfigStore.readConfig(hostContext())
            diag(
                if (atInstall == null) {
                    "config at install: unreadable/none " +
                        "(source=${ConfigStore.lastReadSource}) -> PASS until configured"
                } else {
                    "config at install: enabled=${atInstall.enabled} " +
                        "mode=${atInstall.mode} pkg=${atInstall.pkg} " +
                        "act=${atInstall.activity} " +
                        "(source=${ConfigStore.lastReadSource})"
                }
            )
        } catch (t: Throwable) {
            diag("install FAILED: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 安装时刻取宿主进程的 Application 作 Context（读
     * Settings.Global 用）：handleLoadPackage 阶段
     * currentApplication 可能还没就绪，取不到返回 null，
     * 调用方按无 Context 处理（只走文件通道）。
     */
    private fun hostContext(): Context? {
        return try {
            val clazz = Class.forName("android.app.ActivityThread")
            val method = clazz.getMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 一次落点判定。返回 true 表示已改向（目标已拉起、本实例
     * 已 finish）；false 表示放行原行为，本函数不留副作用
     * （目标启动失败时不 finish，快服务照常打开）。
     */
    private fun tryRedirect(activity: Activity, intent: Intent?, source: String): Boolean {
        val fromTag = try {
            intent?.getStringExtra(FROM_TAG_KEY)
        } catch (t: Throwable) {
            null
        }
        if (fromTag != FROM_TAG_GESTURE) {
            diag("$source PASS: from_tag=$fromTag (not gesture)")
            return false
        }
        val config = ConfigStore.readConfig(activity)
        if (config == null) {
            diag(
                "$source HIT gesture but config unreadable " +
                    "(source=${ConfigStore.lastReadSource}) -> PASS"
            )
            return false
        }
        if (!config.enabled) {
            diag(
                "$source HIT gesture, enabled=0 " +
                    "(source=${ConfigStore.lastReadSource}) -> PASS"
            )
            return false
        }
        if (!config.isConfigured) {
            diag(
                "$source HIT gesture, target not configured " +
                    "(mode=${config.mode} pkg=${config.pkg}, " +
                    "source=${ConfigStore.lastReadSource}) -> PASS"
            )
            return false
        }
        val target = buildTargetIntent(activity, config)
        if (target == null) {
            diag(
                "$source HIT gesture, target intent build failed " +
                    "(mode=${config.mode} pkg=${config.pkg} act=${config.activity}) -> PASS"
            )
            return false
        }
        return try {
            activity.startActivity(target)
            diag(
                "$source REDIRECT ok: from_tag=$fromTag -> " +
                    "${target.component?.flattenToString()} (mode=${config.mode})"
            )
            try {
                activity.finish()
            } catch (t: Throwable) {
                diag("$source finish failed: ${t.javaClass.name}: ${t.message}")
            }
            true
        } catch (t: Throwable) {
            diag(
                "$source REDIRECT failed: ${t.javaClass.name}: ${t.message} " +
                    "(target=${target.component?.flattenToString()}) -> PASS"
            )
            false
        }
    }

    /** 按配置构造目标 Intent（都带 FLAG_ACTIVITY_NEW_TASK）。 */
    private fun buildTargetIntent(activity: Activity, config: GestureConfig): Intent? {
        return try {
            when (config.mode) {
                GestureConfig.MODE_ACTIVITY -> {
                    val cls = if (config.activity.startsWith(".")) {
                        config.pkg + config.activity
                    } else {
                        config.activity
                    }
                    Intent().apply {
                        component = ComponentName(config.pkg, cls)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                else -> {
                    val pm = activity.packageManager
                    val launch = pm.getLaunchIntentForPackage(config.pkg)
                    if (launch != null) {
                        launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    } else {
                        // 兜底：按 MAIN+LAUNCHER 查询该包的入口组件
                        val query = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setPackage(config.pkg)
                        }
                        val resolved = pm.queryIntentActivities(query, 0)
                        val first = resolved.firstOrNull()
                        if (first == null) {
                            null
                        } else {
                            Intent().apply {
                                component = ComponentName(
                                    config.pkg, first.activityInfo.name,
                                )
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            diag("buildTargetIntent error: ${t.javaClass.name}: ${t.message}")
            null
        }
    }
}
