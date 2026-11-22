package io.github.yuxiximyxx.honortoolbox.navwake

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import io.github.yuxiximyxx.honortoolbox.navwake.config.NavConfig

/**
 * NavWakeStore —— 设置侧把本地镜像偏好整份同步到落盘文件的通道。
 *
 * 落点：/sdcard/Download/HonorToolbox/navwake.txt（键名与原模块 prefs 完全一致）。
 * 取代原版 ServiceBridge 的「远程 prefs 推送」：写后删旧候选 + 回读逐字校验，
 * 与工具箱其它落盘功能同一套已验模式。hook 侧每次拦截现读该文件。
 */
object NavWakeStore {

    private const val RELATIVE_PATH = "Download/HonorToolbox"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NavConfig.PREFS_NAME, Context.MODE_PRIVATE)

    fun readEnabled(context: Context): Boolean =
        prefs(context).getBoolean("redirect_enabled", true)

    /** 首页开关用：改镜像 + 同步落盘。返回落盘是否成功。 */
    fun setEnabled(context: Context, value: Boolean): Boolean {
        prefs(context).edit().putBoolean("redirect_enabled", value).apply()
        return syncFromPrefs(context)
    }

    /**
     * 最近一次落盘同步的结果（v1.6.2）：原先 [syncFromPrefs]
     * 的返回值在 NavConfig.onChanged 里被直接丢弃、写失败
     * 全程静默，设置页与真机表现对不上时无从判断。这里记
     * 下来供设置页明示（与快服务改向的「同步失败」同口径）。
     */
    @Volatile
    var lastSyncOk: Boolean? = null
        private set

    /** 把本地镜像 prefs 整份序列化到落盘文件并回读校验。 */
    fun syncFromPrefs(context: Context): Boolean {
        val p = prefs(context)
        val selected = (p.getStringSet("selected_packages", emptySet()) ?: emptySet()).sorted()
        val text = buildString {
            append("redirect_enabled=").append(p.getBoolean("redirect_enabled", true)).append('\n')
            append("tap_back_enabled=").append(p.getBoolean("tap_back_enabled", false)).append('\n')
            append("default_target=").append(p.getString("default_target", "") ?: "").append('\n')
            append("selected_packages=").append(selected.joinToString(",")).append('\n')
            append("log_level=").append(p.getInt("log_level", 1)).append('\n')
            append("probe_mode=").append(p.getBoolean("probe_mode", false)).append('\n')
            append("probe_assist=").append(p.getBoolean("probe_assist", true)).append('\n')
            append("probe_start_activity=").append(p.getBoolean("probe_start_activity", true)).append('\n')
            append("probe_brain=").append(p.getBoolean("probe_brain", true)).append('\n')
            append("probe_magicvoice_activity=").append(p.getBoolean("probe_magicvoice_activity", true)).append('\n')
        }
        val ok = writeFile(context, text)
        lastSyncOk = ok
        return ok
    }

    /** 候选文件名：正本与 MediaStore 改名产生的「navwake (n).txt」副本。 */
    private val CANDIDATE_NAME = Regex("""navwake( \(\d+\))?\.txt""")

    /**
     * 删尽目录下全部同名候选（v1.6.4）：旧实现用
     * 「RELATIVE_PATH = 'Download/HonorToolbox' 且
     * DISPLAY_NAME = 'navwake.txt'」整批删除——RELATIVE_PATH
     * 入库带尾斜杠、等值查不中，且精确名匹配不到 (n) 副本，
     * 实际一份都没删掉；随后每次 insert 都被系统改名成新
     * 副本，真机目录里堆到 (7)、正本停在旧内容（LSPosed
     * 日志实证 hook 恒读旧配置）。现改为先查出全部候选条目
     * （名字 LIKE 粗筛 + 正则整名校验 + 目录归属校验），再
     * 逐 Uri 删除。
     */
    private fun deleteAllCandidates(resolver: ContentResolver, collection: Uri) {
        val uris = ArrayList<Uri>()
        runCatching {
            resolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("navwake%"),
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val rel = cursor.getString(relIdx) ?: continue
                    if (rel.trim('/') != RELATIVE_PATH) continue
                    if (!CANDIDATE_NAME.matches(name)) continue
                    uris.add(ContentUris.withAppendedId(collection, cursor.getLong(idIdx)))
                }
            }
        }
        for (uri in uris) {
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private fun writeFile(context: Context, text: String): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // 先删尽全部同名候选（含 (n) 副本），保证只剩自己即将写的这一份
        deleteAllCandidates(resolver, collection)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, NavConfig.FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }
        val uri: Uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
        val written = runCatching {
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        }.getOrDefault(false)
        if (!written) return false
        // 回读逐字校验
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } == text
        }.getOrDefault(false)
    }
}
