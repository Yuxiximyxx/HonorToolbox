package io.github.yuxiximyxx.honortoolbox.shadeclock

import io.github.yuxiximyxx.honortoolbox.BuildConfig

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.widget.RemoteViews
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动器侧只读探针（v2.0.38，小乐点名「是不是要抓桌面」）：
 * 桌面编辑态底部第一个按钮就是「桌面卡片」、入口由启动器
 * com.hihonor.android.launcher 托管——桌面卡片的宿主就在
 * 启动器进程里。SystemUI 侧 hostbind 经 IAppWidgetService
 * 反射枚举启动器名下绑定，.36 已实锤 ROM 改了
 * getAppWidgetIds 签名、还没走到 uid 校验；地面真值的另
 * 一条路就是直接在启动器进程内读它自己的宿主。
 *
 * 只读口径（零行为改变）：只 hook 四个点记日志，不改任何
 * 参数与返回值、不挂任何 UI：
 * 1. AppWidgetHost 构造：宿主类名（启动器自家子类）+ hostId；
 * 2. AppWidgetHost.createView 后：hostId + appWidgetId +
 *    provider + 当场经 AppWidgetManager.getAppWidgetOptions
 *    读回的完整 options（hostName、尺寸键全量）；
 * 3. AppWidgetHost.updateAppWidgetOptions 前：启动器自己
 *    下发的 options 全量——这是真宿主 options 的第一手样本；
 * 4. AppWidgetHostView.updateAppWidget 前：桌面卡实际收到
 *    的下发（id + pkg + layoutId），只记 probe log 不落文件。
 * 1–3 同时追加写 /sdcard/Download/shadeclock_launcherbind.txt。
 * 启动器重启（强停后自动拉起）时桌面全部卡片会重走
 * createView，一轮即集齐桌面现有绑定的地面真值。
 *
 * 代价：模块作用域新增 com.hihonor.android.launcher
 * （xposedscope 已加），小乐装后要在 LSPosed 里多勾启动器
 * 并强停一次启动器才生效。异常全吞，不许影响桌面。
 */
object LauncherProbe {

    private val OUT_FILE = File("/sdcard/Download/shadeclock_launcherbind.txt")
    private val headerWritten = AtomicBoolean(false)

    fun install(log: (String) -> Unit) {
        writeHeader(log)
        hookHostConstructor(log)
        hookCreateView(log)
        hookUpdateOptions(log)
        hookViewUpdate(log)
        log("[launcherprobe] hooks installed (read-only, zero behavior change)")
    }

    private fun writeHeader(log: (String) -> Unit) {
        if (!headerWritten.compareAndSet(false, true)) return
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            appendFile(
                "# HonorShadeClock launcherbind v${BuildConfig.VERSION_NAME} " +
                    "time=${sdf.format(Date())} pid=${Process.myPid()} " +
                    "uid=${Process.myUid()}\n"
            )
            log("[launcherprobe] launcherbind -> ${OUT_FILE.absolutePath}")
        } catch (t: Throwable) {
            log("[launcherprobe] header write failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    @Synchronized
    private fun appendFile(text: String) {
        try {
            OUT_FILE.appendText(text)
        } catch (t: Throwable) {
            // 写不出去不许影响启动器
        }
    }

    private fun hostIdOf(host: Any): String = try {
        XposedHelpers.getIntField(host, "mHostId").toString()
    } catch (t: Throwable) {
        "?"
    }

    private fun bundleText(b: Bundle?): String {
        if (b == null) return "null"
        val sb = StringBuilder()
        for (k in b.keySet().sorted()) {
            val v = try {
                @Suppress("DEPRECATION")
                b.get(k)
            } catch (t: Throwable) {
                null
            }
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append(k).append("=").append(v)
                .append("(").append(v?.javaClass?.simpleName ?: "null").append(")")
        }
        return sb.toString()
    }

    private fun hookHostConstructor(log: (String) -> Unit) {
        try {
            XposedHelpers.findAndHookConstructor(
                AppWidgetHost::class.java,
                Context::class.java,
                Integer.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val host = param.thisObject
                            val hostId = param.args.getOrNull(1)
                            val line = "## host-created class=${host.javaClass.name} hostId=$hostId"
                            log("[launcherprobe] $line")
                            appendFile(line + "\n")
                        } catch (t: Throwable) {
                            // 只记日志，异常不许影响启动器
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            log("[launcherprobe] hook AppWidgetHost constructor failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun hookCreateView(log: (String) -> Unit) {
        try {
            XposedHelpers.findAndHookMethod(
                AppWidgetHost::class.java,
                "createView",
                Context::class.java,
                Integer.TYPE,
                AppWidgetProviderInfo::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val host = param.thisObject
                            val ctx = param.args.getOrNull(0) as? Context
                            val id = (param.args.getOrNull(1) as? Int) ?: -1
                            val info = param.args.getOrNull(2) as? AppWidgetProviderInfo
                            val view = param.result
                            val opts = try {
                                if (ctx != null && id > 0) {
                                    AppWidgetManager.getInstance(ctx)
                                        ?.getAppWidgetOptions(id)
                                } else {
                                    null
                                }
                            } catch (t: Throwable) {
                                null
                            }
                            val line = "## createView host=${host.javaClass.name} " +
                                "hostId=${hostIdOf(host)} id=$id " +
                                "provider=${info?.provider} " +
                                "view=${view?.javaClass?.name} " +
                                "options=[${bundleText(opts)}]"
                            log("[launcherprobe] $line")
                            appendFile(line + "\n")
                        } catch (t: Throwable) {
                            log("[launcherprobe] createView record failed: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            log("[launcherprobe] hook createView failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun hookUpdateOptions(log: (String) -> Unit) {
        try {
            XposedHelpers.findAndHookMethod(
                AppWidgetHost::class.java,
                "updateAppWidgetOptions",
                Integer.TYPE,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val host = param.thisObject
                            val id = (param.args.getOrNull(0) as? Int) ?: -1
                            val opts = param.args.getOrNull(1) as? Bundle
                            val line = "## updateOptions host=${host.javaClass.name} " +
                                "hostId=${hostIdOf(host)} id=$id " +
                                "options=[${bundleText(opts)}]"
                            log("[launcherprobe] $line")
                            appendFile(line + "\n")
                        } catch (t: Throwable) {
                            // 只记日志，异常不许影响启动器
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            log("[launcherprobe] hook updateAppWidgetOptions failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun hookViewUpdate(log: (String) -> Unit) {
        try {
            XposedHelpers.findAndHookMethod(
                AppWidgetHostView::class.java,
                "updateAppWidget",
                RemoteViews::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? AppWidgetHostView
                            val views = param.args.getOrNull(0) as? RemoteViews
                            log(
                                "[launcherprobe] view-update id=${view?.appWidgetId} " +
                                    "pkg=${views?.`package`} layout=${views?.layoutId}"
                            )
                        } catch (t: Throwable) {
                            // 只记日志，异常不许影响启动器
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            log("[launcherprobe] hook view updateAppWidget failed: ${t.javaClass.name}: ${t.message}")
        }
    }
}
