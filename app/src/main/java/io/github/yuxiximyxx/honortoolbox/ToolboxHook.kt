package io.github.yuxiximyxx.honortoolbox

import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_InitPackageResources
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.yuxiximyxx.honortoolbox.betterheybox.BetterHeyboxHook
import io.github.yuxiximyxx.honortoolbox.libraryplus.hook.LibraryPlusHook
import io.github.yuxiximyxx.honortoolbox.magicpods.hook.MagicPodsHook
import io.github.yuxiximyxx.honortoolbox.magicxm4.hook.MagicXm4Hook
import io.github.yuxiximyxx.honortoolbox.navwake.NavWakeHook
import io.github.yuxiximyxx.honortoolbox.nighticon.NightIconHook
import io.github.yuxiximyxx.honortoolbox.palmpurify.PalmPurifyHook
import io.github.yuxiximyxx.honortoolbox.quickgesture.QuickGestureHook
import io.github.yuxiximyxx.honortoolbox.shadeclock.ShadeClockHook
import io.github.yuxiximyxx.honortoolbox.wetypeglass.WetypeGlassHook

/**
 * 荣耀工具箱统一入口（传统 Xposed API）：按宿主包名把
 * handleLoadPackage 分发给各功能自己的 hook 安装器；功能在
 * 工具箱首页被关掉时，它的 hook 一概不装——对应宿主进程的
 * 行为与没装模块完全一致。
 *
 * 分发口径：
 * - com.android.systemui：通知中心主题（ShadeClock，总开关走
 *   FeatureGate）+ 长按导航条唤醒（NavWake，总开关在它自己的
 *   navwake.txt 里、默认开，全关时它内部不装任何 hook）；
 * - com.hihonor.systemui / com.hihonor.magicvoice / com.hihonor.brain：
 *   NavWake 的其余宿主（后两者只在探针模式下装东西）；
 * - com.hihonor.android.launcher：通知中心主题的桌面只读
 *   探针（ShadeClockHook 内部处理）+ 图标日夜切换
 *   （NightIcon，自带总开关在它自己的配置里，默认关）；
 * - com.hihonor.servicecenter：快服务手势改向
 *   （QuickGesture，自带总开关在它自己的配置里，默认关）；
 * - com.hihonor.android.launcher / com.hihonor.hiboard /
 *   com.hihonor.search：资源库增强（LibraryPlus，v1.3.0 合入；
 *   启动器分支与 ShadeClock 探针、NightIcon 并行安装）。
 * - com.tencent.qt.qtl：掌盟净化（PalmPurify，v1.6.0 合入；
 *   与资源库同口径不经 FeatureGate——原模块无总开关、各功能
 *   开关即门控，FilePrefs 现读；旧配置 hook 侧导出、设置
 *   子页导入，见 palmpurify/PalmMigration）。
 * - com.max.xiaoheihe：小黑盒增强（BetterHeybox，v1.7.0 合入；
 *   YukiHook 声明式 hook 经适配层译成传统 API、各功能开关
 *   即门控不经 FeatureGate，配置走 Provider 直供，见
 *   betterheybox/BetterHeyboxHook）。
 * - com.tencent.wetype / com.bytedance.android.doubaoime：
 *   输入法玻璃（MOS液态输入，v1.8.0 合入；libxposed 现代 API
 *   经适配层译成传统 API、总开关即门控，配置走 Provider
 *   直供，见 wetypeglass/WetypeGlassHook）。
 */
