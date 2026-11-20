package io.github.yuxiximyxx.honortoolbox.shadeclock

import io.github.yuxiximyxx.honortoolbox.BuildConfig

import android.content.res.XResources
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.TypedValue
import android.view.View
import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_InitPackageResources
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * 入口（v2.0.4，方案 B）：通知栏顶部覆盖层 + 通用荣耀锁屏主题渲染。
 *
 * 两块互相独立：
 * 1. 覆盖层（HeaderOverlay）：隐藏头部 Compose 内容、自绘覆盖层按
 *    选中的锁屏主题渲染（数据驱动，/sdcard/Download/HonorToolbox/shadeclock/
 *    themes/<id>/），高度三档（跟随内容/1/3/1/2）；
 *    设置经 stamp.txt 跨进程传递，切换免强停；异常全回退原头部。
 *    v1.1.x 的居中 hook（Lambda9 与 DateAndTime MeasurePolicy 两套）
 *    已整体下线；自制铃铛已于 v2.0.8 按小乐点名删除。
 * 3. 隐藏运营商文字（CarrierHider，v2.0.8）：锁屏/控制中心/通知
 *    中心三处共用的 HwOperatorNameParentView 按开关（默认开，
 *    stamp 第 8 行）压 GONE，与覆盖层开关相互独立。
 * 2. 放大（资源替换）：覆盖层关闭（原系统头部模式）时仍生效——
 *    handleInitPackageResources 里把 SystemUI 的
 *    dimen/notif_center_header_clock_text_size（默认 40dp）、
 *    dimen/notif_center_header_clock_date_size（默认 14dp）替换为
 *    设置里的字号。DimensionReplacement 单位按 API 82 签名
 *    (float, int unit) 传 TypedValue.COMPLEX_UNIT_DIP。
 *
 * 字号设置来自模块 App 的 SharedPreferences（名 settings），经
 * XSharedPreferences 在 SystemUI 进程启动时读取一次（改字号后强停
 * 系统界面生效）；覆盖层设置走 StampStore 的 stamp.txt，不经此路。
 *
 * 诊断写 /sdcard/Download/shadeclock_probe.log（+ LSPosed 日志）。
 * 作用域：com.android.systemui（主体）+ com.hihonor.android.
 * launcher（v2.0.38 起仅只读探针 LauncherProbe，抓桌面宿主
 * 地面真值，零行为改变；见 AndroidManifest 的 xposedscope）。
 */
class ShadeClockHook : IXposedHookLoadPackage, IXposedHookInitPackageResources {

    companion object {
        private const val TAG = "[ShadeClock]"
        private const val TARGET_PACKAGE = "com.android.systemui"
        private const val LAUNCHER_PACKAGE = "com.hihonor.android.launcher"
        private val LOG_FILE = File("/sdcard/Download/shadeclock_probe.log")

        private const val CLOCK_DIMEN = "notif_center_header_clock_text_size"
        private const val DATE_DIMEN = "notif_center_header_clock_date_size"

        private const val MODULE_PACKAGE = "io.github.yuxiximyxx.honortoolbox"
        private const val PREFS_NAME = "settings"
        private const val KEY_CLOCK_SIZE = "clock_size_dp"
        private const val KEY_DATE_SIZE = "date_size_dp"
        private const val DEFAULT_CLOCK_DP = 54
        private const val DEFAULT_DATE_DP = 16
        private const val CLOCK_DP_MIN = 36
        private const val CLOCK_DP_MAX = 68
        private const val DATE_DP_MIN = 12
        private const val DATE_DP_MAX = 24

        private const val HEADER_CLASS_NAME =
            "com.android.systemui.statusbar.notification.header.HnNotificationCenterHeader"

        /** 诊断输出：XposedBridge.log + 文件双通道；写文件失败不影响宿主。 */
        fun diag(message: String) {
            try {
                XposedBridge.log("$TAG $message")
            } catch (t: Throwable) {
                // ignore
            }
            try {
                synchronized(LOG_FILE) {
                    LOG_FILE.appendText("$TAG $message\n")
                }
            } catch (t: Throwable) {
                // 写失败不影响 hook
            }
        }
    }

