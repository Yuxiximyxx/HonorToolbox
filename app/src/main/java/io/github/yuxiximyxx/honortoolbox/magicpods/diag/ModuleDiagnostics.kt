package io.github.yuxiximyxx.honortoolbox.magicpods.diag

import android.content.Context
import android.content.pm.PackageManager
import java.util.zip.ZipFile

/**
 * ModuleDiagnostics —— 在手机上直接自检「LSPosed 能不能把本 APK 认成模块」。
 *
 * v1.4.0 合入荣耀工具箱时按传统 Xposed API 重写（原版按 libxposed 现代
 * 模块声明 META-INF/xposed/* 判定，合入后失效）：
 * 传统模块的识别条件（LSPosed ModuleUtil.isLegacyModule）是
 *   1. APK 内有 assets/xposed_init 且非空（入口类清单）；
 *   2. manifest meta-data 含 xposedminversion。
 * 作用域来自 manifest xposedscope 元数据指向的字符串数组。
 * [Report.recognized] 与 LSPosed 实际行为一一对应。
 */
object ModuleDiagnostics {

    private const val ENTRY_XPOSED_INIT = "assets/xposed_init"

    private val LEGACY_KEYS = listOf("xposedminversion", "xposedmodule", "xposeddescription", "xposedscope")

    /** 工具箱声明的全部作用域（与 res/values/strings.xml 的 xposedscope 保持一致，仅作展示对照）。 */
    private val EXPECTED_SCOPE = listOf(
        "com.android.bluetooth",
        "com.android.systemui",
        "com.hihonor.audioaccessorymanager",
        "com.hihonor.imedia.sws",
        "com.hihonor.controlcenter",
    )

    data class Report(
        val apkPath: String,
        val xposedInitEntries: List<String>,
        val legacyKeys: List<String>,
        val scopeList: List<String>,
    ) {
        /** 与 LSPosed 相同：xposed_init 非空且含 xposedminversion 元数据即为模块。 */
        val recognized: Boolean
            get() = xposedInitEntries.isNotEmpty() && legacyKeys.contains("xposedminversion")

        /** 逐项诊断，供 UI 展示。 */
        fun checks(): List<Check> = buildList {
            add(
                if (xposedInitEntries.isNotEmpty()) Check("传统识别 · assets/xposed_init", true, xposedInitEntries.joinToString())
                else Check("传统识别 · assets/xposed_init", false, "APK 内找不到该条目或为空")
            )
            add(
                if (legacyKeys.contains("xposedminversion")) Check("传统识别 · xposedminversion 元数据", true, legacyKeys.joinToString())
                else Check("传统识别 · xposedminversion 元数据", false, "manifest 中未声明")
            )
            add(
                if (recognized) Check("LSPosed 是否识别为模块", true, "legacy（传统 API）")
                else Check("LSPosed 是否识别为模块", false, "识别条件未满足 —— 管理器里不会出现")
            )
            add(
                if (scopeList.isNotEmpty()) Check("作用域来源", true, "manifest xposedscope（${scopeList.size} 项）")
                else Check("作用域来源", false, "xposedscope 数组读不到")
            )
            val missing = EXPECTED_SCOPE.filter { it !in scopeList }
            add(
                if (missing.isEmpty()) Check("耳机五宿主作用域", true, EXPECTED_SCOPE.joinToString())
                else Check("耳机五宿主作用域", false, "缺失：${missing.joinToString()}")
            )
        }
    }

    data class Check(val label: String, val ok: Boolean?, val detail: String?)

    fun collect(context: Context): Report = runCatching {
        val apkPath = context.applicationInfo.sourceDir
        var initEntries: List<String> = emptyList()
        runCatching {
            ZipFile(apkPath).use { zip ->
                zip.getEntry(ENTRY_XPOSED_INIT)?.let { entry ->
                    initEntries = zip.getInputStream(entry).bufferedReader()
                        .readLines().map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
        }

        val legacyKeys = runCatching {
            val metaData = context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
            LEGACY_KEYS.filter { metaData?.containsKey(it) == true }
        }.getOrDefault(emptyList())

        val scopeList = runCatching {
            val resId = context.resources.getIdentifier("xposedscope", "array", context.packageName)
            if (resId != 0) context.resources.getStringArray(resId).toList() else emptyList()
        }.getOrDefault(emptyList())

        Report(
            apkPath = apkPath,
            xposedInitEntries = initEntries,
            legacyKeys = legacyKeys,
            scopeList = scopeList,
        )
    }.getOrElse { error ->
        Report(
            apkPath = "读取失败: ${error.message}",
            xposedInitEntries = emptyList(),
            legacyKeys = emptyList(),
            scopeList = emptyList(),
        )
    }
}
