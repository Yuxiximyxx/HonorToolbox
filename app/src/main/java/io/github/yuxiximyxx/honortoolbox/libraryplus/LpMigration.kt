package io.github.yuxiximyxx.honortoolbox.libraryplus

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import de.robv.android.xposed.XSharedPreferences
import java.io.File

/**
 * 旧版 LibraryPlus（独立 App，包名 io.github.yuxiximyxx.libraryplus）
 * 的配置迁移（v1.3.0 合入工具箱时新增）。
 *
 * 旧配置的三个来源按 ts 取最新：
 *  1. 旧 XSharedPreferences（文件 libraryplus，MODE_WORLD_READABLE）——
 *     hook 进程可直接读：[readOldXSharedConfig] 供 hook 侧 loadEarly
 *     按 ts 参与候选；[exportOldConfigFromHook] 把它导出成 JSON 写到
 *     公共落盘通道 Download/HonorToolbox/libraryplus_migration.json，
 *     供设置应用侧读取（宿主是系统应用、直写 /sdcard 路径）。
 *  2. 旧私有 prefs——工具箱读不到旧 App 的私有目录，只能经旧
 *     ConfigProvider 的 call("get") 取（其私有 prefs 正是该
 *     Provider 的数据源）：[readOldProvider]。
 *  3. 上面的落盘迁移文件：[readMigrationFile]（MediaStore 读）。
 *
 * 触发时机：设置应用每次打开「资源库增强」子页时跑一次
 * [importIfNewer]；hook 侧每次宿主进程加载时跑一次导出 +
 * loadEarly 候选。导入条件：候选 ts 严格大于工具箱现有配置 ts
 * 且 ts > 0（全默认空配置不导入）。全部通道都读不到 / 解析失败
 * 时静默放弃、继续用工具箱现有配置（首次即默认值），不抛异常、
 * 不阻塞界面。导入成功后经 [ConfigStore.save] 落盘到工具箱自己
 * 的三通道（私有 prefs + 新 xshared + 变更广播），旧 App 之后
 * 改配置也不会再覆盖（ts 只增、且导入后工具箱 ts 已是最新）。
 */
object LpMigration {

    private const val OLD_PKG = "io.github.yuxiximyxx.libraryplus"
    private const val OLD_PREFS_SHARED = "libraryplus"
    private const val OLD_AUTHORITY = "io.github.yuxiximyxx.libraryplus.config"
    private const val OLD_EXTRA_CONFIG = "config"

    private const val REL_DIR = "Download/HonorToolbox"
    private const val FILE_NAME = "libraryplus_migration.json"
    private val CANDIDATE_NAME = Regex("""libraryplus_migration( \(\d+\))?\.json""")
    private val FILES_URI: Uri = MediaStore.Files.getContentUri("external")

    // ── 通道 1：旧 XSharedPreferences（hook 进程内读） ──

    fun readOldXSharedConfig(): LpConfig? = runCatching {
        val xsp = XSharedPreferences(OLD_PKG, OLD_PREFS_SHARED)
        if (xsp.file.canRead()) {
            xsp.reload()
            LpConfig.fromJson(xsp.getString(Const.KEY_CONFIG, null))
        } else {
            null
        }
    }.getOrNull()

    /** hook 侧导出：旧 xshared 配置 → 落盘迁移文件（已有更新的文件不覆盖）。 */
    fun exportOldConfigFromHook() {
        runCatching {
            val cfg = readOldXSharedConfig() ?: return
            if (cfg.ts <= 0L) return
            val dir = File("/sdcard", REL_DIR)
            dir.mkdirs()
            val file = File(dir, FILE_NAME)
            val existing = runCatching {
                LpConfig.fromJson(file.readText(Charsets.UTF_8))
            }.getOrNull()
            if (existing != null && existing.ts >= cfg.ts) return
            file.writeText(cfg.toJson(), Charsets.UTF_8)
        }
    }

    // ── 通道 2：旧 ConfigProvider（设置应用侧读旧私有 prefs 的唯一路径） ──

    private fun readOldProvider(ctx: Context): LpConfig? = runCatching {
        val bundle = ctx.contentResolver.call(
            Uri.parse("content://$OLD_AUTHORITY"),
            Const.METHOD_GET,
            null,
            null,
        )
        LpConfig.fromJson(bundle?.getString(OLD_EXTRA_CONFIG))
    }.getOrNull()

    // ── 通道 3：落盘迁移文件（设置应用侧经 MediaStore 读） ──

    private fun readMigrationFile(ctx: Context): LpConfig? = runCatching {
        val cr = ctx.contentResolver
        var newestUri: Uri? = null
        var newestModified = -1L
        cr.query(
            FILES_URI,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$REL_DIR/%"),
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val rel = cursor.getString(relIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                if (rel.trim('/') != REL_DIR) continue
                if (!CANDIDATE_NAME.matches(name)) continue
                val modified = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                if (modified >= newestModified) {
                    newestModified = modified
                    newestUri = ContentUris.withAppendedId(FILES_URI, cursor.getLong(idIdx))
                }
            }
        }
        val uri = newestUri ?: return null
        val text = cr.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return null
        LpConfig.fromJson(text)
    }.getOrNull()

    // ── 设置应用侧导入 ──

    /**
     * 三通道候选按 ts 取最新，严格新于工具箱现有配置才导入。
     * 返回是否发生了导入；任何失败都返回 false 并保持现状。
     */
    fun importIfNewer(ctx: Context): Boolean = runCatching {
        val current = ConfigStore.load(ctx)
        val best = listOfNotNull(readOldProvider(ctx), readMigrationFile(ctx))
            .maxByOrNull { it.ts } ?: return false
        if (best.ts <= 0L || best.ts <= current.ts) return false
        ConfigStore.save(ctx, best)
        ConfigStore.flush(ctx)
        true
    }.getOrDefault(false)
}