    /** 一次进程生命周期内读取到的字号快照（覆盖层设置不走这里）。 */
    private data class FontSettings(
        val clockDp: Float,
        val dateDp: Float,
    )

    @Volatile
    private var cachedSettings: FontSettings? = null

    private val dimenDiagDone = AtomicBoolean(false)

    /** 读取字号设置（每进程只读一次并缓存），读不到整体回退默认值。 */
    private fun settings(): FontSettings {
        cachedSettings?.let { return it }
        val loaded = try {
            val prefs = XSharedPreferences(MODULE_PACKAGE, PREFS_NAME)
            val s = FontSettings(
                clockDp = prefs.getInt(KEY_CLOCK_SIZE, DEFAULT_CLOCK_DP)
                    .coerceIn(CLOCK_DP_MIN, CLOCK_DP_MAX).toFloat(),
                dateDp = prefs.getInt(KEY_DATE_SIZE, DEFAULT_DATE_DP)
                    .coerceIn(DATE_DP_MIN, DATE_DP_MAX).toFloat(),
            )
            diag(
                "settings loaded: clock_size_dp=${s.clockDp.roundToInt()}, " +
                    "date_size_dp=${s.dateDp.roundToInt()}"
            )
            s
        } catch (t: Throwable) {
            diag("settings load failed, using defaults: ${t.javaClass.name}: ${t.message}")
            FontSettings(DEFAULT_CLOCK_DP.toFloat(), DEFAULT_DATE_DP.toFloat())
        }
        cachedSettings = loaded
        return loaded
    }

    // ================= 放大：资源替换 =================

    override fun handleInitPackageResources(
        resparam: XC_InitPackageResources.InitPackageResourcesParam
    ) {
        if (resparam.packageName != TARGET_PACKAGE) return
        val s = settings()
        replaceDimen(resparam.res, CLOCK_DIMEN, s.clockDp)
        replaceDimen(resparam.res, DATE_DIMEN, s.dateDp)
    }

    private fun replaceDimen(res: XResources, name: String, dp: Float) {
        try {
            val id = res.getIdentifier(name, "dimen", TARGET_PACKAGE)
            if (id == 0) {
                diag("dimen replacement skipped: $name not found in $TARGET_PACKAGE resources")
                return
            }
            res.setReplacement(
                TARGET_PACKAGE, "dimen", name,
                XResources.DimensionReplacement(dp, TypedValue.COMPLEX_UNIT_DIP)
            )
            diag("dimen replaced: $name -> ${dp}dp")
        } catch (t: Throwable) {
            diag("dimen replacement failed for $name: ${t.javaClass.name}: ${t.message}")
        }
    }

