package io.github.yuxiximyxx.honortoolbox

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * 模块激活状态文件 /sdcard/Download/HonorToolbox/
 * activation_status.txt —— 免勾自身作用域的激活检测
 * （v1.2.3）。
 *
 * 写入侧在宿主进程里：[ToolboxHook] 分发到任一功能宿主
 * 并跑完该分支的安装后，由正在运行的 hook 代码写一次
 * （进程加载时一次，不进热路径；写失败静默忽略，不影
 * 响 hook 本职）。ShadeClock 的 SystemUI hook 常年写同
 * 目录的 probe 日志，这条落盘路在本机是验过的。内容
 * 三行：versionCode（写文件的这份 hook 代码的版本）、
 * host（宿主包名）、time（毫秒时间戳）。
 *
 * 读取侧在设置应用里，双路：先按直路读文件（本应用自
 * 有文件与宽松 ROM 下可读），读不到再走 MediaStore 查
 * 自有候选。设置应用每次刷新会先经 MediaStore 占位建
 * 一份空文件（[ensurePlaceholder]）：宿主进程按直路覆
 * 盖写这份已存在的文件时，所有权仍归设置应用，设置
 * 侧经 MediaStore 读回不受 scoped storage 挡路。
 *
 * 判定（首页大卡，三态）：自检标记命中，或本文件
 * versionCode 不低于当前安装版本 → 已激活；文件在但
 * versionCode 落后 → 旧版 hook 还在宿主里跑（中间
 * 态）；两者皆无 → 未激活。
 */
object ActivationStatus {

    private const val REL_DIR = "Download/HonorToolbox"
    private const val FILE_NAME = "activation_status.txt"
    private const val DIRECT_PATH = "/sdcard/Download/HonorToolbox/activation_status.txt"
    private val CANDIDATE_NAME = Regex("""activation_status( \(\d+\))?\.txt""")
    private val FILES_URI: Uri = MediaStore.Files.getContentUri("external")

    data class FileInfo(val versionCode: Int, val host: String?, val timeMillis: Long?)

    /** 宿主进程侧写状态文件（hook 入口在各宿主分支调用）；失败静默。 */
    fun writeFromHook(hostPackage: String) {
        runCatching {
            val dir = File("/sdcard/Download/HonorToolbox")
            dir.mkdirs()
            File(dir, FILE_NAME).writeText(
                "versionCode=${BuildConfig.VERSION_CODE}\n" +
                    "host=$hostPackage\n" +
                    "time=${System.currentTimeMillis()}\n",
            )
        }
    }

    /** 设置侧：没有自有候选时经 MediaStore 占位建一份（已有则不动）。 */
    fun ensurePlaceholder(context: Context) {
        runCatching {
            if (ownCandidates(context).isNotEmpty()) return
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$REL_DIR/")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }
            val uri = context.contentResolver.insert(FILES_URI, values) ?: return
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write("# activation status placeholder\n".toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** 设置侧读状态文件；读不到或内容无有效 versionCode 返回 null。 */
    fun read(context: Context): FileInfo? {
        // 直路：自有文件与宽松 ROM 下可读
        runCatching {
            parse(File(DIRECT_PATH).readText())?.let { return it }
        }
        // MediaStore 自有候选，新的优先，取第一份能解析的
        for (own in ownCandidates(context)) {
            val parsed = runCatching {
                val text = context.contentResolver.openInputStream(own.uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@runCatching null
                parse(text)
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parse(text: String): FileInfo? {
        var vc: Int? = null
        var host: String? = null
        var time: Long? = null
        for (line in text.lineSequence()) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "versionCode" -> vc = value.toIntOrNull()
                "host" -> host = value.takeIf { it.isNotEmpty() }
                "time" -> time = value.toLongOrNull()
            }
        }
        return vc?.let { FileInfo(it, host, time) }
    }

    private data class OwnFile(val uri: Uri, val modifiedSec: Long)

    /** 本应用在 Download/HonorToolbox 下拥有的同名候选（按修改时间新→旧）。 */
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
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            // 尽力而为
        }
        return out.sortedByDescending { it.modifiedSec }
    }
}
