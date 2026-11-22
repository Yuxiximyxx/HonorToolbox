package io.github.yuxiximyxx.honortoolbox.palmpurify

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Member

/**
 * PalmPurify 的 libxposed → 传统 Xposed API 适配层（v1.6.0 合入
 * 荣耀工具箱时新增，与 MagicPods / MagicXM4 的 HookContext 同法、
 * 各留一份）。
 *
 * 原 PalmPurify 基于 libxposed 现代 API，hook 代码通篇是
 * `module.hook(member).setExceptionMode(PROTECTIVE).intercept { chain -> }`
 * 形态，并通过 `module.log(...)` 打日志、`module.getRemotePreferences(组名)`
 * 读配置、`module.moduleApplicationInfo` 取模块包路径。本文件提供
 * 同名同形的本地替身（[XposedModule] / [XposedInterface]），让四组
 * hook 文件的拦截点代码逐字保留、只换底座：
 *
 * - intercept 在传统 API 的 before 阶段执行：lambda 返回非
 *   null/Unit 值即作为方法结果（原方法不再执行）；返回
 *   null/Unit 时，已 proceed 则保留 proceed 的结果，否则结果
 *   为 null（短路原方法——屏蔽弹窗/摇一摇正是靠这条语义）；
 * - ExceptionMode.PROTECTIVE 的语义由适配层内建：lambda 抛
 *   异常时记日志并放行原方法（已 proceed 过则不重复执行）；
 * - getRemotePreferences(组名) 一律落到 [PalmPrefs] 的落盘
 *   通道（原组名 "palmpurify" 不变，见 PalmPrefs）。
 */
object XposedInterface {

    enum class ExceptionMode { PROTECTIVE }

    /**
     * libxposed Chain 的传统 API 等价物。args 是宿主参数数组
     * 的活视图（读为主；PalmPurify 只读不写回）。
     */
    class Chain internal constructor(
        private val member: Member,
        private val thisObject: Any?,
        private val backing: Array<Any?>,
    ) {
        var proceeded: Boolean = false
            private set

        var proceededResult: Any? = null
            private set

        fun getThisObject(): Any? = thisObject

        fun getArg(index: Int): Any? = backing.getOrNull(index)

        fun getArgs(): List<Any?> = backing.asList()

        fun proceed(): Any? = proceed(backing)

        fun proceed(args: Array<Any?>): Any? {
            proceeded = true
            val result = XposedBridge.invokeOriginalMethod(member, thisObject, args)
            proceededResult = result
            return result
        }
    }
}

/** libxposed XposedModule 的本地替身：hook 构建、日志、配置、模块包信息。 */
object XposedModule {

    fun hook(member: Member): HookBuilder = HookBuilder(member)

    fun log(priority: Int, tag: String, message: String) {
        emit(priority, tag, message, null)
    }

    fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        emit(priority, tag, message, throwable)
    }

    private fun emit(priority: Int, tag: String, message: String, throwable: Throwable?) {
        fileLog(tag, message, throwable)
        runCatching {
            if (throwable != null) {
                XposedBridge.log("$tag: $message\n${Log.getStackTraceString(throwable)}")
            } else {
                XposedBridge.log("$tag: $message")
            }
        }
        runCatching {
            if (throwable != null) {
                Log.println(priority, tag, "$message\n${Log.getStackTraceString(throwable)}")
            } else {
                Log.println(priority, tag, message)
            }
        }
    }

    // ── 落盘诊断（v1.6.2 新增） ─────────────────────────────────
    // 四组 hook 的日志都经 emit 出去，这里统一再落一份到
    // /sdcard/Download/HonorToolbox/palmpurify.log：v1.6.1
    // 真机反馈开屏没屏蔽、底栏精简没生效，而原日志只在
    // LSPosed/logcat 里，定不了案。同一行 2 秒内重复只记
    // 一次防刷屏；文件超 1MB 整份重开；写失败静默。
    private val LOG_FILE = File("/sdcard/Download/HonorToolbox/palmpurify.log")
    private const val FILE_CAP_BYTES = 1_000_000L
    private const val DEDUPE_WINDOW_MS = 2_000L
    private val fileLock = Any()
    private val recentLines = HashMap<String, Long>()

    private fun fileLog(tag: String, message: String, throwable: Throwable?) {
        runCatching {
            val key = "$tag|$message"
            val now = System.currentTimeMillis()
            synchronized(fileLock) {
                val last = recentLines[key]
                if (last != null && now - last < DEDUPE_WINDOW_MS) return
                recentLines[key] = now
                if (recentLines.size > 256) recentLines.clear()
                LOG_FILE.parentFile?.mkdirs()
                if (LOG_FILE.exists() && LOG_FILE.length() > FILE_CAP_BYTES) {
                    LOG_FILE.delete()
                }
                val sb = StringBuilder()
                sb.append(now).append(' ').append(tag).append(": ").append(message).append('\n')
                if (throwable != null) {
                    sb.append(Log.getStackTraceString(throwable)).append('\n')
                }
                LOG_FILE.appendText(sb.toString())
            }
        }
    }

    /** 原 RemotePreferences 入口：组名不变，底座换成落盘现读（见 [PalmPrefs]）。 */
    fun getRemotePreferences(group: String): SharedPreferences = PalmPrefs.store

    /**
     * 模块（工具箱）自身的 ApplicationInfo。原版由 libxposed
     * 框架提供，SplashAdHooks 只用它的 sourceDir 从模块包里
     * 解出 libdexkit.so。传统 API 下按两条路取：先用本类代码
     * 来源（LSPosed 从工具箱 APK 装载模块类，codeSource 即
     * 工具箱 APK 路径），取不到再经系统 Context 的包管理器
     * 按包名查。两条都失败时抛异常，由调用方按原逻辑放行。
     */
    val moduleApplicationInfo: ApplicationInfo by lazy {
        val pkg = "io.github.yuxiximyxx.honortoolbox"
        val fromCodeSource = runCatching {
            val path = XposedModule::class.java.protectionDomain
                ?.codeSource?.location?.path
            if (path.isNullOrBlank() || !File(path).isFile) {
                throw IllegalStateException("codeSource 不可用")
            }
            ApplicationInfo().apply {
                packageName = pkg
                sourceDir = path
                publicSourceDir = path
            }
        }
        fromCodeSource.getOrElse {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            val context = threadClass.getMethod("getSystemContext").invoke(thread) as Context
            context.packageManager.getApplicationInfo(
                pkg,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        }
    }
}

class HookBuilder(private val member: Member) {

    /** PROTECTIVE 语义已内建在 [intercept] 的异常处理里，此处仅保持调用形态。 */
    fun setExceptionMode(mode: XposedInterface.ExceptionMode): HookBuilder = this

    fun intercept(block: (XposedInterface.Chain) -> Any?) {
        XposedBridge.hookMethod(member, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val chain = XposedInterface.Chain(member, param.thisObject, param.args)
                val ret: Any? = try {
                    block(chain)
                } catch (error: Throwable) {
                    XposedModule.log(
                        Log.ERROR,
                        ModulePrefs.LOG_TAG,
                        "拦截器异常，放行原方法 $member",
                        error,
                    )
                    if (!chain.proceeded) {
                        runCatching { chain.proceed() }
                    }
                    chain.proceededResult
                }
                param.result = when {
                    ret == null || ret === Unit ->
                        if (chain.proceeded) chain.proceededResult else null
                    else -> ret
                }
            }
        })
    }
}
