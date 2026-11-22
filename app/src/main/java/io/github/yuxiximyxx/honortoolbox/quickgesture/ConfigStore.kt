package io.github.yuxiximyxx.honortoolbox.quickgesture

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
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
 *
 * Settings.Global 通道（v1.6.4 新增）：真机 LSPosed 日志
 * 实证——文件本身存在且内容正确，但快服务进程没有直读
 * /sdcard/Download 的权限，hook 侧目录扫描拿不到任何候选
 * （同文件在 SystemUI 里可读）。故设置侧每次文件写成功后
 * 把同一份格式化文本同步写入 Settings.Global 的
 * [GLOBAL_KEY] 键（与 MagicPods 的 Settings.Global 跨进程
 * 桥同模式，读它不需要存储权限）；hook 侧 [readConfig]
 * 读该键、像本格式的文本就解析采用，读不到再回退文件
 * 候选扫描。每次读取实际走的通道记在 [lastReadSource]，
 * hook 的 diag 日志会带出来，下轮取证可直接分辨。
 *
 * ContentProvider 通道（v1.6.5 新增，hook 侧首选）：真机
 * 实证 v1.6.4 的 Global 通道写不进去——工具箱没有
 * WRITE_SETTINGS、用户也从未授「修改系统设置」，
 * syncGlobal 的 putString 必然失败（只 Log.w 留痕），
 * hook 回退文件通道照旧读不到。改为工具箱自带一个
 * exported 的 [QuickGestureConfigProvider]（与资源库增强
 * 的 ConfigProvider 同范式：call("get") 返回 Bundle）：
 * 它在工具箱进程内读设置页同源的镜像偏好
 * [readMirrorConfig] 并按 [format] 供出配置文本，任何
 * 进程查询都不需要特殊权限。hook 侧 [readConfig] 读序：
 * Provider → Settings.Global（保留，万一将来授了权）→
 * 文件候选扫描（兜底）。
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

    /** Settings.Global 通道键名（honortoolbox_ 前缀，本功能专用）。 */
    const val GLOBAL_KEY = "honortoolbox_quickgesture_config"

    /**
     * ContentProvider 通道（v1.6.5）：authority 独立于资源库
     * 增强已占用的 io.github.yuxiximyxx.honortoolbox.config；
     * 方法与载荷键与 ConfigProvider 的 get 范式一致。
     */
    const val PROVIDER_AUTHORITY = "io.github.yuxiximyxx.honortoolbox.quickgesture"
    const val PROVIDER_METHOD_GET = "get"
    const val PROVIDER_EXTRA_CONFIG = "config"

    /** 设置侧镜像偏好（设置页显示与系统页开关共用同一份）。 */
    const val SETTINGS_PREFS_NAME = "quickgesture_settings"

    /**
     * 读设置侧镜像偏好（provider 的数据源）：与设置页读回
     * 展示、系统页开关读写的是同一份偏好、同一组键与默认
     * 值，保证 provider 供出的就是页面上显示的当前配置。
     * 只在工具箱进程内调用（provider / 设置侧）。
     */
    fun readMirrorConfig(context: Context): GestureConfig {
        val prefs = context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        return GestureConfig(
            enabled = prefs.getBoolean("enabled", false),
            mode = prefs.getString("mode", GestureConfig.MODE_APP)
                ?: GestureConfig.MODE_APP,
            pkg = prefs.getString("package", "") ?: "",
            activity = prefs.getString("activity", "") ?: "",
        )
    }

    /**
     * 最近一次 [readConfig] 实际命中的通道："provider" /
     * "global" / "file" / "none"。hook 侧 diag 日志据此留痕。
     */
    @Volatile
    var lastReadSource: String = "none"
        private set

    /**
     * hook 侧读取（[context] 非空时）：依次走 Provider →
     * Settings.Global → 文件候选扫描，任一通道读到形如本
     * 格式（含 enabled= 行）的文本即解析采用；全部失败返回
     * null（调用方按未配置放行）。[context] 为 null 时直接
     * 走文件通道（安装时拿不到宿主 Context 的场合）。
     * Provider 查询是同步跨进程调用、只在安装自检与手势
     * 命中时发生，异常一律吞掉回退下一通道，绝不阻塞判定。
     */
    fun readConfig(context: Context?): GestureConfig? {
        if (context != null) {
            val fromProvider = readConfigFromProvider(context)
            if (fromProvider != null) {
                lastReadSource = "provider"
                return fromProvider
            }
            val fromGlobal = try {
                Settings.Global.getString(context.contentResolver, GLOBAL_KEY)
            } catch (t: Throwable) {
                null
            }
            if (!fromGlobal.isNullOrBlank() && fromGlobal.contains("enabled=")) {
                lastReadSource = "global"
                return parse(fromGlobal)
            }
        }
        val fromFile = readConfigFromFile()
        lastReadSource = if (fromFile != null) "file" else "none"
        return fromFile
    }

    /**
     * Provider 通道读取：向工具箱的
     * [QuickGestureConfigProvider] 发 call("get")，取回
     * Bundle 里的配置文本解析。查不到/异常/文本不像本
     * 格式一律返回 null、由调用方回退下一通道。
     */
    private fun readConfigFromProvider(context: Context): GestureConfig? {
        return try {
            val bundle = context.contentResolver.call(
                Uri.parse("content://$PROVIDER_AUTHORITY"),
                PROVIDER_METHOD_GET,
                null,
                null,
            )
            val text = bundle?.getString(PROVIDER_EXTRA_CONFIG)
            if (!text.isNullOrBlank() && text.contains("enabled=")) {
                parse(text)
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 无 Context 的兼容入口（等价 readConfig(null)，只走文件通道）。 */
    fun readConfig(): GestureConfig? = readConfig(null)

    /**
     * 文件通道读取（普通文件 IO）：正本与全部「config (n).txt」
     * 副本都是候选，取 mtime 最新的一份。无任何可读候选返回
     * null（调用方按未配置放行）。
     */
    private fun readConfigFromFile(): GestureConfig? {
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
     * 份），最后回读校验内容一致才算成功。文件通道成功后再
     * 同步 Settings.Global（见 [syncGlobal]），其成败不影响
     * 本返回值（文件通道的结果呈现口径不变）。
     */
    fun writeConfig(context: Context, config: GestureConfig): Boolean {
        val bytes = ConfigStore.format(config).toByteArray(Charsets.UTF_8)
        deleteOwnCandidates(context)
        if (!writeFile(context, ConfigStore.CONFIG_NAME, bytes)) return false
        val ok = verifyOwnContent(context, bytes)
        if (ok) syncGlobal(context, config)
        return ok
    }

    private const val TAG = "QuickGestureStore"

    /** 最近一次 Settings.Global 同步结果（null = 还没同步过）。 */
    @Volatile
    var lastGlobalSyncOk: Boolean? = null
        private set

    /**
     * 把同一份格式化配置文本同步写入 Settings.Global 的
     * [ConfigStore.GLOBAL_KEY] 键（v1.6.4）：快服务进程读
     * 不到 Download 文件、但读 Global 不需要存储权限（与
     * MagicPods 的 Settings.Global 跨进程桥同模式）。同步
     * 失败只留痕（logcat + [lastGlobalSyncOk]），不影响文件
     * 通道已成功的结果。
     */
    private fun syncGlobal(context: Context, config: GestureConfig) {
        val text = ConfigStore.format(config)
        val result = runCatching {
            Settings.Global.putString(
                context.contentResolver, ConfigStore.GLOBAL_KEY, text,
            )
        }
        val ok = result.getOrDefault(false)
        lastGlobalSyncOk = ok
        if (!ok) {
            Log.w(
                TAG,
                "Settings.Global 同步未成功（文件通道已落盘，hook 侧仍可走文件）",
                result.exceptionOrNull(),
            )
        }
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