    // ================= hook 安装 =================

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 启动器进程（v2.0.38）：只装只读探针抓桌面宿主地面
        // 真值，不挂任何 UI、不改桌面行为；主体功能仍只在
        // SystemUI 进程。
        if (lpparam.packageName == LAUNCHER_PACKAGE) {
            diag("=== HonorShadeClock v${BuildConfig.VERSION_NAME} launcher probe loaded in $LAUNCHER_PACKAGE ===")
            LauncherProbe.install { message -> diag(message) }
            return
        }
        if (lpparam.packageName != TARGET_PACKAGE) return
        diag("=== HonorShadeClock v${BuildConfig.VERSION_NAME} loaded in $TARGET_PACKAGE ===")
        installDimenDiagnostic(lpparam)
        HeaderOverlay.install(lpparam) { message -> diag(message) }
        // 隐藏运营商文字（v2.0.8）：与覆盖层相互独立，原头部模式下同样生效
        CarrierHider.install(lpparam) { message -> diag(message) }
        // 真卡片探针（v2.0.31）：只加日志、零行为改变，进程内只跑一次
        // v2.0.36：先把宿主类加载器交给探针（hostbind 反射加载
        // framework 隐藏类时多一条路），再触发 init 路径。
        CardProbe.hostClassLoader = lpparam.classLoader
        CardProbe.runAtInit { message -> diag(message) }
        // 设置页「重启系统界面」按钮的看守（v2.0.12）：进程级轮询
        // restart.txt，与覆盖层是否挂载无关（原头部模式下字号改动
        // 正是最需要重启的场景）。
        installRestartWatcher()
    }

    /**
     * 重启看守（v2.0.12）：SystemUI 进程主线程每 800ms 瞄一眼
     * restart.txt。设置页点「重启系统界面」时把请求时刻（epoch
     * 毫秒）写进该文件；看守只认**本进程启动之后**的请求（进程
     * 启动时刻在看守安装时记下）——上一代进程没来得及消费的残
     * 留时间戳必然早于本进程启动，不会把新进程再杀一遍。命中
     * 后先把文件覆写为已消费（双保险），记一行日志，再结束
     * 自己进程：SystemUI 是常驻应用、会被系统立即拉起，模块
     * 随新进程重新加载，等同强停系统界面，全程不依赖 root。
     * 检查本身只是一次小文件读，失败只记一行、不停看守。
     */
    private fun installRestartWatcher() {
        try {
            val processStartMs = System.currentTimeMillis()
            val handler = Handler(Looper.getMainLooper())
            var handledMs = 0L
            var staleCleaned = false
            val watcher = object : Runnable {
                override fun run() {
                    try {
                        val reqMs = StampStore.readRestartRequestMs()
                        if (reqMs != null && reqMs > 0L && reqMs != handledMs) {
                            if (reqMs > processStartMs) {
                                handledMs = reqMs
                                StampStore.consumeRestartRequest()
                                diag(
                                    "restart requested from settings (ts=$reqMs), " +
                                        "killing SystemUI process now"
                                )
                                Process.killProcess(Process.myPid())
                                return
                            }
                            if (!staleCleaned) {
                                // 残留的旧请求：清掉，免得每 800ms 重读
                                staleCleaned = true
                                StampStore.consumeRestartRequest()
                                diag("stale restart request ignored and cleared (ts=$reqMs)")
                            }
                        }
                    } catch (t: Throwable) {
                        diag("restart watcher check failed: ${t.javaClass.name}: ${t.message}")
                    }
                    handler.postDelayed(this, 800L)
                }
            }
            handler.postDelayed(watcher, 800L)
            diag("restart watcher installed (settings -> restart.txt -> self kill)")
        } catch (t: Throwable) {
            diag("restart watcher install failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 字号验读回：头部首次 onAttachedToWindow 后读一次两个 dimen 的
     * getDimensionPixelSize，与期望像素（设置 dp x 当前 density）对照
     * 写一行诊断。每个进程只读一次；失败只记一行，不影响其他功能。
     */
    private fun installDimenDiagnostic(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val s = settings()
            val headerClass = XposedHelpers.findClass(HEADER_CLASS_NAME, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                headerClass, "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!dimenDiagDone.compareAndSet(false, true)) return
                        try {
                            val view = param.thisObject as View
                            val res = view.resources
                            val density = res.displayMetrics.density
                            val clockId = res.getIdentifier(CLOCK_DIMEN, "dimen", TARGET_PACKAGE)
                            val dateId = res.getIdentifier(DATE_DIMEN, "dimen", TARGET_PACKAGE)
                            val clockPx =
                                if (clockId != 0) res.getDimensionPixelSize(clockId) else -1
                            val datePx =
                                if (dateId != 0) res.getDimensionPixelSize(dateId) else -1
                            val clockExpected = (s.clockDp * density).roundToInt()
                            val dateExpected = (s.dateDp * density).roundToInt()
                            diag(
                                "dimen check: density=$density " +
                                    "clock=${clockPx}px (expect ${clockExpected}px), " +
                                    "date=${datePx}px (expect ${dateExpected}px)"
                            )
                        } catch (t: Throwable) {
                            diag("dimen check failed: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            diag("dimen diagnostic hook skipped: ${t.javaClass.name}: ${t.message}")
        }
    }
}
