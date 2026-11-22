package io.github.yuxiximyxx.honortoolbox.palmpurify

import android.content.SharedPreferences
import java.io.File
import java.util.Properties

/**
 * PalmPurify 的配置落盘通道（v1.6.0 合入工具箱新增）。
 *
 * 原 PalmPurify 用 libxposed 的 getRemotePreferences("palmpurify")，
 * 传统 Xposed API 没有等价物。照工具箱既有口径（MagicPods 的
 * FilePrefs 同款、各留一份）实现一个 SharedPreferences 接口：
 * 数据存 /sdcard/Download/HonorToolbox/palmpurify/palmpurify.properties，
 * 每次读都先按文件 mtime 判断是否需要重载（落盘现读），写经
 * Editor 落盘。键名与默认值语义与原 RemotePreferences 完全
 * 一致（四组 hook 经 [XposedModule.getRemotePreferences] 读，
 * 不感知通道）。
 *
 * 值按类型前缀编码：S=String I=Int L=Long B=Boolean F=Float SS=StringSet(逗号转义)。
 */
class FilePrefs : SharedPreferences {

    private val file: File
        get() = File(DIR, FILE_NAME)

    @Volatile
    private var cache: MutableMap<String, Any?> = mutableMapOf()

    @Volatile
    private var loadedMtime: Long = -1L

    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Synchronized
    private fun reloadIfChanged() {
        val f = file
        val mtime = if (f.exists()) f.lastModified() else 0L
        if (mtime == loadedMtime) return
        loadedMtime = mtime
        val next = mutableMapOf<String, Any?>()
        if (f.exists()) {
            runCatching {
                val props = Properties()
                f.inputStream().use { props.load(it) }
                for (key in props.stringPropertyNames()) {
                    decode(props.getProperty(key))?.let { next[key] = it }
                }
            }
        }
        cache = next
    }

    @Synchronized
    private fun persistLocked() {
        runCatching {
            val f = file
            f.parentFile?.mkdirs()
            val props = Properties()
            for ((k, v) in cache) {
                encode(v)?.let { props.setProperty(k, it) }
            }
            f.outputStream().use { props.store(it, "palmpurify (HonorToolbox FilePrefs)") }
            loadedMtime = f.lastModified()
        }
    }

    private fun decode(raw: String?): Any? {
        if (raw == null || raw.length < 2) return null
        val body = raw.substring(2)
        return when (raw.substring(0, 2)) {
            "S:" -> body
            "I:" -> body.toIntOrNull()
            "L:" -> body.toLongOrNull()
            "B:" -> body.toBooleanStrictOrNull()
            "F:" -> body.toFloatOrNull()
            "SS" -> if (body.isEmpty()) emptySet<String>()
                else body.split(',').map { it.replace("%2C", ",").replace("%25", "%") }.toSet()
            else -> null
        }
    }

    private fun encode(value: Any?): String? = when (value) {
        is String -> "S:$value"
        is Int -> "I:$value"
        is Long -> "L:$value"
        is Boolean -> "B:$value"
        is Float -> "F:$value"
        is Set<*> -> "SS" + value.joinToString(",") {
            it.toString().replace("%", "%25").replace(",", "%2C")
        }
        else -> null
    }

    override fun getAll(): MutableMap<String, *> {
        reloadIfChanged()
        return HashMap(cache)
    }

    override fun getString(key: String, defValue: String?): String? {
        reloadIfChanged()
        return cache[key] as? String ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        reloadIfChanged()
        return (cache[key] as? Set<String>)?.toMutableSet() ?: defValues
    }

    override fun getInt(key: String, defValue: Int): Int {
        reloadIfChanged()
        return (cache[key] as? Number)?.toInt() ?: defValue
    }

    override fun getLong(key: String, defValue: Long): Long {
        reloadIfChanged()
        return (cache[key] as? Number)?.toLong() ?: defValue
    }

    override fun getFloat(key: String, defValue: Float): Float {
        reloadIfChanged()
        return (cache[key] as? Number)?.toFloat() ?: defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        reloadIfChanged()
        return cache[key] as? Boolean ?: defValue
    }

