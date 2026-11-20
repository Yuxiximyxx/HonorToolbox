package io.github.yuxiximyxx.honortoolbox.quickgesture

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * 改向配置的跨进程通道（沿用 HonorShadeClock 在小乐真机验过的
 * 落盘通道）：设置应用把配置写成小文件
 * /sdcard/Download/HonorToolbox/quickgesture.txt（经 MediaStore 写入，
 * 普通应用对公共 Download 目录的合规写路径）；快服务进程里的
 * hook 侧用普通文件 IO 读（服务中心是系统应用、读 Download 已
 * 被同类模块的探针日志实证可行），每次手势命中时现读，改目标
 * 即时生效、不用强停快服务。
 *
 * config.txt 格式（键值行，一行一键）：
 *   enabled=1            总开关（1=开、0=关；缺失按关）
 *   mode=app             app=打开应用默认入口 / activity=打开指定 activity
 *   package=<包名>       目标应用包名（空=未配置）
 *   activity=<类全名>    mode=activity 时的目标组件类名
 *
 * 副本收敛（与 HonorShadeClock 戳记同口径）：设置 App 经
 * MediaStore 写文件，卸载重装后旧文件不归新安装所有，新写入
 * 可能被系统改名成「config (n).txt」副本。写侧口径：写前先删
 * 尽本安装拥有的全部同名候选再写一份、写后回读校验内容一致
 * 才算成功；读侧（hook）口径：把正本与全部副本都当候选，
 * 取 mtime 最新的一份解析——两侧合力保证读到的就是最新写入。
 * 读不到/解析失败一律按「未配置」处理，hook 侧放行快服务原
 * 行为，绝不因配置问题拦住手势。
 */
data class GestureConfig(
    val enabled: Boolean = false,
    val mode: String = MODE_APP,
    val pkg: String = "",
    val activity: String = "",
) {
    /** 目标是否已配置到可执行改向的程度。 */
    val isConfigured: Boolean
        get() = pkg.isNotEmpty() && (mode == MODE_APP || activity.isNotEmpty())

    companion object {
        const val MODE_APP = "app"
        const val MODE_ACTIVITY = "activity"
    }
}

object ConfigStore {

    /** MediaStore 相对目录（无首尾斜杠）。 */
    const val CONFIG_REL_DIR = "Download/HonorToolbox"
    const val CONFIG_NAME = "quickgesture.txt"

    val BASE_DIR = File("/sdcard/Download/HonorToolbox")
    val CONFIG_FILE = File(BASE_DIR, CONFIG_NAME)

    private val CONFIG_CANDIDATE_NAME = Regex("""quickgesture( \(\d+\))?\.txt""")

    fun format(config: GestureConfig): String =
        "enabled=${if (config.enabled) 1 else 0}\n" +
            "mode=${config.mode}\n" +
            "package=${config.pkg}\n" +
            "activity=${config.activity}\n"

    fun parse(text: String): GestureConfig {
        var enabled = false
        var mode = GestureConfig.MODE_APP
        var pkg = ""
        var activity = ""
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "enabled" -> enabled = value == "1" || value.equals("true", true)
                "mode" -> mode = if (value == GestureConfig.MODE_ACTIVITY) {
                    GestureConfig.MODE_ACTIVITY
                } else {
                    GestureConfig.MODE_APP
                }
                "package" -> pkg = value
                "activity" -> activity = value
            }
        }
        return GestureConfig(enabled, mode, pkg, activity)
    }

    /**
     * hook 侧读取（普通文件 IO）：正本与全部「config (n).txt」
     * 副本都是候选，取 mtime 最新的一份。无任何可读候选返回
     * null（调用方按未配置放行）。
     */
    fun readConfig(): GestureConfig? {
        val files = try {
            BASE_DIR.listFiles() ?: emptyArray()
        } catch (t: Throwable) {
            emptyArray()
        }
        var best: File? = null
        for (f in files) {
            if (!f.isFile || !CONFIG_CANDIDATE_NAME.matches(f.name)) continue
            if (best == null || f.lastModified() > best.lastModified()) best = f
        }
        val file = best ?: return null
        return try {
            parse(file.readText())
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * 设置侧写入（MediaStore）：普通应用对 /sdcard/Download 无直接
 * 文件写权限，经 MediaStore.Files 创建的自有文件可免权限写入/
 * 覆写/删除。写法与 HonorShadeClock 的 PublicStore 同源。
 */
object ConfigWriter {

    private val FILES_URI: Uri = MediaStore.Files.getContentUri("external")
    private val CONFIG_FILE_NAME = Regex("""quickgesture( \(\d+\))?\.txt""")

    /**
     * 写入配置（调用方需在 IO 线程执行）；返回是否经回读校验
     * 确认落盘。口径：先删尽本安装拥有的全部 config 候选，
     * 再写一份（落盘名仍可能被改成副本，但自有候选只剩这一
     * 份），最后回读校验内容一致才算成功。
     */
    fun writeConfig(context: Context, config: GestureConfig): Boolean {
        val bytes = ConfigStore.format(config).toByteArray(Charsets.UTF_8)
        deleteOwnCandidates(context)
        if (!writeFile(context, ConfigStore.CONFIG_NAME, bytes)) return false
        return verifyOwnContent(context, bytes)
    }

    private fun ownCandidates(context: Context): List<Uri> {
        val cr = context.contentResolver
        val out = ArrayList<Uri>()
        try {
            cr.query(
                FILES_URI,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(ConfigStore.CONFIG_REL_DIR + "/%"),
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val rel = cursor.getString(relIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    // 只认目录直属、文件名整名匹配的候选
                    if (rel.trim('/') != ConfigStore.CONFIG_REL_DIR) continue
                    if (!CONFIG_FILE_NAME.matches(name)) continue
                    out.add(ContentUris.withAppendedId(FILES_URI, cursor.getLong(idIdx)))
                }
            }
        } catch (t: Throwable) {
            // 尽力而为
        }
        return out
    }

    private fun deleteOwnCandidates(context: Context) {
        val cr = context.contentResolver
        for (uri in ownCandidates(context)) {
            try {
                cr.delete(uri, null, null)
            } catch (t: Throwable) {
                // 尽力而为
            }
        }
    }

    private fun verifyOwnContent(context: Context, expect: ByteArray): Boolean {
        val cr = context.contentResolver
        for (uri in ownCandidates(context)) {
            try {
                val data = cr.openInputStream(uri)?.use { it.readBytes() } ?: continue
                if (data.contentEquals(expect)) return true
            } catch (t: Throwable) {
                // 读不出的候选跳过
            }
        }
        return false
    }

    private fun writeFile(context: Context, name: String, data: ByteArray): Boolean {
        val cr = context.contentResolver
        val rel = ConfigStore.CONFIG_REL_DIR + "/"
        return try {
            val uri = queryUri(cr, rel, name) ?: insertFile(cr, rel, name) ?: return false
            val out = cr.openOutputStream(uri, "wt") ?: return false
            out.use { it.write(data) }
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun queryUri(
        cr: android.content.ContentResolver,
        rel: String,
        name: String,
    ): Uri? {
        return try {
            cr.query(
                FILES_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(rel, name),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(FILES_URI, cursor.getLong(0))
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun insertFile(
        cr: android.content.ContentResolver,
        rel: String,
        name: String,
    ): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }
            cr.insert(FILES_URI, values)
        } catch (t: Throwable) {
            null
        }
    }
}
