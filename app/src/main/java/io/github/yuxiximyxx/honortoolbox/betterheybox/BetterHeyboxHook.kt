package io.github.yuxiximyxx.honortoolbox.betterheybox

import android.content.pm.ApplicationInfo
import com.better.heybox.App
import com.better.heybox.MainModule
import com.highcapable.yukihookapi.hook.param.PackageParam
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 小黑盒增强入口（v1.7.0 合入，原 BetterHeybox 的 YukiHook
 * 入口 HookEntry 的传统 API 等价物）：由 ToolboxHook 按宿主
 * 包名分发到这里，组装适配层 [PackageParam] 后交给原模块
 * 的 [MainModule.attach]，后续安装流程与原模块一致（先挂
 * Application.onCreate、过降级检查再装全部 hook）。
 *
 * 两处合入适配：
 * - module.appInfo 给一份 sourceDir 为空的 ApplicationInfo：
 *   原模块的「模块版本下限」防降级判定在合入形态下无意义
 *   （工具箱有自己的版本线），且旧独立版在宿主本地存下的
 *   下限值（其版本码远大于工具箱）会误判降级、拒绝激活；
 *   源路径不可解析时 ownVersionCode 返回 -1、整套判定惰性
 *   跳过，行为等价于该功能关闭；
 * - 宿主 Context 经 [App.resolveAppContext] 延迟解析
 *   （loadPackage 时机应用尚未创建）。
 */
object BetterHeyboxHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != MainModule.TARGET_PKG) return
        val moduleInfo = ApplicationInfo().apply {
            packageName = "io.github.yuxiximyxx.honortoolbox"
        }
        val param = PackageParam(
            hostClassLoader = lpparam.classLoader,
            moduleAppInfo = moduleInfo,
            hostContextProvider = { App.resolveAppContext() },
        )
        MainModule.attach(param)
    }
}
