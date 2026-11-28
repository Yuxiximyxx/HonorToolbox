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
 * from_tag 不是 "LauncherScroll"（正常点开快服务）、配置
 * 三路皆空且缓存未建、有界重试环多枪 Provider 仍失败
 * 且 onResume 二审仍失败（v1.10.1 兜底，见 ConfigStore
 * 类注）、总开关关、目标未配置、目标启动失败。
 *
 * 闲置首击二轮加固（v1.10.1）：v1.8.2 的种子读在
 * handleLoadPackage 后 +1s/+4s 才跑，手势冷启进程时
 * onCreate 决策（进程启动后约 0.2~0.9s）永远跑在预热
 * 前面；单枪 400ms 重试又不足以焐热 Provider。本版三
 * 处补强：①hook 宿主 Application.onCreate（必在
 * LauncherActivity.onCreate 之前）拿到真 Context 立
 * 刻积极预热缓存（0/250/700/1500ms 多枪）；②决策点
 * 改有界重试环（预算 1.6s、多枪、逐枪日志）；③读空
 * 失败时不立刻定案放行，记 pending、由 onResume 二
 * 审一次（预算减半 0.8s）——二审只对「配置读空」触
 * 发，开关关/未配置这类已读到配置的放行不二审。
 *
 * 宿主本地缓存兜底（v1.10.3）：用户导出日志定案快
 * 服务进程实例呈双稳态——约半数实例从启动起
 * Provider 每次读取都立即抛 IllegalArgumentException，
 * warm/seed/决策多枪/二审全灭且终身不恢复，预热与
 * 重试路线救不回。故决策改由 ConfigStore 的宿主本
 * 地缓存兜底：任一通道读成功顺手把配置落进宿主私
 * 有存储，实时读取全灭时用缓存配置照常走完决策
 * （本文件日志记 config from local cache、来源记
 * localcache），warm 阶段命中缓存即灌进进程内缓
 * 存。详见 ConfigStore 类注。
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

        /** 决策点有界重试环预算（v1.10.1）：远低于 5s ANR 线。 */
        private const val COLD_BUDGET_MS = 1_600L

        /** 二审重试环预算（v1.10.1）：面板可能已露面，减半。 */
        private const val COLD_BUDGET_DEFERRED_MS = 800L

        /** pending 二审有效期（毫秒），过期丢弃不补判。 */
        private const val PENDING_TTL_MS = 5_000L

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

    /** Application.onCreate 预热每进程只武装一次（v1.10.1）。 */
    private val warmArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 待二审的手势判定（v1.10.1）：读空放行时记下、二审后清。 */
    private data class PendingGesture(val intent: Intent?, val atMs: Long)

    @Volatile
    private var pendingGesture: PendingGesture? = null

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
            // v1.10.1 预热提前：宿主 Application.onCreate 必
            // 在 LauncherActivity.onCreate 之前，且是进程里
            // 第一个能拿到真 Context 的点——在这里就起积极
            // 预热（见 warmConfigCache），把缓存建立时点从
            // v1.8.2 的「安装后 +1s 种子」提前到决策之前。
            // 挂基类 Application.onCreate：子类覆写时必调
            // super，本回调在其返回后触发；每进程只武装一次。
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.Application", lpparam.classLoader, "onCreate",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val app = param.thisObject as? Context ?: return
                                if (warmArmed.compareAndSet(false, true)) {
                                    warmConfigCache(app)
                                }
                            } catch (t: Throwable) {
                                diag("Application.onCreate warm error: ${t.javaClass.name}: ${t.message}")
                            }
                        }
                    },
                )
            } catch (t: Throwable) {
                diag("Application.onCreate hook FAILED: ${t.javaClass.name}: ${t.message}")
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
            // v1.10.1 二审：onCreate/onNewIntent 因配置读空
            // 放行时记下的 pending，在 onResume 时重跑一次
            // 判定（此时预热与重试环多半已把缓存建好）。
            // 只审一次、过期（>5s）丢弃；二审自身不再记
            // pending，不循环。
            XposedHelpers.findAndHookMethod(
                clazz, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as? Activity ?: return
                            val pending = pendingGesture ?: return
                            if (System.currentTimeMillis() - pending.atMs > PENDING_TTL_MS) {
                                pendingGesture = null
                                diag("onResume deferred decision expired -> drop")
                                return
                            }
                            pendingGesture = null
                            diag("onResume deferred decision: retrying gesture decision")
                            tryRedirect(
                                activity, pending.intent,
                                "onResume(deferred)",
                                coldBudgetMs = COLD_BUDGET_DEFERRED_MS,
                                allowDefer = false,
                            )
                        } catch (t: Throwable) {
                            diag("onResume hook error: ${t.javaClass.name}: ${t.message}")
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
            // v1.8.2 种子读：handleLoadPackage 时刻宿主
            // Application 多半还没就绪、自检读恒走文件通道
            // 落空，缓存建不起来，新进程首击只能靠决策点
            // 重试硬扛。这里起后台线程在约 1s、3s 各补读
            // 一次（此时 currentApplication 应已就绪），把
            // 缓存提前预热好，首击直接命中缓存/正常通道。
            // 取不到 Context 或读不到只 diag 留痕，不报错。
            seedConfigCache()
        } catch (t: Throwable) {
            diag("install FAILED: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 积极预热（v1.10.1）：宿主 Application 就绪即起后
     * 台线程，按 0/250/700/1500ms 节奏多枪 [ConfigStore]
     * 全通道读取（readConfig 成功即刷新进程内缓存），
     * 抢在 LauncherActivity.onCreate 决策点之前把缓存
     * 建好。读到即停（后续枪不再打）；全灭只 diag 留
     * 痕。原 v1.8.2 的 1s/3s 种子读保留作补枪（宿主
     * Application 钩子万一没装上时仍有预热）。
     */
    private fun warmConfigCache(appContext: Context) {
        val worker = Thread {
            val delays = longArrayOf(0L, 250L, 450L, 800L)
            var elapsed = 0L
            for (delayMs in delays) {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (t: Throwable) {
                        return@Thread
                    }
                    elapsed += delayMs
                }
                try {
                    val warmed = ConfigStore.readConfig(appContext)
                    if (warmed != null) {
                        diag(
                            "config warm @~${elapsed}ms: enabled=${warmed.enabled} " +
                                "mode=${warmed.mode} pkg=${warmed.pkg} " +
                                "(source=${ConfigStore.lastReadSource})"
                        )
                        if (ConfigStore.lastReadSource == "localcache") {
                            // v1.10.3：实时通道全灭、靠宿主本地
                            // 缓存把进程内缓存灌好，本进程后续
                            // 手势直接命中缓存。
                            diag(
                                "config from local cache: warmed into " +
                                    "process cache @~${elapsed}ms"
                            )
                        }
                        return@Thread
                    }
                } catch (t: Throwable) {
                    diag("config warm @~${elapsed}ms error: ${t.javaClass.name}: ${t.message}")
                }
            }
            diag(
                "config warm: all attempts unreadable " +
                    "(source=${ConfigStore.lastReadSource})"
            )
        }
        worker.isDaemon = true
        worker.name = "QuickGestureWarm"
        worker.start()
    }

    /**
     * 种子读（v1.8.2）：安装完成后在后台线程里延迟约
     * 1s、3s 各做一次带宿主 Context 的 [ConfigStore]
     * 读取，成功即预热进程内配置缓存（readConfig 内部
     * 收口），让新进程的首个手势不必赌 Provider 冷启动
     * 时序。两次都试：1s 时宿主 Application 可能仍未
     * 就绪，3s 作补枪；已建缓存时重读也只是刷新，无害。
     * 全程异常吞掉，只 diag 记每次结果与来源。
     */
    private fun seedConfigCache() {
        val worker = Thread {
            for (delayMs in longArrayOf(1_000L, 3_000L)) {
                try {
                    Thread.sleep(delayMs)
                } catch (t: Throwable) {
                    return@Thread
                }
                try {
                    val ctx = hostContext()
                    if (ctx == null) {
                        diag("config seed read @${delayMs}ms: no host context yet")
                        continue
                    }
                    val seeded = ConfigStore.readConfig(ctx)
                    diag(
                        if (seeded == null) {
                            "config seed read @${delayMs}ms: unreadable " +
                                "(source=${ConfigStore.lastReadSource})"
                        } else {
                            "config seed read @${delayMs}ms: enabled=${seeded.enabled} " +
                                "mode=${seeded.mode} pkg=${seeded.pkg} " +
                                "act=${seeded.activity} " +
                                "(source=${ConfigStore.lastReadSource})"
                        }
                    )
                } catch (t: Throwable) {
                    diag("config seed read @${delayMs}ms error: ${t.javaClass.name}: ${t.message}")
                }
            }
        }
        worker.isDaemon = true
        worker.name = "QuickGestureSeed"
        worker.start()
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
    private fun tryRedirect(
        activity: Activity,
        intent: Intent?,
        source: String,
        coldBudgetMs: Long = COLD_BUDGET_MS,
        allowDefer: Boolean = true,
    ): Boolean {
        val fromTag = try {
            intent?.getStringExtra(FROM_TAG_KEY)
        } catch (t: Throwable) {
            null
        }
        if (fromTag != FROM_TAG_GESTURE) {
            diag("$source PASS: from_tag=$fromTag (not gesture)")
            return false
        }
        var config = ConfigStore.readConfig(activity)
        if (config == null) {
            // v1.10.1 兜底（原 v1.8.2 单枪重试升级）：三路
            // 皆空且缓存也空，多半是新进程首击撞上 Provider
            // 冷启动——放行前在预算内多枪重读 Provider
            // （逐枪日志，命中时 lastReadSource=
            // provider(retry)）。readConfig 内部已有缓存兜
            // 底，能走到这里说明缓存未建，故重试环是当场
            // 最后一道；再失败且允许二审时记 pending 交
            // onResume 复判，不在本点定案。
            config = ConfigStore.readConfigFromProviderColdStart(
                activity, coldBudgetMs,
            ) { line -> diag("$source $line") }
        }
        if (config != null && ConfigStore.lastReadSource == "localcache") {
            // v1.10.3：实时通道全灭但宿主本地缓存命中，
            // 用缓存配置照常走完下面的改向判定。
            diag(
                "$source config from local cache: enabled=${config.enabled} " +
                    "mode=${config.mode} pkg=${config.pkg} act=${config.activity}"
            )
        }
        if (config == null) {
            if (allowDefer) {
                pendingGesture = PendingGesture(intent, System.currentTimeMillis())
                diag(
                    "$source HIT gesture but config unreadable " +
                        "(source=${ConfigStore.lastReadSource}) " +
                        "-> PASS for now, deferred to onResume"
                )
            } else {
                diag(
                    "$source HIT gesture but config unreadable " +
                        "(source=${ConfigStore.lastReadSource}) -> PASS"
                )
            }
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
