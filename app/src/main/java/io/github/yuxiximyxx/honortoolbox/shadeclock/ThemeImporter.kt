package io.github.yuxiximyxx.honortoolbox.shadeclock

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.yuxiximyxx.honortoolbox.shadeclock.theme.ThemePackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/** 已导入主题的一条登记。 */
data class ThemeEntry(val id: String, val name: String)

/**
 * 公共 Download 目录的合规读写（MediaStore）：普通应用对
 * /sdcard/Download 无直接文件写权限，经 MediaStore.Files 创建的
 * 自有文件可免权限写入/覆写/删除；SystemUI 侧用普通文件 IO 读。
 */
object PublicStore {

    private val FILES_URI: Uri = MediaStore.Files.getContentUri("external")

    /** relDir 形如 "Download/HonorToolbox/shadeclock/themes/<id>"（无首尾斜杠）。 */
    fun writeFile(context: Context, relDir: String, name: String, data: ByteArray): Boolean {
        val cr = context.contentResolver
        val rel = relDir.trim('/') + "/"
        return try {
            val uri = queryUri(cr, rel, name) ?: insertFile(cr, rel, name) ?: return false
            val out = cr.openOutputStream(uri, "wt") ?: return false
            out.use { it.write(data) }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 流式写入（主题包本体可达几十 MB，不整读进内存）。 */
    fun writeStream(
        context: Context,
        relDir: String,
        name: String,
        input: java.io.InputStream,
    ): Boolean = writeStreamUri(context, relDir, name, input) != null

    /** 流式写入并返回落盘条目的 Uri（迁移校验回读要用）；失败返回 null。 */
    fun writeStreamUri(
        context: Context,
        relDir: String,
        name: String,
        input: java.io.InputStream,
    ): Uri? {
        val cr = context.contentResolver
        val rel = relDir.trim('/') + "/"
        return try {
            val uri = queryUri(cr, rel, name) ?: insertFile(cr, rel, name) ?: return null
            val out = cr.openOutputStream(uri, "wt") ?: return null
            out.use { input.copyTo(it) }
            uri
        } catch (t: Throwable) {
            null
        }
    }

    /** 一条已登记文件的句柄：uri + 完整相对路径（形如 Download/HonorToolbox/shadeclock/themes/<id>/time/time_0.png）。 */
    class StoredFile(val uri: Uri, val fullPath: String)

    /** 枚举 relDir 前缀下的全部自有文件（含子目录）。 */
    fun listFiles(context: Context, relDir: String): List<StoredFile> {
        val cr = context.contentResolver
        val prefix = relDir.trim('/') + "/"
        val out = ArrayList<StoredFile>()
        try {
            cr.query(
                FILES_URI,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$prefix%"),
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val rel = cursor.getString(relIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    out.add(
                        StoredFile(
                            ContentUris.withAppendedId(FILES_URI, cursor.getLong(idIdx)),
                            rel + name,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            // 尽力而为
        }
        return out
    }

    /**
     * 枚举 relDir 直属（不含子目录）的自有文件，文件名需整名
     * 匹配 nameRegex。戳记/重启标记的副本清理用：listFiles 的
     * 前缀查询会连 themes/ 子目录的文件一并带回，这里按完整
     * 路径的父目录严格过滤，只认直属文件。
     */
    fun listOwnFiles(
        context: Context,
        relDir: String,
        nameRegex: Regex,
    ): List<StoredFile> {
        val dir = relDir.trim('/')
        return listFiles(context, relDir).filter { f ->
            f.fullPath.substringBeforeLast('/', "") == dir &&
                nameRegex.matches(f.fullPath.substringAfterLast('/'))
        }
    }

    /** 读回一条自有文件的内容（写后回读校验用）；读不出返回 null。 */
    fun readOwnFile(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        }
    }

    /** 删除一条已登记文件（文件本体与 MediaStore 条目一并消失）。 */
    fun deleteFile(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (t: Throwable) {
            false
        }
    }

    /** 删除 relDir 前缀下的全部自有文件，返回删除条数。 */
    fun deleteTree(context: Context, relDir: String): Int {
        val cr = context.contentResolver
        val prefix = relDir.trim('/') + "/"
        var deleted = 0
        try {
            cr.query(
                FILES_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$prefix%"),
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val ids = ArrayList<Long>()
                while (cursor.moveToNext()) ids.add(cursor.getLong(idIdx))
                for (id in ids) {
                    deleted += cr.delete(ContentUris.withAppendedId(FILES_URI, id), null, null)
                }
            }
        } catch (t: Throwable) {
            // 尽力而为
        }
        return deleted
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
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            }
            cr.insert(FILES_URI, values)
        } catch (t: Throwable) {
            null
        }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "xml" -> "text/xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** 主题登记表（存 settings 偏好）与 stamp.txt 的写入。 */
object ThemeStore {

    const val KEY_THEMES_JSON = "themes_json"
    const val KEY_SELECTED_THEME = "selected_theme_id"
    const val KEY_TOP_STYLE = "top_style"
    const val KEY_HEIGHT_MODE = "height_mode"
    const val KEY_SHOW_BACKGROUND = "show_background"
    const val KEY_SHOW_WIDGETS = "show_widgets"
    const val KEY_OFFSET_DP = "offset_dp"
    const val KEY_OFFSETS_JSON = "offsets_by_theme_json"
    const val KEY_OFFSETS_X_JSON = "offsets_x_by_theme_json"
    const val KEY_WEEKDAY_JSON = "weekday_by_theme_json"
    const val KEY_HIDE_CARRIER = "hide_carrier"
    // 头部卡片（v2.0.32）：模式开关与选中 provider 都是全局
    // 一份（试验功能，不按主题记）；写戳记时下发到第 11/12 行。
    const val KEY_CARD_MODE = "card_mode"
    const val KEY_CARD_PROVIDER = "card_provider"

    // ---- 垂直偏移按主题记（v2.0.21）----
    // v2.0.20 及以前偏移是全局一个值（KEY_OFFSET_DP），但各
    // 主题的时钟簇位置/留白不同，一个值套不住所有主题。改
    // 为按 themeId 存一张映射（KEY_OFFSETS_JSON）；戳记格式
    // 不变——写戳记时取「当前选中主题」的那一份下发，hook
    // 侧无感。存量迁移：旧的全局值作为「当前选中主题」的初
    // 始值搬进映射（只搬一次，搬完删旧键），不丢用户已调好
    // 的值；其余主题从 0 开始各自调。

    fun loadOffsetMap(prefs: SharedPreferences): MutableMap<String, Int> {
        val out = mutableMapOf<String, Int>()
        val raw = prefs.getString(KEY_OFFSETS_JSON, null) ?: return out
        return try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optInt(k, 0)
                    .coerceIn(StampStore.OFFSET_DP_MIN, StampStore.OFFSET_DP_MAX)
            }
            out
        } catch (t: Throwable) {
            out
        }
    }

    fun saveOffsetMap(prefs: SharedPreferences, map: Map<String, Int>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs.edit().putString(KEY_OFFSETS_JSON, obj.toString()).apply()
    }

    /** 存量迁移（幂等）：旧全局偏移 → 当前选中主题的初始偏移，搬完删旧键。 */
    fun migrateLegacyOffset(prefs: SharedPreferences) {
        if (!prefs.contains(KEY_OFFSET_DP)) return
        val legacy = prefs.getInt(KEY_OFFSET_DP, 0)
            .coerceIn(StampStore.OFFSET_DP_MIN, StampStore.OFFSET_DP_MAX)
        val selected = prefs.getString(KEY_SELECTED_THEME, "") ?: ""
        val map = loadOffsetMap(prefs)
        if (!map.containsKey(selected)) {
            map[selected] = legacy
            saveOffsetMap(prefs, map)
        }
        prefs.edit().remove(KEY_OFFSET_DP).apply()
    }

    /** 某主题的偏移（dp）；没调过返回 0。调用前应已跑过 migrateLegacyOffset。 */
    fun offsetForTheme(prefs: SharedPreferences, themeId: String): Int =
        loadOffsetMap(prefs)[themeId] ?: 0

    fun setOffsetForTheme(prefs: SharedPreferences, themeId: String, value: Int) {
        val map = loadOffsetMap(prefs)
        map[themeId] = value.coerceIn(StampStore.OFFSET_DP_MIN, StampStore.OFFSET_DP_MAX)
        saveOffsetMap(prefs, map)
    }

    // ---- 水平偏移按主题记（v2.0.23）----
    // 与垂直偏移并列：另一张按 themeId 的映射（独立键，值域
    // 对称 -120～+120dp），写戳记时一并取当前主题的那一份
    // 下发到戳记第 9 行；旧版没有这张映射、缺省 0。

    fun loadOffsetXMap(prefs: SharedPreferences): MutableMap<String, Int> {
        val out = mutableMapOf<String, Int>()
        val raw = prefs.getString(KEY_OFFSETS_X_JSON, null) ?: return out
        return try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optInt(k, 0)
                    .coerceIn(StampStore.OFFSET_X_DP_MIN, StampStore.OFFSET_X_DP_MAX)
            }
            out
        } catch (t: Throwable) {
            out
        }
    }

    fun saveOffsetXMap(prefs: SharedPreferences, map: Map<String, Int>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs.edit().putString(KEY_OFFSETS_X_JSON, obj.toString()).apply()
    }

    /** 某主题的水平偏移（dp）；没调过返回 0。 */
    fun offsetXForTheme(prefs: SharedPreferences, themeId: String): Int =
        loadOffsetXMap(prefs)[themeId] ?: 0

    fun setOffsetXForTheme(prefs: SharedPreferences, themeId: String, value: Int) {
        val map = loadOffsetXMap(prefs)
        map[themeId] = value.coerceIn(StampStore.OFFSET_X_DP_MIN, StampStore.OFFSET_X_DP_MAX)
        saveOffsetXMap(prefs, map)
    }

    // ---- 星期编号模式按主题记（v2.0.23）----
    // 主题的星期图集编号口径写在著者脑子里、XML 里不声明
    // （两边都只有 srcid="#day_of_week"）：.hwt 样本按周日=1
    // 编（w_1=SUN…w_5=THU，与荣耀引擎 #day_of_week 的
    // Calendar 原口径一致）、云镜按周一=1 编。渲染器无法自
    // 动分辨，只能按 themeId 存一份模式：true=周一起算、
    // false=周日起算（默认，与引擎口径一致，AAA 装上即
    // 对）；写戳记时取当前主题的那一份下发到戳记第 10 行。
    // 存量不迁移、不预置任何主题的特殊值——云镜需在设置里
    // 对该主题手动切一次「周一起算」，切完按主题记住。

    fun loadWeekdayMap(prefs: SharedPreferences): MutableMap<String, Boolean> {
        val out = mutableMapOf<String, Boolean>()
        val raw = prefs.getString(KEY_WEEKDAY_JSON, null) ?: return out
        return try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optBoolean(k, false)
            }
            out
        } catch (t: Throwable) {
            out
        }
    }

    fun saveWeekdayMap(prefs: SharedPreferences, map: Map<String, Boolean>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs.edit().putString(KEY_WEEKDAY_JSON, obj.toString()).apply()
    }

    /** 某主题的星期编号是否按周一起算；没设过返回 false（周日起算）。 */
    fun weekdayMondayStartForTheme(prefs: SharedPreferences, themeId: String): Boolean =
        loadWeekdayMap(prefs)[themeId] ?: false

    fun setWeekdayMondayStartForTheme(
        prefs: SharedPreferences,
        themeId: String,
        mondayStart: Boolean,
    ) {
        val map = loadWeekdayMap(prefs)
        map[themeId] = mondayStart
        saveWeekdayMap(prefs, map)
    }

    fun loadThemes(prefs: SharedPreferences): List<ThemeEntry> {
        val raw = prefs.getString(KEY_THEMES_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<ThemeEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isNotEmpty()) out.add(ThemeEntry(id, obj.optString("name", id)))
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun saveThemes(prefs: SharedPreferences, themes: List<ThemeEntry>) {
        val arr = JSONArray()
        for (t in themes) {
            arr.put(JSONObject().put("id", t.id).put("name", t.name))
        }
        prefs.edit().putString(KEY_THEMES_JSON, arr.toString()).apply()
    }

    private val STAMP_FILE_NAME = Regex("""stamp( \(\d+\))?\.txt""")
    private val RESTART_FILE_NAME = Regex("""restart( \(\d+\))?\.txt""")

    /** 删除本安装在戳记目录直属拥有的全部同名候选（含正本，若归本安装所有）。 */
    private fun deleteOwnCandidates(context: Context, nameRegex: Regex) {
        for (f in PublicStore.listOwnFiles(context, StampStore.STAMP_REL_DIR, nameRegex)) {
            PublicStore.deleteFile(context, f.uri)
        }
    }

    /** 回读校验：自有候选里存在一份内容与 expect 完全一致的文件才算写成功。 */
    private fun verifyOwnContent(
        context: Context,
        nameRegex: Regex,
        expect: ByteArray,
    ): Boolean {
        for (f in PublicStore.listOwnFiles(context, StampStore.STAMP_REL_DIR, nameRegex)) {
            val data = PublicStore.readOwnFile(context, f.uri) ?: continue
            if (data.contentEquals(expect)) return true
        }
        return false
    }

    /**
     * 按当前偏好重写 stamp.txt（调用方需在 IO 线程执行）；返回
     * 是否经回读校验确认落盘。
     *
     * v2.0.20 单一权威口径：旧写法是「MediaStore 查不到就
     * insert」——重装后正本不归本安装所有、查不到，insert 又
     * 被系统改名成「stamp (n).txt」副本，结果还被丢弃，
     * v2.0.19 真机出现过选了新主题、目录里却始终没有一份能
     * 被 hook 采纳的最新戳记。本版写前先删尽本安装拥有的
     * 全部戳记候选，再写一份（落盘名仍可能被改成副本，但
     * 自有候选只剩这一份），最后回读校验内容一致才算成功；
     * hook 侧采纳胜者后也会删旧副本收敛（见 StampStore），
     * 两侧合力保证目录里最新写入就是唯一权威。
     */
    fun writeStamp(context: Context, prefs: SharedPreferences): Boolean {
        val selected = prefs.getString(KEY_SELECTED_THEME, "") ?: ""
        val topStyle = prefs.getString(KEY_TOP_STYLE, StampStore.TOP_STYLE_THEME)
            ?: StampStore.TOP_STYLE_THEME
        val heightMode = prefs.getInt(KEY_HEIGHT_MODE, StampStore.HEIGHT_FOLLOW)
        val showBackground = prefs.getBoolean(KEY_SHOW_BACKGROUND, false)
        val showWidgets = prefs.getBoolean(KEY_SHOW_WIDGETS, false)
        // 偏移按主题下发（v2.0.21）：戳记第 7 行仍是单个值，
        // 取当前选中主题的那一份；先跑存量迁移保证旧全局值
        // 已搬进映射。
        migrateLegacyOffset(prefs)
        val offsetDp = offsetForTheme(prefs, selected)
        val offsetXDp = offsetXForTheme(prefs, selected)
        // 星期编号模式同样按主题下发（v2.0.23，戳记第 10 行）
        val weekdayMondayStart = weekdayMondayStartForTheme(prefs, selected)
        val hideCarrier = prefs.getBoolean(KEY_HIDE_CARRIER, true)
        // 头部卡片（v2.0.32）：全局一份，下发到戳记第 11/12 行
        val cardMode = prefs.getBoolean(KEY_CARD_MODE, true)
        val cardProvider = prefs.getString(KEY_CARD_PROVIDER, StampStore.DEFAULT_CARD_PROVIDER)
            ?: StampStore.DEFAULT_CARD_PROVIDER
        val content = StampStore.formatStamp(
            selected, System.currentTimeMillis(), heightMode, topStyle,
            showBackground, showWidgets, offsetDp, hideCarrier, offsetXDp,
            weekdayMondayStart, cardMode, cardProvider,
        )
        val bytes = content.toByteArray(Charsets.UTF_8)
        deleteOwnCandidates(context, STAMP_FILE_NAME)
        if (!PublicStore.writeFile(
                context, StampStore.STAMP_REL_DIR, StampStore.STAMP_NAME, bytes,
            )
        ) {
            return false
        }
        return verifyOwnContent(context, STAMP_FILE_NAME, bytes)
    }

    /**
     * 「重启系统界面」请求（v2.0.12 通道）：把请求时刻写入
     * restart.txt。与 writeStamp 同口径（v2.0.20）：先清本安
     * 装拥有的全部 restart 候选再写一份、回读校验；返回是否
     * 确认落盘。
     */
    fun writeRestartRequest(context: Context): Boolean {
        val bytes = System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8)
        deleteOwnCandidates(context, RESTART_FILE_NAME)
        if (!PublicStore.writeFile(
                context, StampStore.STAMP_REL_DIR, StampStore.RESTART_NAME, bytes,
            )
        ) {
            return false
        }
        return verifyOwnContent(context, RESTART_FILE_NAME, bytes)
    }

    /** 删除一个主题：清资产、摘登记、若是当前选中则清选中，最后重写 stamp。 */
    fun deleteTheme(context: Context, prefs: SharedPreferences, id: String) {
        PublicStore.deleteTree(context, "${StampStore.THEMES_REL_DIR}/$id")
        val themes = loadThemes(prefs).filter { it.id != id }
        saveThemes(prefs, themes)
        // 该主题的按主题记录一并清掉（偏移 v2.0.21、水平偏移
        // 与星期编号模式 v2.0.23），免得映射里留孤儿
        val offsets = loadOffsetMap(prefs)
        if (offsets.remove(id) != null) saveOffsetMap(prefs, offsets)
        val offsetsX = loadOffsetXMap(prefs)
        if (offsetsX.remove(id) != null) saveOffsetXMap(prefs, offsetsX)
        val weekday = loadWeekdayMap(prefs)
        if (weekday.remove(id) != null) saveWeekdayMap(prefs, weekday)
        if (prefs.getString(KEY_SELECTED_THEME, "") == id) {
            prefs.edit().putString(KEY_SELECTED_THEME, "").apply()
        }
        writeStamp(context, prefs)
    }

    /**
     * 兜底（v2.0.18）：在 HonorShadeClock 目录根放 .nomedia，
     * 媒体扫描器跳过整棵子树——即便将来又落了图片文件也不进
     * 图库。写入失败尽力而为（本体按非媒体登记才是主防线）。
     */
    fun ensureNoMedia(context: Context) {
        PublicStore.writeFile(
            context, StampStore.STAMP_REL_DIR, StampStore.NOMEDIA_NAME, ByteArray(0),
        )
    }

    /**
     * 存量迁移（v2.0.18）：旧版导入把主题解压成一堆散图存在
     * themes/<id>/ 下、还以图片条目登记进 MediaStore，全被图库
     * 显示出来。迁移把每个登记主题的散文件就地打包回一个
     * theme.pkg（条目结构与新导入一致：锁屏资产在
     * unlock/lockscreen/ 下、字体保持包根 fonts/），再逐条删掉
     * 散文件——删 MediaStore 条目即删文件本体，图库条目随之
     * 消失。返回成功迁移的主题数；无散文件的主题跳过；打包
     * 后必须回读校验通过才删散文件（v2.0.19，防再现 2.0.18
     * 迁移把 103 图打成 2 图还删原件的数据丢失）。
     * 调用方在设置页打开时于 IO 线程跑一次，幂等；迁移后主题
     * id 不变、登记不变，不需要用户重新导入。
     */
    fun migrateLegacyThemes(context: Context, prefs: SharedPreferences): Int {
        ensureNoMedia(context)
        var migrated = 0
        for (entry in loadThemes(prefs)) {
            if (migrateOneTheme(context, entry.id)) migrated++
        }
        return migrated
    }

    private fun migrateOneTheme(context: Context, id: String): Boolean {
        val relDir = "${StampStore.THEMES_REL_DIR}/$id"
        val prefix = "$relDir/"
        val pkgPath = prefix + StampStore.THEME_PACKAGE_NAME
        val files = PublicStore.listFiles(context, relDir)
        val legacy = files.filter { it.fullPath != pkgPath }
        if (legacy.isEmpty()) return false
        // v2.0.19 安全性重写：v2.0.18 的迁移在真机上把一个
        // 103 图的主题打成了只剩 2 图的包、还把散图全删了，
        // 数据不可逆丢失（枚举/读流任一环静默少件都会如此）。
        // 本版口径：只要还有散文件就一律重新打包（不因「已有
        // 包」就跳过打包只删散文件——那会把坏包坐实）；任一
        // 散文件读不出来就整题放弃、散文件原样保留；包写完
        // 必须回读校验（manifest 可定位、条目数与图片数与打
        // 包计数一致）通过后才删散文件，校验不过删掉新包、
        // 散文件保留，渲染侧继续走解压目录兜底。
        val tmp = File(context.cacheDir, "theme_migrate_$id.zip")
        var packedEntries = 0
        var packedImages = 0
        try {
            java.util.zip.ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
                for (f in legacy) {
                    val rel = f.fullPath.removePrefix(prefix)
                    // 旧解压布局：锁屏资产在 <id>/<rel>、字体在
                    // <id>/fonts/；回包时锁屏资产归位到
                    // unlock/lockscreen/ 下（渲染器缺省
                    // dynamicPath 即 lockscreen，与原包是否
                    // 写过 theme.xml 无关、自洽），字体保持
                    // 包根 fonts/。
                    val entryName = if (rel.startsWith("fonts/")) {
                        rel
                    } else {
                        "unlock/lockscreen/$rel"
                    }
                    val input = context.contentResolver.openInputStream(f.uri)
                        ?: return false
                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                    input.use { it.copyTo(zos) }
                    zos.closeEntry()
                    packedEntries++
                    if (isImageName(entryName)) packedImages++
                }
            }
            val pkgUri = tmp.inputStream().buffered().use { ins ->
                PublicStore.writeStreamUri(
                    context, relDir, StampStore.THEME_PACKAGE_NAME, ins,
                )
            }
            if (pkgUri == null) {
                tmp.delete()
                return false
            }
            if (!verifyPackage(context, pkgUri, packedEntries, packedImages)) {
                PublicStore.deleteFile(context, pkgUri)
                tmp.delete()
                return false
            }
        } catch (t: Throwable) {
            tmp.delete()
            return false
        }
        tmp.delete()
        // 包已校验就位：删尽散文件（连同其 MediaStore 条目）
        for (f in legacy) {
            PublicStore.deleteFile(context, f.uri)
        }
        return true
    }

    private fun isImageName(name: String): Boolean =
        name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
            name.endsWith(".jpeg", true) || name.endsWith(".webp", true)

    /** 回读刚写的主题包做完整性校验：manifest 可定位、条目数与锁屏图片数与打包计数一致。 */
    private fun verifyPackage(
        context: Context,
        uri: Uri,
        expectEntries: Int,
        expectImages: Int,
    ): Boolean {
        val tmp = File(context.cacheDir, "theme_verify_${System.currentTimeMillis()}.zip")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
            ZipFile(tmp).use { zip ->
                val prefix = ThemePackage.lockscreenPrefix(zip) ?: return false
                val entries = Collections.list(zip.entries()).filter { !it.isDirectory }
                if (entries.size != expectEntries) return false
                val images = entries.count {
                    it.name.startsWith(prefix) && isImageName(it.name)
                }
                images == expectImages
            }
        } catch (t: Throwable) {
            false
        } finally {
            tmp.delete()
        }
    }
}

