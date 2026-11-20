package io.github.yuxiximyxx.honortoolbox.navwake

import android.app.Application
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.yuxiximyxx.honortoolbox.navwake.config.NavConfig
import io.github.yuxiximyxx.honortoolbox.navwake.config.Packages
import io.github.yuxiximyxx.honortoolbox.navwake.core.HookContext
import io.github.yuxiximyxx.honortoolbox.navwake.core.ModuleLog
import io.github.yuxiximyxx.honortoolbox.navwake.hook.RedirectHook
import io.github.yuxiximyxx.honortoolbox.navwake.hook.TapBackHook
import io.github.yuxiximyxx.honortoolbox.navwake.probe.AssistProbe
import io.github.yuxiximyxx.honortoolbox.navwake.probe.BrainKeyProbe
import io.github.yuxiximyxx.honortoolbox.navwake.probe.ClassDumpProbe
import io.github.yuxiximyxx.honortoolbox.navwake.probe.IntentDispatchProbe
import io.github.yuxiximyxx.honortoolbox.navwake.probe.MagicVoiceActivityProbe
import io.github.yuxiximyxx.honortoolbox.navwake.probe.StartActivityProbe
import java.util.concurrent.ConcurrentHashMap

/**
 * NavWakeHook —— 原 NavWake 的 HookEntry（libxposed）在传统 Xposed API 上的等价移植。
 *
 * 加载顺序与熔断纪律与原版一致：
 * 1. 打无条件 ENTRY 日志 —— 判断「模块到底有没有被注入」全靠它；
 * 2. 绑落盘配置（原版是远程 prefs，工具箱改落盘文件，见 NavConfig）；
 * 3. SystemUI 里装重定向 hook（探针模式打开时再叠加阶段 0 探针）；
 *    magicvoice / brain 只在探针模式下装东西，平时完全不碰。
 *
 * 工具箱总闸（新增的一层，原版没有）：SystemUI 里三项（重定向/单击返回/探针）
 * 全关时不装任何 hook，宿主行为与没装一样。
 *
 * 每一步都独立 try/catch（熔断），任何异常都不能让 SystemUI 起不来。
 */
object NavWakeHook {

    const val TAG = "HookEntry"

    // 包名常量集中在 config/Packages，别在这里重复定义。
    const val MODULE_PACKAGE = Packages.MODULE
    val SYSTEMUI_PACKAGES: Set<String> = Packages.SYSTEMUI
    const val MAGICVOICE = Packages.MAGICVOICE
    const val BRAIN = Packages.BRAIN
    val ALL_PACKAGES: Set<String> = Packages.ALL

    /** 同一进程可能因多包共用而重复回调，按包名去重。 */
    private val installedPackages = ConcurrentHashMap.newKeySet<String>()

    /** 取当前进程的 Application（拿不到就返回 null，调用方要能容错）。 */
    fun currentApplicationContext(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        ModuleLog.i(
            TAG,
            "ENTRY LOADED pkg=${lpparam.packageName} process=${lpparam.processName}"
        )

        if (lpparam.packageName !in ALL_PACKAGES) {
            ModuleLog.d(TAG, "非目标包，忽略: ${lpparam.packageName}")
            return
        }
        if (!installedPackages.add(lpparam.packageName)) {
            ModuleLog.d(TAG, "本进程已安装过 ${lpparam.packageName}，跳过重复回调")
            return
        }

        try {
            installInternal(lpparam)
        } catch (error: Throwable) {
            ModuleLog.e(TAG, "install 顶层异常（已吞掉，进程照常启动）", error)
        }
    }

    private fun installInternal(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            NavConfig.bindFromFile()
            ModuleLog.setLevel(NavConfig.logLevel())
        }.onFailure {
            ModuleLog.e(TAG, "读取落盘配置失败，按默认值继续", it)
        }

        val probeMode = NavConfig.probeMode()
        ModuleLog.i(
            TAG,
            "安装 pkg=${lpparam.packageName} 探针模式=$probeMode " +
                "重定向=${NavConfig.enabled()} 单击返回=${NavConfig.tapBackEnabled()} " +
                "默认目标=${defaultTargetLabel()}"
        )

        val context = HookContext(
            classLoader = lpparam.classLoader,
            packageName = lpparam.packageName
        )

        when (lpparam.packageName) {
            in SYSTEMUI_PACKAGES -> {
                // 工具箱总闸：三项全关 → 不装任何 hook
                if (!NavConfig.enabled() && !NavConfig.tapBackEnabled() && !probeMode) {
                    ModuleLog.i(TAG, "重定向/单击返回/探针全关 → 不安装任何 hook（原行为）")
                    return
                }
                // ① 重定向（阶段 1 的主功能）
                if (NavConfig.enabled()) {
                    safely("RedirectHook") { RedirectHook.install(context) }
                } else {
                    ModuleLog.i(TAG, "总开关关闭 → 不安装重定向 hook（原 YOYO 行为）")
                }
                // ② 单击返回（v0.4.0）：常驻观察点位、按次读开关，默认关时纯放行，
                //    所以无条件安装 —— 设置页改开关立即生效，不必重启 SystemUI。
                safely("TapBackHook") { TapBackHook.install(context) }
                // ③ 探针（阶段 0 的诊断工具，默认关）
                if (probeMode) installSystemUiProbes(context)
            }

            MAGICVOICE -> if (probeMode) {
                safely("MagicVoiceActivityProbe") { MagicVoiceActivityProbe.install(context) }
                safely("IntentDispatchProbe") { IntentDispatchProbe.install(context) }
            } else {
                ModuleLog.i(TAG, "magicvoice：非探针模式，不安装任何 hook")
            }

            BRAIN -> if (probeMode) {
                safely("BrainKeyProbe") { BrainKeyProbe.install(context) }
            } else {
                ModuleLog.i(TAG, "brain：非探针模式，不安装任何 hook")
            }
        }

        ModuleLog.i(TAG, "安装完成 pkg=${lpparam.packageName} 共 ${context.installedCount} 个点位")
    }

    private fun defaultTargetLabel(): String {
        val pkg = NavConfig.defaultTarget()
        return pkg.ifEmpty { "（未设，每次弹框）" }
    }

    private fun installSystemUiProbes(context: HookContext) {
        runCatching {
            ClassDumpProbe.dumpMethods(
                context,
                "com.android.systemui.assist.AssistManager",
                listOf("start", "assist", "launch", "gesture"),
                "AssistManager"
            )
        }.onFailure { ModuleLog.w(TAG, "类形状 dump 失败（不影响主功能）", it) }

        safely("AssistProbe") { AssistProbe.install(context) }
        safely("StartActivityProbe") { StartActivityProbe.install(context) }
        safely("IntentDispatchProbe") { IntentDispatchProbe.install(context) }
    }

    private inline fun safely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            ModuleLog.e(TAG, "$name 安装失败，已熔断（该项不生效，其它不受影响）", error)
        }
    }
}
