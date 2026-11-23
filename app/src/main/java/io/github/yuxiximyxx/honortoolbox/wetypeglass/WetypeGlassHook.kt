package io.github.yuxiximyxx.honortoolbox.wetypeglass

import android.app.Application
import android.content.Context
import android.os.Bundle
import com.superboyyy.mosinputglass.Config
import com.superboyyy.mosinputglass.Main
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 输入法玻璃入口（v1.8.0 合入 MOS液态输入，原模块是
 * libxposed 现代 API 入口）：由 ToolboxHook 按宿主包名分发
 * 到这里。原版在 onPackageLoaded 里就装全部 hook；合入后
 * 改为先挂 Application.onCreate（与小黑盒增强的 attach 同
 * 范式），拿到宿主 Context 后：
 *
 * 1. 经 [WgConfigContract] 的 Provider 拉一份配置快照做
 *    总开关门控——enable != "1" 时不装任何 hook（工具箱其
 *    它合入功能的统一口径；拉不到快照时按默认值开处理，
 *    与原版远程配置不可用时的回落一致）；
 * 2. 门控通过才 new Main().install(包名, Context)，后续
 *    hook 安装与玻璃逻辑与原模块一致（Main 本体零改，
 *    只有入口/配置/hook 注册三处适配，见其类头）。
 *
 * 每个宿主进程各走一遍（输入法服务在主进程，子进程装了
 * 也不会命中 InputMethodService 的 hook，与原版
 * onPackageLoaded 的进程口径一致）。
 */
object WetypeGlassHook {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg != Config.PKG_WECHAT && pkg != Config.PKG_DOUBAO) return
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Context ?: return
                    if (!masterEnabled(app)) return
                    runCatching { Main().install(pkg, app) }
                }
            },
        )
    }

    /** 总开关门控：快照里 enable 为 "1"（或拉不到快照）才放行。 */
    private fun masterEnabled(context: Context): Boolean {
        return try {
            val out: Bundle? = context.contentResolver.call(
                WgConfigContract.URI, WgConfigContract.METHOD_GET_ALL, null, null,
            )
            if (out == null) {
                true
            } else {
                val v = out.getString(Config.ENABLE)
                v == null || v == "1"
            }
        } catch (t: Throwable) {
            true
        }
    }
}