/**
 * .hnt/.hwt 主题包导入（v2.0.18 改存包本体）：校验包里有锁屏
 * manifest 后，只把主题文件原样复制一份存进 themes/<id>/（文件
 * 名 theme.pkg，按 application/octet-stream 登记、不是图片条
 * 目，图库索引不到），渲染器运行时直接读这份 zip。不再解压
 * 散图——旧版解压出的散图全进了图库，就是这版要改掉的。
 */
object ThemeImporter {

    sealed interface Result {
        class Ok(val entry: ThemeEntry) : Result
        class Err(val message: String) : Result
    }

    fun importTheme(context: Context, uri: Uri): Result {
        val cacheZip = File(context.cacheDir, "theme_import_${System.currentTimeMillis()}.zip")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.Err("读不到所选文件")
            input.use { ins -> cacheZip.outputStream().use { out -> ins.copyTo(out) } }

            val title = ZipFile(cacheZip).use { zip ->
                if (ThemePackage.lockscreenPrefix(zip) == null) {
                    return Result.Err("包里找不到锁屏 manifest.xml，不是可用的锁屏主题")
                }
                val entries = Collections.list(zip.entries())
                readTitle(zip, entries)
                    ?: displayName(context, uri)?.substringBeforeLast('.')
                    ?: "未命名主题"
            }

            val id = "t" + System.currentTimeMillis().toString(36)
            val ok = cacheZip.inputStream().buffered().use { ins ->
                PublicStore.writeStream(
                    context,
                    "${StampStore.THEMES_REL_DIR}/$id",
                    StampStore.THEME_PACKAGE_NAME,
                    ins,
                )
            }
            if (!ok) {
                return Result.Err("主题文件写入失败（公共存储不可写）")
            }
            ThemeStore.ensureNoMedia(context)
            return Result.Ok(ThemeEntry(id, title))
        } catch (t: Throwable) {
            return Result.Err("解析失败：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            cacheZip.delete()
        }
    }

    private fun readTitle(zip: ZipFile, entries: List<java.util.zip.ZipEntry>): String? {
        val desc = entries.firstOrNull { it.name == "description.xml" } ?: return null
        return try {
            val text = zip.getInputStream(desc).readBytes().toString(Charsets.UTF_8)
            Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (t: Throwable) {
            null
        }
    }
}