class ToolboxHook : IXposedHookLoadPackage, IXposedHookInitPackageResources {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 工具箱自己的进程：只装一个激活标记 hook（LSPosed 把
        // 已启用模块注入它自己的应用进程时才会命中），供设置
        // 应用首页如实显示「LSPosed 已激活」。不碰任何功能分发。
        if (lpparam.packageName == "io.github.yuxiximyxx.honortoolbox") {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    "io.github.yuxiximyxx.honortoolbox.ModuleActivation",
                    lpparam.classLoader,
                    "isActive",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    },
                )
            }
            return
        }
        when (lpparam.packageName) {
            "com.android.systemui" -> {
                if (FeatureGate.isEnabled(FeatureGate.SHADE_CLOCK, true)) {
                    ShadeClockHook().handleLoadPackage(lpparam)
                }
                NavWakeHook.install(lpparam)
                // MagicPods（v1.4.0 合入）：状态栏耳机图标 + 控制中心
                // 降噪卡的 systemui 双装路径（原版同款，见 MagicPodsHook）
                MagicPodsHook.install(lpparam)
                // MagicXM4（v1.5.0 合入）：与 MagicPods 并行装载同一
                // 双装路径，各自按本方设备身份过滤，互不干扰
                MagicXm4Hook.install(lpparam)
            }
            "com.android.bluetooth",
            "com.hihonor.audioaccessorymanager",
            "com.hihonor.imedia.sws",
            "com.hihonor.controlcenter" -> {
                // MagicPods（v1.4.0 合入）：蓝牙协议/设备中心卡片/
                // 荣耀耳机面板/SWS 占位，宿主分发在 MagicPodsHook 内
                MagicPodsHook.install(lpparam)
                // MagicXM4（v1.5.0 合入）：与 MagicPods 并行装载；
                // 蓝牙进程内两台耳机的分发合并口径见 MagicXm4Hook
                // 文件头（谓词互斥、键前缀与 SPP 通道隔离）
                MagicXm4Hook.install(lpparam)
            }
            "com.hihonor.systemui",
            "com.hihonor.magicvoice",
            "com.hihonor.brain" -> {
                // NavWake 的其余宿主：magicvoice/brain 只在探针模式下装东西，
                // 平时 NavWakeHook 内部直接返回、不装任何 hook
                NavWakeHook.install(lpparam)
            }
            "com.hihonor.android.launcher" -> {
                if (FeatureGate.isEnabled(FeatureGate.SHADE_CLOCK, true)) {
                    ShadeClockHook().handleLoadPackage(lpparam)
                }
                NightIconHook.install(lpparam)
                // 资源库增强（LibraryPlus 合入，v1.3.0）：自带作用域
                // 判断与主进程判断，配置无总开关、各项默认关/跟随，
                // 不经 FeatureGate（落盘语义与独立版一致）。
                LibraryPlusHook().handleLoadPackage(lpparam)
            }
            "com.hihonor.hiboard",
            "com.hihonor.search" -> {
                LibraryPlusHook().handleLoadPackage(lpparam)
            }
            "com.hihonor.servicecenter" -> {
                QuickGestureHook().handleLoadPackage(lpparam)
            }
            "com.tencent.qt.qtl" -> {
                // 掌盟净化（PalmPurify 合入，v1.6.0）：只在主进程
                // 装钩、各功能开关 FilePrefs 现读为门控，口径见
                // 文件头。
                PalmPurifyHook.install(lpparam)
            }
            "com.max.xiaoheihe" -> {
                // 小黑盒增强（BetterHeybox 合入，v1.7.0）：原模块
                // 在小黑盒全部进程装载（与原 Yuki 入口同口径），
                // 开关即门控、配置 Provider 现读，见 BetterHeyboxHook。
                BetterHeyboxHook.install(lpparam)
            }
            "com.tencent.wetype",
            "com.bytedance.android.doubaoime" -> {
                // 输入法玻璃（MOS液态输入合入，v1.8.0）：挂
                // Application.onCreate 后经 Provider 快照做总
                // 开关门控再装 hook，见 WetypeGlassHook。
                WetypeGlassHook.install(lpparam)
            }
        }
        // 激活状态文件（v1.2.3）：分发到功能宿主并跑完该分支
        // 安装后写一次，设置应用据此判定激活——不再依赖用户
        // 在 LSPosed 里勾工具箱自身作用域（自身包名分支已在
        // 上面 return，不会走到这里）。写失败静默，不影响
        // hook 本职；只在进程加载时写这一次。
        ActivationStatus.writeFromHook(lpparam.packageName)
    }

    override fun handleInitPackageResources(
        resparam: XC_InitPackageResources.InitPackageResourcesParam
    ) {
        if (resparam.packageName != "com.android.systemui") return
        if (!FeatureGate.isEnabled(FeatureGate.SHADE_CLOCK, true)) return
        ShadeClockHook().handleInitPackageResources(resparam)
    }
}

/**
 * 模块激活自检标记：本体永远返回 false；LSPosed 已启用本
 * 模块时，[ToolboxHook] 会在工具箱自己的进程里把这个方法
 * hook 成 true。设置应用首页据此显示激活状态（真值，不猜）。
 */
object ModuleActivation {
    fun isActive(): Boolean = false
}
