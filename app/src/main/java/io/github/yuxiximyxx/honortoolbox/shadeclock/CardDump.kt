package io.github.yuxiximyxx.honortoolbox.shadeclock

import io.github.yuxiximyxx.honortoolbox.BuildConfig

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 选卡目录定案 dump（v2.0.33）：进设置页「头部卡片」页时
 * 生成一次 /sdcard/Download/shadeclock_carddump.txt（经
 * PublicStore 的 MediaStore 通道写，与戳记同一条合规路径）。
 *
 * 要定案的两件事：
 * 1. 设置进程视角的枚举范围。.32 真机上选卡页里天气
 *    （com.hihonor.android.totemweather）、日历、时钟、
 *    智慧空间四组整组缺失，而 .31 探针在 SystemUI 进程
 *    里能看到全量 554 个——两侧调的是同一个
 *    AppWidgetManager.installedProviders，差别只在调用方
 *    身份。本 dump 开头就记本进程看到的总数，并交叉用
 *    queryBroadcastReceivers(APPWIDGET_UPDATE) 再数一遍，
 *    两条路的差就是调用方过滤的实锤范围。
 * 2. 系统「服务卡片」目录的精选标记（v2.0.37 已定案：
 *    receiver meta-data 的 com.hihonor.android.launcher.
 *    card_* 键，见 MainActivity.isHonorServiceCard）。本
 *    dump 继续逐个记录可读字段与 metaData 全部键名，并
 *    补记 card_* 键的原始值，供核对 card_size 的值形态。
 *
 * 本进程只写一次；全程尽力而为、异常全吞，诊断件不许
 * 影响设置页本身。
 */
object CardDump {

    private val done = AtomicBoolean(false)

    /** 十个服务卡片来源应用（与选卡页置顶名单同一份口径）。 */
    private val KNOWN_PACKAGES = listOf(
        "com.hihonor.android.totemweather",
        "com.hihonor.calendar",
        "com.hihonor.deskclock",
        "com.hihonor.contacts",
        "com.hihonor.photos",
        "com.hihonor.wallet",
        "com.hihonor.magichome",
        "com.hihonor.parentcontrol",
        "com.hihonor.android.launcher",
        "com.hihonor.notepad",
    )

    fun writeOnce(context: Context) {
        if (!done.compareAndSet(false, true)) return
        try {
            val text = build(context)
            PublicStore.writeFile(
                context,
                "Download",
                "shadeclock_carddump.txt",
                text.toByteArray(Charsets.UTF_8),
            )
        } catch (t: Throwable) {
            // 尽力而为：写不出去也不许影响设置页
        }
    }

    private fun build(context: Context): String {
        val pm = context.packageManager
        val awm = AppWidgetManager.getInstance(context)
        val dm = context.resources.displayMetrics
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sb.appendLine("HonorShadeClock card dump (settings-process view, v${BuildConfig.VERSION_NAME})")
        sb.appendLine(
            "time=${sdf.format(Date())} pid=${Process.myPid()} uid=${Process.myUid()}",
        )
        sb.appendLine("density=${dm.density} screenPx=${dm.widthPixels}x${dm.heightPixels}")

        val providers: List<AppWidgetProviderInfo> = try {
            awm?.installedProviders ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        sb.appendLine("installedProviders total (this process)=${providers.size}")

        val receivers = try {
            pm.queryBroadcastReceivers(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
                PackageManager.GET_META_DATA,
            )
        } catch (t: Throwable) {
            emptyList()
        }
        val receiverPkgs = receivers.map { it.activityInfo.packageName }.toSet()
        sb.appendLine(
            "queryBroadcastReceivers(APPWIDGET_UPDATE) total=${receivers.size} " +
                "packages=${receiverPkgs.size}",
        )

        sb.appendLine("---- known source packages ----")
        for (pkg in KNOWN_PACKAGES) {
            val n = providers.count { it.provider.packageName == pkg }
            val viaReceivers = if (pkg in receiverPkgs) "yes" else "no"
            sb.appendLine("known $pkg: providers=$n viaReceivers=$viaReceivers")
        }

        sb.appendLine("---- providers (settings-process view) ----")
        val sorted = providers.sortedWith(
            compareBy<AppWidgetProviderInfo> { it.provider.packageName }
                .thenBy { it.provider.className },
        )
        for (info in sorted) {
            val comp = info.provider
            val label = try {
                info.loadLabel(pm) ?: ""
            } catch (t: Throwable) {
                ""
            }
            var metaKeys = ""
            var cardMeta = ""
            try {
                val ri = pm.getReceiverInfo(comp, PackageManager.GET_META_DATA)
                val bundle = ri.metaData
                metaKeys = bundle?.keySet()?.sorted()?.joinToString(",") ?: ""
                // v2.0.37：服务卡片身份标记已定案在 card_* 键
                // （见 MainActivity.isHonorServiceCard）；本版把
                // 这些键的原始值一并记下——card_size 的值形态
                // （字符串/资源引用/整数代码）决定选卡页尺寸
                // 文案能不能直接用它，留证供下一轮核对。
                // v2.0.38：Int 资源引用（>=0x7f000000）再回源
                // 应用资源解成文本一并记下，与 cardxml 的
                // meta-values 同口径。
                if (bundle != null) {
                    val parts = mutableListOf<String>()
                    for (k in bundle.keySet().sorted()) {
                        if (!k.startsWith("com.hihonor.android.launcher.")) continue
                        @Suppress("DEPRECATION")
                        val v = try {
                            bundle.get(k)
                        } catch (t: Throwable) {
                            null
                        }
                        parts.add(
                            when (v) {
                                is Int -> {
                                    if (v >= 0x7f000000) {
                                        val resolved = try {
                                            pm.getResourcesForApplication(comp.packageName)
                                                .getString(v)
                                        } catch (t: Throwable) {
                                            null
                                        }
                                        if (resolved != null) {
                                            "$k=$v(0x${Integer.toHexString(v)})->\"$resolved\""
                                        } else {
                                            "$k=$v(0x${Integer.toHexString(v)})-><unresolved>"
                                        }
                                    } else {
                                        "$k=$v(0x${Integer.toHexString(v)})"
                                    }
                                }
                                else -> "$k=$v"
                            },
                        )
                    }
                    if (parts.isNotEmpty()) {
                        cardMeta = " cardMeta=[${parts.joinToString(";")}]"
                    }
                }
            } catch (t: Throwable) {
                metaKeys = "ERR:${t.javaClass.simpleName}"
            }
            sb.appendLine(
                "pkg=${comp.packageName} cls=${comp.className} label=$label " +
                    "cell=${info.targetCellWidth}x${info.targetCellHeight} " +
                    "min=${info.minWidth}x${info.minHeight} " +
                    "cat=${info.widgetCategory} resize=${info.resizeMode} " +
                    "descRes=${info.descriptionRes} " +
                    "previewLayout=${info.previewLayout} " +
                    "previewImage=${info.previewImage} " +
                    "configure=${info.configure?.flattenToString() ?: "-"} " +
                    "updatePeriod=${info.updatePeriodMillis} " +
                    "metaKeys=[$metaKeys]$cardMeta",
            )
        }
        return sb.toString()
    }
}
