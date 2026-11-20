package io.github.yuxiximyxx.honortoolbox.shadeclock

import io.github.yuxiximyxx.honortoolbox.BuildConfig

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.xmlpull.v1.XmlPullParser

/**
 * 真卡片探针（v2.0.31）：只加日志、零行为改变。
 *
 * 目的：回答「荣耀桌面应用服务卡片能不能搬进通知中心头部」。
 * A 路线（标准 AppWidget）：SystemUI 自己的锁屏小部件
 * （com.hihonor.keyguard.util.AppWidgetHelper$KgAppWidgetHost）
 * 已在同进程走通 allocate -> bindAppWidgetIdIfAllowed ->
 * createView 全流程；本探针实测三件事：十个来源应用有没有
 * 标准 provider、SystemUI 进程有没有 BIND_APPWIDGET、本进程
 * new AppWidgetHost + allocateAppWidgetId 是否放行。
 * B 路线（荣耀 form 框架）：静态分析在 SystemUI 四个 dex 与
 * framework-magic.jar 里都没找到 form 宿主入口（类名/服务均
 * 无实锤），按「无已知入口」记一行并跳过，不猜类名乱试。
 *
 * 触发两次（进程内只真正跑一次）：hook 初始化、首次开门。
 * 全部输出经 log 回调写既有 shadeclock_probe.log，前缀
 * [cardprobe]；异常全吞，不许影响模块任何现有功能。
 */
object CardProbe {

    private const val HOST_ID = 0x5343 // 'SC'
    private const val MAX_PROVIDER_LINES = 400

    private val ran = AtomicBoolean(false)

    /** 宿主（SystemUI）类加载器，由 ShadeClockHook 在 handleLoadPackage 时注入，
     * 供 hostbind 反射加载 framework 隐藏类时多一条路（v2.0.36）。 */
    @Volatile
    var hostClassLoader: ClassLoader? = null

    /** 目标来源应用的 label 关键词（按 label 匹配并标注，包名以枚举结果为准）。 */
    private val LABEL_KEYWORDS = listOf(
        "日历", "天气", "时钟", "联系人", "图库", "钱包", "健康",
        "文字卡片", "语录", "智慧空间", "平板",
        "Calendar", "Weather", "Clock", "Contacts", "Gallery", "Wallet", "Health"
    )

