package io.github.yuxiximyxx.honortoolbox.navwake

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
        return writeFile(context, text)
    }

    private fun writeFile(context: Context, text: String): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = arrayOf(RELATIVE_PATH, NavConfig.FILE_NAME)
        // 先删尽同名候选，保证只剩自己即将写的这一份
        runCatching { resolver.delete(collection, selection, args) }
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
