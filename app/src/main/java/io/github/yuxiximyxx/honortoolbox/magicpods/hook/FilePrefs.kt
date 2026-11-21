package io.github.yuxiximyxx.honortoolbox.magicpods.hook

import android.content.SharedPreferences
import java.io.File
import java.util.Properties

/**
 * FilePrefs — magicorig_settings 配置的落盘现读通道（v1.4.0 合入工具箱新增）。
 *
 * 原 MagicPods 用 libxposed 的 getRemotePreferences("magicorig_settings")，
 * 传统 Xposed API 没有等价物。工具箱既有口径是配置走
 * /sdcard/Download/HonorToolbox/ 落盘文件（FeatureGate/ToolboxStore 同款），
 * 这里照此实现一个 SharedPreferences 接口：数据存
 * /sdcard/Download/HonorToolbox/magicpods/magicorig_settings.properties，
 * 每次读都先按文件 mtime 判断是否需要重载（落盘现读），写经 Editor 落盘。
 * 键名与默认值语义与原 RemotePreferences 完全一致（ConfigManager 不感知通道）。
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
            f.outputStream().use { props.store(it, "magicorig_settings (HonorToolbox FilePrefs)") }
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
        const val DIR = "/sdcard/Download/HonorToolbox/magicpods"
        const val FILE_NAME = "magicorig_settings.properties"
    }
}
