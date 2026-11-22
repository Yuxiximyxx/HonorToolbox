package io.github.yuxiximyxx.honortoolbox.palmpurify

import android.util.Log
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONObject
import java.io.File

/**
 * 旧版 PalmPurify（独立 App，包名 io.github.yuxiximyxx.palmpurify）
 * 的配置迁移（v1.6.0 合入工具箱时新增，照 LibraryPlus 的
 * LpMigration 先例，两段式）：
 *
 * 旧配置有两处，都在旧包名下、工具箱设置应用直读不到：
 *  1. 旧 RemotePreferences 组 "palmpurify"（LSPosed 落在旧包的
 *     shared_prefs/palmpurify.xml）——用户在独立版设置页改过的值
 *     最终都经服务写到这里；
 *  2. 旧私有 prefs "palmpurify_local"——服务未绑定时的本地兜底，
 *     每个键带 `<key>_explicit` 标记（外加 immersive 的历史
 *     标记 immersive_explicit），只有带标记的才是用户真改过的。
 *
 * 迁移路径与 LibraryPlus 同构：
 *  - hook 侧（掌盟进程内，[exportOldConfigFromHook]）：用
 *    XSharedPreferences 读上面两处，按「local 带 explicit 标记
 *    的值优先、其次远程组」合并成 JSON，写到公共落盘通道
 *    /sdcard/Download/HonorToolbox/palmpurify/migration.json；
 *  - 设置侧（应用页掌盟子页打开时，[importIfNeeded]）：读这份
 *    JSON，只填 [PalmPrefs] 里还没有的键（不覆盖工具箱时代
 *    用户已改过的值），并落 done 标记只跑一次；同时兜底直读
 *    一次 XSharedPreferences（能读到就同样处理）。
 * 全程静默：读不到旧配置就用默认值，不阻塞、不报错。
 *
 * 映射口径：9 个开关的键名/默认值与独立版完全一致，逐键直搬。
 * 其中 slim_tabbar 的用户值直搬为新版「底栏精简」总闸——总闸
 * 语义不变（关=底栏原样），逐项显隐的缺省又与原精简结果
 * 一致（首页/我的显示、其余隐藏），所以他若改过总开关，
 * 迁移后效果与原来一致。
 */
object PalmMigration {

    private const val OLD_PKG = "io.github.yuxiximyxx.palmpurify"
    private const val OLD_LOCAL_PREFS = "palmpurify_local"
    private const val LEGACY_IMMERSIVE_EXPLICIT = "immersive_explicit"
    private const val DONE_KEY = "migration_done"
    private const val MIGRATION_FILE = "migration.json"

    private val KEYS: List<Pair<String, Boolean>> = listOf(
        ModulePrefs.KEY_IMMERSIVE to ModulePrefs.DEFAULT_IMMERSIVE,
        ModulePrefs.KEY_BLOCK_SPLASH_AD to ModulePrefs.DEFAULT_BLOCK_SPLASH_AD,
        ModulePrefs.KEY_SLIM_TABBAR to ModulePrefs.DEFAULT_SLIM_TABBAR,
        ModulePrefs.KEY_HIDE_CENTER_BTN to ModulePrefs.DEFAULT_HIDE_CENTER_BTN,
        ModulePrefs.KEY_SLIM_HOME_MODULES to ModulePrefs.DEFAULT_SLIM_HOME_MODULES,
        ModulePrefs.KEY_BLOCK_PROMO_DIALOG to ModulePrefs.DEFAULT_BLOCK_PROMO_DIALOG,
        ModulePrefs.KEY_BLOCK_UPDATE_DIALOG to ModulePrefs.DEFAULT_BLOCK_UPDATE_DIALOG,
        ModulePrefs.KEY_BLOCK_SHAKE to ModulePrefs.DEFAULT_BLOCK_SHAKE,
        ModulePrefs.KEY_DEBUG_LOG to ModulePrefs.DEFAULT_DEBUG_LOG,
    )

    // ── hook 侧：导出旧配置 JSON 到公共落盘通道 ──

    fun exportOldConfigFromHook() {
        runCatching {
            val merged = linkedMapOf<String, Boolean>()
            // 远程组（优先级低，先铺底）
            readXShared(ModulePrefs.GROUP)?.let { xsp ->
                for ((key, def) in KEYS) {
                    if (xsp.contains(key)) {
                        merged[key] = xsp.getBoolean(key, def)
                    }
                }
            }
            // 本地兜底（只认 explicit 标记，优先级高，覆盖远程组）
            readXShared(OLD_LOCAL_PREFS)?.let { xsp ->
                for ((key, def) in KEYS) {
                    if (isExplicit(xsp, key)) {
                        merged[key] = xsp.getBoolean(key, def)
                    }
                }
            }
            if (merged.isEmpty()) return
            val json = JSONObject()
            for ((key, value) in merged) {
                json.put(key, value)
            }
            val dir = File(FilePrefs.DIR).apply { mkdirs() }
            File(dir, MIGRATION_FILE).writeText(json.toString())
        }.onFailure {
            Log.w(ModulePrefs.LOG_TAG, "导出旧配置失败，跳过迁移：${it.message}")
        }
    }

    // ── 设置侧：子页打开时导入 ──

    /** 返回 true 表示这次有键被导入（界面应重读配置刷新）。 */
    fun importIfNeeded(): Boolean {
        val store = PalmPrefs.store
        if (store.getBoolean(DONE_KEY, false)) return false
        var imported = false
        val fill: (String, Boolean) -> Unit = { key, value ->
            if (!store.contains(key)) {
                store.edit().putBoolean(key, value).apply()
                imported = true
            }
        }
        // 通道一：hook 侧导出的 JSON
        runCatching {
            val file = File(FilePrefs.DIR, MIGRATION_FILE)
            if (file.isFile) {
                val json = JSONObject(file.readText())
                for ((key, _) in KEYS) {
                    if (json.has(key)) {
                        fill(key, json.getBoolean(key))
                    }
                }
            }
        }
        // 通道二：兜底直读旧 XSharedPreferences（远程组 + local explicit）
        runCatching {
            readXShared(ModulePrefs.GROUP)?.let { xsp ->
                for ((key, def) in KEYS) {
                    if (xsp.contains(key)) {
                        fill(key, xsp.getBoolean(key, def))
                    }
                }
            }
            readXShared(OLD_LOCAL_PREFS)?.let { xsp ->
                for ((key, def) in KEYS) {
                    if (isExplicit(xsp, key)) {
                        fill(key, xsp.getBoolean(key, def))
                    }
                }
            }
        }
        store.edit().putBoolean(DONE_KEY, true).apply()
        return imported
    }

    private fun readXShared(name: String): XSharedPreferences? = runCatching {
        val xsp = XSharedPreferences(OLD_PKG, name)
        if (xsp.file.canRead()) {
            xsp.reload()
            xsp
        } else {
            null
        }
    }.getOrNull()

    private fun isExplicit(xsp: XSharedPreferences, key: String): Boolean {
        if (xsp.getBoolean("${key}_explicit", false)) return true
        return key == ModulePrefs.KEY_IMMERSIVE &&
            xsp.getBoolean(LEGACY_IMMERSIVE_EXPLICIT, false)
    }
}
