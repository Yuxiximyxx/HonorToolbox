package io.github.yuxiximyxx.honortoolbox

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * 设置侧读写功能总开关（features.txt）：经 MediaStore 写公共
 * Download/HonorToolbox 目录（普通应用的合规写路径），写前删
 * 尽本安装拥有的全部同名候选、写后回读校验，与各功能落盘
 * 通道同一套口径（防卸载重装后写入落到副本、正本读不到）。
 * 读取时在自有候选里取 DATE_MODIFIED 最新的一份解析。
 */
object ToolboxStore {

    private const val REL_DIR = "Download/HonorToolbox"
    private const val FILE_NAME = "features.txt"
    private val CANDIDATE_NAME = Regex("""features( \(\d+\))?\.txt""")
    private val FILES_URI: Uri = MediaStore.Files.getContentUri("external")

    private data class OwnFile(val uri: Uri, val modifiedSec: Long)

    private fun ownCandidates(context: Context): List<OwnFile> {
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

    /** 设置侧读取当前全部开关状态（读不到返回空表，调用方按默认显示）。 */
    fun readFeatureStates(context: Context): Map<String, Boolean> {
        val newest = ownCandidates(context).maxByOrNull { it.modifiedSec } ?: return emptyMap()
        return try {
            val text = context.contentResolver.openInputStream(newest.uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return emptyMap()
            FeatureGate.parse(text)
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    /** 改一个功能的开关并落盘（保留其他功能的现有状态）；返回是否回读校验通过。 */
    fun writeFeatureEnabled(context: Context, feature: String, enabled: Boolean): Boolean {
        val states = HashMap(readFeatureStates(context))
        states[feature] = enabled
        val bytes = FeatureGate.format(states).toByteArray(Charsets.UTF_8)
        val cr = context.contentResolver
        for (own in ownCandidates(context)) {
            try {
                cr.delete(own.uri, null, null)
            } catch (t: Throwable) {
                // 尽力而为
            }
        }
        val uri = try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$REL_DIR/")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }
            cr.insert(FILES_URI, values)
        } catch (t: Throwable) {
            null
        } ?: return false
        try {
            cr.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return false
        } catch (t: Throwable) {
            return false
        }
        // 回读校验：任一自有候选内容与写入一致即算落盘
        for (own in ownCandidates(context)) {
            try {
                val data = cr.openInputStream(own.uri)?.use { it.readBytes() } ?: continue
                if (data.contentEquals(bytes)) return true
            } catch (t: Throwable) {
                // 读不出的候选跳过
            }
        }
        return false
    }
}