    /**
     * hook 初始化触发点。handleLoadPackage 时 Application 往往还没建、
     * context 拿不到——v2.0.35 时这条路实际从未触发、探针全靠首次开门。
     * v2.0.36 改为后台轮询重试（每 2 秒、最长 60 秒），context 一出现
     * 就跑，不再只挂首次开门；runOnce 的进程内一次闸不变。
     */
    fun runAtInit(log: (String) -> Unit) {
        val ctx = currentApplicationContext()
        if (ctx != null) {
            runOnce(ctx, "init", log)
            return
        }
        log("[cardprobe] init trigger: no application context yet, scheduling retry")
        try {
            val t = Thread {
                var waitedMs = 0
                while (waitedMs < 60_000) {
                    try {
                        Thread.sleep(2_000)
                    } catch (ie: InterruptedException) {
                        return@Thread
                    }
                    waitedMs += 2_000
                    val c = currentApplicationContext()
                    if (c != null) {
                        runOnce(c, "init-retry", log)
                        return@Thread
                    }
                }
                log("[cardprobe] init trigger: context still unavailable after 60s, defer to first gate open")
            }
            t.name = "shadeclock-probe-init"
            t.isDaemon = true
            t.start()
        } catch (t: Throwable) {
            log("[cardprobe] init trigger: retry schedule failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /** 首次开门触发点（HeaderOverlay 在 wrapper 转 VISIBLE 时调用）。 */
    fun runAtFirstGateOpen(context: Context?, log: (String) -> Unit) {
        if (context == null) return
        runOnce(context, "first-gate-open", log)
    }

    private fun currentApplicationContext(): Context? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getMethod("currentApplication").invoke(null)
            (app as? Context)
        } catch (t: Throwable) {
            null
        }
    }

    private fun runOnce(ctx: Context, source: String, log: (String) -> Unit) {
        if (!ran.compareAndSet(false, true)) return
        try {
            log("[cardprobe] ===== card probe start (trigger=$source, pid=${Process.myPid()}, uid=${Process.myUid()}) =====")
            probePermission(ctx, log)
            probeProviders(ctx, log)
            probeXmlDumpAsync(ctx, log)
            probeHostAllocate(ctx, log)
            log("[cardprobe] form-host: no known entry point (static analysis: no Honor form host service/class found in SystemUI dex or framework-magic.jar); skipped, no guessing")
            log("[cardprobe] ===== card probe end =====")
        } catch (t: Throwable) {
            log("[cardprobe] probe aborted: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun probePermission(ctx: Context, log: (String) -> Unit) {
        try {
            val perm = "android.permission.BIND_APPWIDGET"
            val self = ctx.checkPermission(perm, Process.myPid(), Process.myUid())
            log(
                "[cardprobe] permission BIND_APPWIDGET self-check: " +
                    if (self == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED($self)"
            )
            val byPkg = try {
                ctx.packageManager.checkPermission(perm, ctx.packageName)
            } catch (t: Throwable) {
                -99
            }
            log(
                "[cardprobe] permission BIND_APPWIDGET for package ${ctx.packageName}: " +
                    if (byPkg == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED($byPkg)"
            )
        } catch (t: Throwable) {
            log("[cardprobe] permission check failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun probeProviders(ctx: Context, log: (String) -> Unit) {
        try {
            val awm = AppWidgetManager.getInstance(ctx)
            if (awm == null) {
                log("[cardprobe] providers: AppWidgetManager is null")
                return
            }
            val providers = awm.installedProviders
            log("[cardprobe] providers: total=${providers.size}")
            var matched = 0
            var lines = 0
            for (info in providers) {
                val comp = info.provider
                val label = try {
                    info.loadLabel(ctx.packageManager) ?: ""
                } catch (t: Throwable) {
                    ""
                }
                val hit = LABEL_KEYWORDS.firstOrNull { label.contains(it) }
                if (hit != null) matched++
                if (lines >= MAX_PROVIDER_LINES) continue
                lines++
                log(
                    "[cardprobe] provider${if (hit != null) " [MATCH:$hit]" else ""}: " +
                        "pkg=${comp.packageName} cls=${comp.className} label=$label " +
                        "minW=${info.minWidth} minH=${info.minHeight} " +
                        "cellW=${info.targetCellWidth} cellH=${info.targetCellHeight} " +
                        "resizeMode=${info.resizeMode} widgetCategory=${info.widgetCategory}"
                )
            }
            if (providers.size > lines) {
                log("[cardprobe] providers: list truncated at $lines lines (total=${providers.size})")
            }
            log("[cardprobe] providers: label-matched=$matched of ${providers.size}")
        } catch (t: Throwable) {
            log("[cardprobe] providers enumeration failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 服务卡片 XML 声明 dump（v2.0.34）：小乐纠正——系统目录里
     * 「服务卡片」与「经典小工具」是两个分区，区分标记与规范
     * 尺寸档位不在运行时 API 里，最大嫌疑在每个 receiver 的
     * appwidget-provider XML 原始声明（荣耀命名空间自定义属性）。
     * 本探针在 SystemUI 侧（全量 554 可见、不受普通应用可见性
     * 过滤）逐个 loadXmlMetaData 读原始 XML，把全部属性
     * （命名空间+名+值，资源引用另记原始整数形态）写到
     * /sdcard/Download/shadeclock_cardxml.txt，供下一轮逐属性
     * 比对定案。解析 554 份 XML 较重，放后台线程跑；只写文件、
     * 零行为改变，异常全吞。
     */
    private fun probeXmlDumpAsync(ctx: Context, log: (String) -> Unit) {
        try {
            val appCtx = ctx.applicationContext ?: ctx
            val t = Thread {
                dumpProviderXml(appCtx, log)
                dumpHostBindings(appCtx, log)
            }
            t.name = "shadeclock-cardxml"
            t.isDaemon = true
            t.start()
            log("[cardprobe] xml-dump: scheduled on background thread -> /sdcard/Download/shadeclock_cardxml.txt")
        } catch (t: Throwable) {
            log("[cardprobe] xml-dump: schedule failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun dumpProviderXml(ctx: Context, log: (String) -> Unit) {
        val outFile = File("/sdcard/Download/shadeclock_cardxml.txt")
        var ok = 0
        var failed = 0
        try {
            val pm = ctx.packageManager
            val awm = AppWidgetManager.getInstance(ctx)
            if (awm == null) {
                log("[cardprobe] xml-dump: AppWidgetManager is null")
                return
            }
            val providers = awm.installedProviders
            val seenPkgs = HashSet<String>()
            outFile.bufferedWriter().use { w ->
                w.write("# HonorShadeClock cardxml dump v${BuildConfig.VERSION_NAME} providers=${providers.size}\n")
                for (info in providers) {
                    val comp = info.provider
                    val label = try {
                        info.loadLabel(pm) ?: ""
                    } catch (t: Throwable) {
                        ""
                    }
                    // 应用级 meta-data 键（每包只记一次）：服务卡片身份标记
                    // 若不在 provider XML 里，候选位置之二（v2.0.36 新增）。
                    if (seenPkgs.add(comp.packageName)) {
                        try {
                            val ai = pm.getApplicationInfo(
                                comp.packageName,
                                PackageManager.GET_META_DATA,
                            )
                            val keys = ai.metaData?.keySet()?.sorted()?.joinToString(", ")
                            w.write("### app-meta pkg=${comp.packageName} keys=[$keys]\n")
                            val mv = metaValuesText(pm, comp.packageName, ai.metaData)
                            if (mv.isNotEmpty()) {
                                w.write("### app-meta-values pkg=${comp.packageName} $mv\n")
                            }
                        } catch (t: Throwable) {
                            w.write("### app-meta pkg=${comp.packageName} FAILED: ${t.javaClass.name}: ${t.message}\n")
                        }
                    }
                    w.write("=== ${comp.flattenToString()} label=$label\n")
                    // receiver 级 meta-data 全部键名（候选位置之三）：身份标记
                    // 可能以独立 meta-data 键挂在 receiver 上，不在 XML 内容里。
                    try {
                        val ri0 = pm.getReceiverInfo(comp, PackageManager.GET_META_DATA)
                        val keys = ri0.metaData?.keySet()?.sorted()?.joinToString(", ")
                        w.write("  meta-keys: [$keys]\n")
                        // v2.0.38：目标键取值一并记（card_size /
                        // card_category / card_widget_type /
                        // card_widgetType / widget_type / widgetType，
                        // 均为 launcher 前缀；实测真实键名是
                        // widget_type / widgetType，card_widget_*
                        // 并不存在——两种拼写都覆盖免得漏）：
                        // 字符串直记，资源引用记原始值并回源
                        // 应用资源解文本，供定尺寸档位文案。
                        val mv = metaValuesText(pm, comp.packageName, ri0.metaData)
                        if (mv.isNotEmpty()) w.write("  meta-values: $mv\n")
                    } catch (t: Throwable) {
                        w.write("  meta-keys: FAILED ${t.javaClass.name}: ${t.message}\n")
                    }
                    try {
                        val ri = pm.getReceiverInfo(comp, PackageManager.GET_META_DATA)
                        val parser = ri.loadXmlMetaData(pm, "android.appwidget.provider")
                        if (parser == null) {
                            w.write("  <no xml metadata>\n")
                            failed++
                            continue
                        }
                        try {
                            var event = parser.eventType
                            while (event != XmlPullParser.END_DOCUMENT) {
                                if (event == XmlPullParser.START_TAG) {
                                    val sb = StringBuilder()
                                    sb.append("  <").append(parser.name)
                                    for (i in 0 until parser.attributeCount) {
                                        val ns = parser.getAttributeNamespace(i)
                                        val name = parser.getAttributeName(i)
                                        val value = try {
                                            parser.getAttributeValue(i)
                                        } catch (t: Throwable) {
                                            null
                                        }
                                        sb.append('\n').append("    ")
                                        if (!ns.isNullOrEmpty()) {
                                            sb.append('{').append(ns).append('}')
                                        }
                                        sb.append(name).append("=\"").append(value).append('"')
                                        val rawInt = try {
                                            parser.getAttributeIntValue(i, Int.MIN_VALUE)
                                        } catch (t: Throwable) {
                                            Int.MIN_VALUE
                                        }
                                        if (rawInt != Int.MIN_VALUE) {
                                            sb.append(" rawInt=").append(rawInt)
                                                .append("/0x")
                                                .append(Integer.toHexString(rawInt))
                                        }
                                    }
                                    w.write(sb.toString())
                                    w.write("\n")
                                }
                                event = parser.next()
                            }
                        } finally {
                            try {
                                parser.close()
                            } catch (t: Throwable) {
                                // 关不掉就算了
                            }
                        }
                        ok++
                    } catch (t: Throwable) {
                        failed++
                        w.write("  <error ${t.javaClass.name}: ${t.message}>\n")
                    }
                }
                w.write("# end ok=$ok failed=$failed\n")
            }
            log("[cardprobe] xml-dump: done ok=$ok failed=$failed -> ${outFile.absolutePath}")
        } catch (t: Throwable) {
            log("[cardprobe] xml-dump: aborted: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 目标 meta 键取值渲染（v2.0.38）：card_size / card_category /
     * card_widget_type / card_widgetType / widget_type / widgetType
     * （com.hihonor.android.launcher 前缀；实测 card_widget_* 键
     * 不存在，真实键是 widget_type / widgetType，两种拼写都覆盖）。
     * 字符串直记；Int 资源引用（>=0x7f000000）记原始值并回源
     * 应用资源解成文本、解不出记 <unresolved>；小整数原样记。
     * 没有任一目标键时返回空串（调用方不写行）。
     */
    private fun metaValuesText(
        pm: PackageManager,
        pkg: String,
        meta: android.os.Bundle?,
    ): String {
        if (meta == null) return ""
        val keys = listOf(
            "com.hihonor.android.launcher.card_size",
            "com.hihonor.android.launcher.card_category",
            "com.hihonor.android.launcher.card_widget_type",
            "com.hihonor.android.launcher.card_widgetType",
            "com.hihonor.android.launcher.widget_type",
            "com.hihonor.android.launcher.widgetType",
        )
        val parts = mutableListOf<String>()
        for (k in keys) {
            if (!meta.containsKey(k)) continue
            @Suppress("DEPRECATION")
            val v = try {
                meta.get(k)
            } catch (t: Throwable) {
                null
            }
            val rendered = when (v) {
                null -> "null"
                is String -> "\"$v\""
                is Int -> {
                    if (v >= 0x7f000000) {
                        val resolved = try {
                            pm.getResourcesForApplication(pkg).getString(v)
                        } catch (t: Throwable) {
                            null
                        }
                        if (resolved != null) {
                            "$v(0x${Integer.toHexString(v)})->\"$resolved\""
                        } else {
                            "$v(0x${Integer.toHexString(v)})-><unresolved>"
                        }
                    } else {
                        "$v"
                    }
                }
                else -> v.toString()
            }
            parts.add("$k=$rendered")
        }
        return parts.joinToString("; ")
    }

    /**
     * 宿主绑定地面真值 dump（v2.0.35，小乐拍板的取证捷径）：
     * 他在桌面亲手添加想要的服务卡片后，启动器宿主名下就有一
     * 批已成立的绑定——经 IAppWidgetService（反射）枚举各候选
     * 宿主包名下的全部 appWidgetId，逐个把 provider 组件与
     * getAppWidgetOptions 的完整 Bundle（hostName、尺寸档位、
     * 私有键全量原样）写到 /sdcard/Download/
     * shadeclock_hostbind.txt，与我们自己宿主的绑定逐项对照。
     * 服务侧对 callingPackage 有 uid 校验，启动器那批大概率
     * 被拦——逐法记成败、被拦就如实记异常退回 XML 路线，
     * 不硬闯。SystemUI 自家名下（锁屏宿主 KgAppWidgetHost 与
     * 本模块 CardHost 都在此）应可达：锁屏宿主是荣耀自家宿主、
     * 它的 options 就是现成的对照样本。
     * v2.0.36 修订：.35 真机两会话都死在取 Stub 类一步、文件没
     * 生成（根因见 obtainAppWidgetService 注释）；本版文件先行 +
     * 三条服务获取策略，成败全写进文件本身。
     */
    private fun dumpHostBindings(ctx: Context, log: (String) -> Unit) {
        val outFile = File("/sdcard/Download/shadeclock_hostbind.txt")
        // v2.0.36 第一修：文件先行。.35 的死法是反射取 Stub 类在
        // 打开文件之前就抛 ClassNotFoundException、整个函数 abort，
        // 文件根本没机会创建——小乐那边看到的就是「没有 hostbind」。
        // 本版先把文件建出来写好文件头，后面每一步（拿 binder、
        // 拿服务代理、逐包枚举）的成败都写进文件本身并逐段 flush，
        // 全灭也有一份带失败轨迹的文件落地。
        try {
            outFile.bufferedWriter().use { w ->
                w.write("# HonorShadeClock hostbind dump v${BuildConfig.VERSION_NAME}\n")
                w.flush()
                val svc = obtainAppWidgetService(ctx, w, log)
                if (svc == null) {
                    w.write("## service acquisition: ALL STRATEGIES FAILED (see steps above)\n")
                    w.flush()
                    log("[cardprobe] hostbind: service acquisition failed, failure trail written -> ${outFile.absolutePath}")
                    return
                }
                val methods = discoverServiceMethods(svc, w, log)
                val candidates = listOf(
                    "com.android.systemui",
                    "com.hihonor.android.launcher",
                    "com.android.launcher3",
                    ctx.packageName,
                )
                for (pkg in candidates) {
                    // v2.0.38：按真签名调——候选来自签名发现的
                    // 全量方法表（名字含 AppWidgetIds 的都试，
                    // 精确名优先），参数按类型填（secondInt 对
                    // getAppWidgetIds 是 userId=0）。不再写死
                    // 任何签名（.36 写死 (String) 全灭的教训）。
                    val ids = invokeServiceByNamePart(
                        svc, w, methods, "AppWidgetIds", pkg, 0,
                    ) as? IntArray
                    w.write(
                        "## getAppWidgetIds($pkg) -> ${ids?.size} ids: " +
                            "${ids?.joinToString()}\n"
                    )
                    w.flush()
                    log("[cardprobe] hostbind: getAppWidgetIds($pkg) -> ${ids?.size} ids")
                    if (ids != null) {
                        for (id in ids) {
                            dumpOneBinding(svc, w, methods, pkg, id)
                        }
                        w.flush()
                    }
                }
            }
            log("[cardprobe] hostbind: done -> ${outFile.absolutePath}")
        } catch (t: Throwable) {
            log("[cardprobe] hostbind: aborted: ${t.javaClass.name}: ${t.message}")
            try {
                outFile.appendText("## aborted: ${t.javaClass.name}: ${t.message}\n")
            } catch (ignored: Throwable) {
                // 文件都落不了就只剩日志这一行
            }
        }
    }

    /**
     * 拿到小部件服务代理，每条策略的成败都写进 [w]（hostbind 文件）
     * 与探针日志。v2.0.35 的死因有二：一是类名写错——Stub 的真实
     * 全名是 com.android.internal.appwidget.IAppWidgetService$Stub
     * （AOSP AIDL 所在包），.35 写的是 android.appwidget.…，类根本
     * 不存在；二是 Class.forName 走模块类加载器、framework 隐藏类
     * 未必可见。本版三条策略依次试：
     * S1 直接从 AppWidgetManager 实例的 mService 字段掏现成代理
     *    （不需要加载任何隐藏类，最稳）；
     * S2 用正确类名经 ServiceManager binder + asInterface，类加载
     *    器依次试模块 / 系统 / 宿主（SystemUI）三条；
     * S3 旧错名兜底（防个别 ROM 真把它放在 android.appwidget 下）。
     */
    private fun obtainAppWidgetService(
        ctx: Context,
        w: java.io.Writer,
        log: (String) -> Unit,
    ): Any? {
        // ---- S1: AppWidgetManager.mService ----
        try {
            val awm = AppWidgetManager.getInstance(ctx)
            var cls: Class<*>? = awm?.javaClass
            var field: java.lang.reflect.Field? = null
            while (cls != null && field == null) {
                field = try {
                    cls.getDeclaredField("mService")
                } catch (nf: NoSuchFieldException) {
                    null
                }
                if (field == null) cls = cls.superclass
            }
            if (awm != null && field != null) {
                field.isAccessible = true
                val svc = field.get(awm)
                if (svc != null) {
                    w.write("## service via S1 AppWidgetManager.mService: ${svc.javaClass.name}\n")
                    w.flush()
                    log("[cardprobe] hostbind: service via S1 mService ok: ${svc.javaClass.name}")
                    return svc
                }
                w.write("## S1 AppWidgetManager.mService: field present but null\n")
            } else {
                w.write("## S1 AppWidgetManager.mService: field not found (awm=${awm?.javaClass?.name})\n")
            }
            w.flush()
        } catch (t: Throwable) {
            val cause = unwrap(t)
            w.write("## S1 FAILED: ${cause.javaClass.name}: ${cause.message}\n")
            w.flush()
            log("[cardprobe] hostbind: S1 failed: ${cause.javaClass.name}: ${cause.message}")
        }
        // ---- binder（S2/S3 共用）----
        val binder = try {
            val smClass = Class.forName("android.os.ServiceManager")
            smClass.getMethod("getService", String::class.java)
                .invoke(null, "appwidget")
        } catch (t: Throwable) {
            val cause = unwrap(t)
            w.write("## binder FAILED: ${cause.javaClass.name}: ${cause.message}\n")
            w.flush()
            log("[cardprobe] hostbind: binder failed: ${cause.javaClass.name}: ${cause.message}")
            null
        }
        if (binder == null) return null
        // ---- S2/S3: Stub.asInterface ----
        val stubNames = listOf(
            "com.android.internal.appwidget.IAppWidgetService\$Stub",
            "android.appwidget.IAppWidgetService\$Stub",
        )
        val loaders = listOf<Pair<String, ClassLoader?>>(
            "module" to CardProbe::class.java.classLoader,
            "system" to ClassLoader.getSystemClassLoader(),
            "host" to hostClassLoader,
        )
        for (name in stubNames) {
            for ((lname, loader) in loaders) {
                try {
                    val stubClass = if (loader != null) {
                        Class.forName(name, true, loader)
                    } else {
                        Class.forName(name)
                    }
                    val svc = stubClass.getMethod(
                        "asInterface",
                        Class.forName("android.os.IBinder"),
                    ).invoke(null, binder)
                    if (svc != null) {
                        w.write("## service via Stub $name (loader=$lname): ${svc.javaClass.name}\n")
                        w.flush()
                        log("[cardprobe] hostbind: service via Stub $name loader=$lname ok")
                        return svc
                    }
                    w.write("## Stub $name (loader=$lname): asInterface returned null\n")
                    w.flush()
                } catch (t: Throwable) {
                    val cause = unwrap(t)
                    w.write("## Stub $name (loader=$lname) FAILED: ${cause.javaClass.name}: ${cause.message}\n")
                    w.flush()
                    log(
                        "[cardprobe] hostbind: Stub $name loader=$lname failed: " +
                            "${cause.javaClass.name}"
                    )
                }
            }
        }
        return null
    }

    private fun dumpOneBinding(
        svc: Any,
        w: java.io.Writer,
        methods: List<java.lang.reflect.Method>,
        callingPackage: String,
        id: Int,
    ) {
        w.write("=== id=$id via callingPackage=$callingPackage\n")
        val info = invokeServiceByNamePart(
            svc, w, methods, "AppWidgetInfo", callingPackage, id,
        ) as? AppWidgetProviderInfo
        w.write(
            "  provider=${info?.provider} minW=${info?.minWidth} " +
                "minH=${info?.minHeight} updatePeriod=${info?.updatePeriodMillis} " +
                "configure=${info?.configure}\n"
        )
        val opts = invokeServiceByNamePart(
            svc, w, methods, "AppWidgetOptions", callingPackage, id,
        ) as? android.os.Bundle
        if (opts == null) {
            w.write("  options=null\n")
        } else {
            for (k in opts.keySet()) {
                val v = try {
                    @Suppress("DEPRECATION")
                    opts.get(k)
                } catch (t: Throwable) {
                    null
                }
                w.write("  opt $k = $v (${v?.javaClass?.name})\n")
            }
        }
        w.flush()
    }

    /**
     * 服务方法签名全量发现（v2.0.38 改全量）：把代理所实现
     * 接口的**全部**方法（名 + 参数类型全名 + 返回类型）
     * 原样写进 hostbind 文件并返回方法表供按真签名调用。
     * .36 的教训是签名被 ROM 改过、写死必死；.37 只 dump
     * 名字含 AppWidgetIds/Options/Info/Views 的方法——真
     * 方法名若落在过滤词之外（如 getAppWidgetIdsForHost）
     * 就会漏掉，本版不再过滤。接口方法之外再并入代理类
     * 自身的 public 方法（去重），防接口枚举不全。
     */
    private fun discoverServiceMethods(
        svc: Any,
        w: java.io.Writer,
        log: (String) -> Unit,
    ): List<java.lang.reflect.Method> {
        val ifaces = mutableListOf<Class<*>>()
        var c: Class<*>? = svc.javaClass
        while (c != null) {
            try {
                for (i in c.interfaces) {
                    if (i !in ifaces) ifaces.add(i)
                }
            } catch (t: Throwable) {
                // 收接口失败不致命
            }
            c = c.superclass
        }
        val all = mutableListOf<java.lang.reflect.Method>()
        val seen = HashSet<String>()
        fun sigOf(m: java.lang.reflect.Method): String =
            m.name + "(" + m.parameterTypes.joinToString { it.name } + ")"
        for (iface in ifaces) {
            w.write("## interface ${iface.name}\n")
            val ms = try {
                iface.declaredMethods
            } catch (t: Throwable) {
                emptyArray()
            }
            for (m in ms.sortedBy { it.name }) {
                w.write(
                    "  method ${m.name}(" +
                        m.parameterTypes.joinToString { it.name } +
                        ") -> ${m.returnType.name}\n"
                )
                if (seen.add(sigOf(m))) all.add(m)
            }
        }
        try {
            for (m in svc.javaClass.methods) {
                if (seen.add(sigOf(m))) all.add(m)
            }
        } catch (t: Throwable) {
            // 代理类方法收不全不致命，接口表已在手
        }
        w.flush()
        log(
            "[cardprobe] hostbind: signature discovery done " +
                "(${ifaces.size} interfaces, ${all.size} methods)"
        )
        return all
    }

    /**
     * 按名字子串 + 真签名试调（v2.0.38）：候选是签名发现
     * 拿到的方法表里名字含 [namePart] 的全部方法（精确名
     * get<namePart> 优先、其余按名排序）；参数按类型填：
     * String->[pkg]、int->[secondInt]、UserHandle->当前用户，
     * 其余类型放弃该候选。每个候选的成败都写进 [w]；全灭
     * 返回 null。不再按写死签名 getMethod（.36 全灭教训）。
     */
    private fun invokeServiceByNamePart(
        svc: Any,
        w: java.io.Writer,
        methods: List<java.lang.reflect.Method>,
        namePart: String,
        pkg: String,
        secondInt: Int,
    ): Any? {
        val exactName = "get$namePart"
        val candidates = methods
            .filter { it.name.contains(namePart) }
            .sortedWith(compareBy({ it.name != exactName }, { it.name }))
        if (candidates.isEmpty()) {
            w.write("## *$namePart*: no method with this name part on ${svc.javaClass.name}\n")
            w.flush()
            return null
        }
        for (m in candidates) {
            val sig = m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")"
            val args = arrayOfNulls<Any>(m.parameterTypes.size)
            var fillable = true
            for (i in m.parameterTypes.indices) {
                val t = m.parameterTypes[i]
                when {
                    t == String::class.java -> args[i] = pkg
                    t == Int::class.javaPrimitiveType || t == Int::class.java ->
                        args[i] = secondInt
                    t.name == "android.os.UserHandle" ->
                        args[i] = android.os.Process.myUserHandle()
                    else -> fillable = false
                }
            }
            if (!fillable) {
                w.write("## $sig: skipped (unsupported param type)\n")
                w.flush()
                continue
            }
            try {
                val r = m.invoke(svc, *args)
                w.write("## $sig -> ok: ${describeResult(r)}\n")
                w.flush()
                return r
            } catch (t: Throwable) {
                val cause = unwrap(t)
                w.write(
                    "## $sig FAILED: " +
                        "${cause.javaClass.name}: ${cause.message}\n"
                )
                w.flush()
            }
        }
        return null
    }

    private fun describeResult(r: Any?): String = when (r) {
        null -> "null"
        is IntArray -> "int[${r.size}] ${r.joinToString()}"
        is android.os.Bundle -> "Bundle keys=${r.keySet()}"
        else -> "${r.javaClass.name}: $r"
    }

    private fun unwrap(t: Throwable): Throwable {
        return if (t is java.lang.reflect.InvocationTargetException) {
            t.targetException ?: t
        } else {
            t
        }
    }

    private fun probeHostAllocate(ctx: Context, log: (String) -> Unit) {
        var host: AppWidgetHost? = null
        var id = -1
        try {
            host = AppWidgetHost(ctx, HOST_ID)
            log("[cardprobe] host: new AppWidgetHost(hostId=$HOST_ID) ok")
            id = host.allocateAppWidgetId()
            log("[cardprobe] host: allocateAppWidgetId -> $id (no bind attempted)")
        } catch (t: Throwable) {
            log("[cardprobe] host: allocate failed: ${t.javaClass.name}: ${t.message}")
        } finally {
            // 只做卫生清理：把刚分配的 id 删掉，不绑任何真卡片
            try {
                if (host != null && id > 0) {
                    host.deleteAppWidgetId(id)
                    log("[cardprobe] host: cleanup deleteAppWidgetId($id) done")
                }
            } catch (t: Throwable) {
                log("[cardprobe] host: cleanup failed: ${t.javaClass.name}: ${t.message}")
            }
        }
    }
}