    override fun contains(key: String): Boolean {
        reloadIfChanged()
        return cache.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { if (value == null) removed.add(key) else pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { if (values == null) removed.add(key) else pending[key] = values.toSet() }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { pending[key] = value }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { removed.add(key) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            val changedKeys: List<String>
            synchronized(this@FilePrefs) {
                reloadIfChanged()
                if (clearAll) cache.clear()
                for (k in removed) cache.remove(k)
                cache.putAll(pending)
                persistLocked()
                changedKeys = (pending.keys + removed).toList()
            }
            val snapshot = synchronized(listeners) { listeners.toList() }
            for (key in changedKeys) {
                for (listener in snapshot) {
                    runCatching { listener.onSharedPreferenceChanged(this@FilePrefs, key) }
                }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }

    companion object {
        const val DIR = "/sdcard/Download/HonorToolbox/palmpurify"
        const val FILE_NAME = "palmpurify.properties"
    }
}

/**
 * PalmPurify 配置的统一读写口（hook 侧与设置页共用同一份
 * [FilePrefs] 落盘现读）。除原 9 个开关外，这里还管 v1.6.0
 * 新增的「底栏逐项自定义」数据：hook 处理到底栏时把全部
 * 底栏项按顺序记进 [KEY_TAB_LABELS]，设置页据此逐项列名
 * 给开关；逐项显隐存 `tab_show_<身份>`，缺省值与原单一
 * 精简的结果一致（首页/我的显示、其余隐藏）。
 *
 * v1.6.1 修复（小乐真机实证：五项底栏只记到三项）：v1.6.0
 * 的记录以「标签」（可见文字→contentDescription）为唯一
 * 身份，文字与描述皆空的纯图标项标签为空、被直接过滤，
 * 永远进不了名单、也控制不了。新记录改为稳定身份制
 * （资源 id 条目名→文字→描述→「位置 N」，见 SlimHooks 的
 * tabIdentities），每项记 身份/显示名/旧标签 三字段；
 * 开关键随之改用身份，读值走 [tabShow] 的旧键兼容读：
 * 新键没有再回退 v1.6.0 的 `tab_show_<旧标签>`，小乐已调
 * 过的三项（首页/我的/加号）设置不失效。旧记录（整条即
 * 标签、无字段分隔）按 身份=显示名=旧标签 解析兼容。
 */
object PalmPrefs {

    val store: SharedPreferences by lazy { FilePrefs() }

    fun bool(key: String, default: Boolean): Boolean = store.getBoolean(key, default)

    fun putBool(key: String, value: Boolean) {
        store.edit().putBoolean(key, value).apply()
    }

    /**
     * 一个底栏项的记录：[identity] 稳定身份（开关键与去重
     * 都用它）、[title] 设置页显示名、[legacyLabel] v1.6.0
     * 口径的旧标签（文字→描述，可为空），只用于旧键兼容读。
     */
    data class TabEntry(
        val identity: String,
        val title: String,
        val legacyLabel: String,
    )

    /** hook 发现的底栏项（按底栏顺序，条目竖线分隔、字段制表符分隔）。 */
    const val KEY_TAB_LABELS = "tab_labels"
    private const val TAB_LABELS_SEP = "|"
    private const val TAB_FIELDS_SEP = "\t"

    fun tabShowKey(identity: String): String = "tab_show_$identity"

    /** 逐项显隐的缺省：与原单一 slim_tabbar 精简结果一致。 */
    fun defaultTabShow(label: String): Boolean =
        label.contains("首页") || label.contains("我的")

    fun defaultTabShow(entry: TabEntry): Boolean =
        defaultTabShow(entry.title) || defaultTabShow(entry.legacyLabel)

    /**
     * 逐项显隐的现值（旧键兼容读）：先读 `tab_show_<身份>`，
     * 没有再回退 v1.6.0 的 `tab_show_<旧标签>`，都没有用
     * [default]。写入只写新键，写过一次即完成迁移。
     */
    fun tabShow(identity: String, legacyLabel: String?, default: Boolean): Boolean {
        val key = tabShowKey(identity)
        if (store.contains(key)) return bool(key, default)
        if (!legacyLabel.isNullOrEmpty() && legacyLabel != identity) {
            val oldKey = tabShowKey(legacyLabel)
            if (store.contains(oldKey)) return bool(oldKey, default)
        }
        return default
    }

    fun tabEntries(): List<TabEntry> =
        store.getString(KEY_TAB_LABELS, null)
            ?.split(TAB_LABELS_SEP)
            ?.mapNotNull { raw ->
                val parts = raw.split(TAB_FIELDS_SEP)
                when {
                    parts.size >= 3 -> TabEntry(
                        identity = parts[0].trim(),
                        title = parts[1].trim(),
                        legacyLabel = parts[2].trim(),
                    ).takeIf { it.identity.isNotEmpty() }
                    // v1.6.0 旧格式：整条就是标签本身
                    parts.size == 1 && parts[0].isNotBlank() -> {
                        val label = parts[0].trim()
                        TabEntry(identity = label, title = label, legacyLabel = label)
                    }
                    else -> null
                }
            }
            ?: emptyList()

    /** 旧口径只读视图（显示名列表），供还没迁到条目制的调用方。 */
    fun tabLabels(): List<String> = tabEntries().map { it.title }

    fun saveTabEntries(entries: List<TabEntry>) {
        if (entries.isEmpty()) return
        val cleaned = entries.map {
            TabEntry(
                identity = cleanField(it.identity),
                title = cleanField(it.title),
                legacyLabel = cleanField(it.legacyLabel),
            )
        }.filter { it.identity.isNotEmpty() }
        if (cleaned.isEmpty()) return
        val raw = cleaned.joinToString(TAB_LABELS_SEP) {
            listOf(it.identity, it.title, it.legacyLabel).joinToString(TAB_FIELDS_SEP)
        }
        if (raw == store.getString(KEY_TAB_LABELS, null)) return
        store.edit().putString(KEY_TAB_LABELS, raw).apply()
    }

    /** 字段里不许混进记录分隔符，免得解析串位。 */
    private fun cleanField(value: String): String =
        value.replace(TAB_LABELS_SEP, " ").replace(TAB_FIELDS_SEP, " ")
            .replace('\n', ' ').trim()
}
