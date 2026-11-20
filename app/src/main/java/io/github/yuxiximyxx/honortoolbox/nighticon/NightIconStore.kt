package io.github.yuxiximyxx.honortoolbox.nighticon

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * 设置侧把图标日夜切换的配置与图标 zip 写进落盘通道
 * /sdcard/Download/HonorToolbox/nighticon/（经 MediaStore，
 * 普通应用对公共 Download 的合规写路径）。与工具箱其他落盘
 * 通道同口径：写前删尽本安装拥有的同名候选再写一份，写后
 * 回读校验；读侧（桌面进程）认正本与副本、取最新。
 */
object NightIconStore {

    private val FILES_URI: Uri = MediaStore.Files.getContentUri("external")

    private data class OwnFile(val uri: Uri, val modifiedSec: Long)

    private fun ownCandidates(context: Context, name: String): List<OwnFile> {
        val pattern = Regex("""${Regex.escape(name)}( \(\d+\))?""")
        val cr = context.contentResolver
        val out = ArrayList<OwnFile>()
        try {
            cr.query(
                FILES_URI,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(NightIconConfig.REL_DIR + "/%"),
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val modIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val rel = cursor.getString(relIdx) ?: continue
                    val display = cursor.getString(nameIdx) ?: continue
                    if (rel.trim('/') != NightIconConfig.REL_DIR) continue
                    if (!pattern.matches(display)) continue
                    out.add(
                        OwnFile(
                            ContentUris.withAppendedId(FILES_URI, cursor.getLong(idIdx)),
                            if (modIdx >= 0) cursor.getLong(modIdx) else 0L,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            // 尽力而为
        }
        return out
    }

    private fun deleteCandidates(context: Context, name: String) {
        val cr = context.contentResolver
        for (own in ownCandidates(context, name)) {
            try {
                cr.delete(own.uri, null, null)
            } catch (t: Throwable) {
                // 尽力而为
            }
        }
    }

    private fun insertAndWrite(context: Context, name: String, write: (java.io.OutputStream) -> Unit): Uri? {
        val cr = context.contentResolver
        val uri = try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, NightIconConfig.REL_DIR + "/")
            }
            cr.insert(FILES_URI, values)
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            cr.openOutputStream(uri, "wt")?.use { write(it) } ?: return null
            uri
        } catch (t: Throwable) {
            null
        }
    }

    /** 把本地文件下发为落盘目录里的同名文件；回读长度一致才算成功。 */
    fun writeFile(context: Context, name: String, src: File): Boolean {
        deleteCandidates(context, name)
        insertAndWrite(context, name) { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: return false
        for (own in ownCandidates(context, name)) {
            try {
                val len = context.contentResolver.openInputStream(own.uri)
                    ?.use { it.readBytes().size.toLong() } ?: continue
                if (len == src.length()) return true
            } catch (t: Throwable) {
                // 读不出的候选跳过
            }
        }
        return false
    }

    fun deleteFile(context: Context, name: String) {
        deleteCandidates(context, name)
    }

    /** 写 config.txt（内容回读一致才算成功）。 */
    fun writeConfig(context: Context, state: NightIconConfig.State): Boolean {
        val bytes = NightIconConfig.format(state).toByteArray(Charsets.UTF_8)
        deleteCandidates(context, NightIconConfig.CONFIG_NAME)
        insertAndWrite(context, NightIconConfig.CONFIG_NAME) { it.write(bytes) } ?: return false
        for (own in ownCandidates(context, NightIconConfig.CONFIG_NAME)) {
            try {
                val data = context.contentResolver.openInputStream(own.uri)
                    ?.use { it.readBytes() } ?: continue
                if (data.contentEquals(bytes)) return true
            } catch (t: Throwable) {
                // 读不出的候选跳过
            }
        }
        return false
    }

    /** 设置侧读当前落盘配置（自有候选里取修改时间最新的一份）。 */
    fun readState(context: Context): NightIconConfig.State? {
        val newest = ownCandidates(context, NightIconConfig.CONFIG_NAME)
            .maxByOrNull { it.modifiedSec } ?: return null
        return try {
            val text = context.contentResolver.openInputStream(newest.uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
            NightIconConfig.parse(text)
        } catch (t: Throwable) {
            null
        }
    }
}
