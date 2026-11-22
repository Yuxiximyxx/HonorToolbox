package io.github.yuxiximyxx.honortoolbox.navwake.config

import android.content.SharedPreferences
import java.io.File

/**
 * NavConfig —— 设置侧与 hook 侧共用的配置入口（工具箱移植版）。
 *
 * 通道与原模块的差异（行为语义不变）：
 * - 原版：设置页写本地 SharedPreferences（权威）+ 经 LSPosed 服务写远程 prefs，
 *   hook 侧读远程 prefs、每次拦截 refresh 绕缓存。
 * - 工具箱：没有 libxposed 服务，改走落盘文件
 *   `/sdcard/Download/HonorToolbox/navwake.txt`（键名/默认值与原 prefs 完全一致）：
 *   设置侧仍写本地 prefs（选择框 Activity 在本进程读它，与原版一致），
 *   写后经 [onChanged] 回调把整份配置同步落盘；hook 侧读落盘快照，
 *   每次拦截 [refreshFromFile] 现读，同样绕开「进程内缓存」问题。
 *
 * 所有读取都带默认值，配置没绑上也不会崩。
 */
object NavConfig {

    const val PREFS_NAME = "navwake_settings"
    const val FILE_NAME = "navwake.txt"

    private const val KEY_ENABLED = "redirect_enabled"
    private const val KEY_TAP_BACK = "tap_back_enabled"
    private const val KEY_DEFAULT_TARGET = "default_target"
    private const val KEY_SELECTED = "selected_packages"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_PROBE_MODE = "probe_mode"

    /** 落盘目录（与其它工具箱功能同一落点）。 */
    private val BASE_DIR = File("/sdcard/Download/HonorToolbox")

    /**
     * 候选文件名：正本与 MediaStore 改名产生的「navwake (n).txt」
     * 副本（与 FeatureGate/ConfigStore/ActivationStatus 同一口径）。
     *
     * v1.6.4 前的教训：旧候选只有正本的两条等价路径，而设置侧
     * 经 MediaStore 写入时删不掉旧文件、每次新写都被改名成
     * (n) 副本，正本停在旧内容——hook 永远读到过期配置（真机
     * LSPosed 日志实证：安装行恒为旧快照）。现改为扫目录取
     * mtime 最新一份，与仓内其余落盘通道一致。
     */
    private val CANDIDATE_NAME = Regex("""navwake( \(\d+\))?\.txt""")

    @Volatile
    private var prefs: SharedPreferences? = null

    /** hook 侧的落盘快照（key → 字符串值）。非空时优先于 prefs。 */
    @Volatile
    private var snapshot: Map<String, String>? = null

    /**
     * 设置侧每次写完本地 prefs 后触发（由设置页/选择框在 onCreate 装上：
     * 把整份配置同步写落盘文件）。取代原版 ServiceBridge.pushRemote 的角色。
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun bind(value: SharedPreferences?) {
        prefs = value
    }

    /** hook 侧绑定：读落盘快照。 */
    fun bindFromFile() {
        refreshFromFile()
    }

    /** 重新读一次落盘文件（绕过进程内快照）。文件不存在保留原快照。 */
    fun refreshFromFile() {
        readLatestFile()?.let { snapshot = it }
    }

    private fun readLatestFile(): Map<String, String>? {
        val files = try {
            BASE_DIR.listFiles() ?: emptyArray()
        } catch (t: Throwable) {
            emptyArray()
        }
        var best: File? = null
        for (f in files) {
            if (!f.isFile || !CANDIDATE_NAME.matches(f.name)) continue
            if (best == null || f.lastModified() > best.lastModified()) best = f
        }
        val file = best ?: return null
        return runCatching {
            val map = mutableMapOf<String, String>()
            file.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            map
        }.getOrNull()
    }

    /** 拿到可写的 prefs（设置页用）。 */
    fun writable(): SharedPreferences? = prefs

    // ── 统一读取（快照优先） ────────────────────────────────────────

    private fun bool(key: String, def: Boolean): Boolean {
        snapshot?.let { snap -> snap[key]?.let { return it == "true" } }
        return prefs?.getBoolean(key, def) ?: def
    }

    private fun str(key: String, def: String): String {
        snapshot?.let { snap -> snap[key]?.let { return it } }
        return prefs?.getString(key, def) ?: def
    }

    private fun int(key: String, def: Int): Int {
        snapshot?.let { snap -> snap[key]?.toIntOrNull()?.let { return it } }
        return prefs?.getInt(key, def) ?: def
    }

    // ── 功能开关 ────────────────────────────────────────────────────

    /** 总开关：关掉就完全恢复原 YOYO 行为。默认开。 */
    fun enabled(): Boolean = bool(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        write { it.putBoolean(KEY_ENABLED, value) }
    }

    // ── 单击返回（v0.4.0 新增，替代单装的 mback） ──────────────────

    /** 单击导航条小白条 = 返回。默认**关**，与重定向总开关互相独立。 */
    fun tapBackEnabled(): Boolean = bool(KEY_TAP_BACK, false)

    fun setTapBackEnabled(value: Boolean) {
        write { it.putBoolean(KEY_TAP_BACK, value) }
    }

    // ── 默认目标 ────────────────────────────────────────────────────

    /** 默认目标包名；空串 = 每次都弹选择框。 */
    fun defaultTarget(): String = str(KEY_DEFAULT_TARGET, "")

    fun setDefaultTarget(pkg: String) {
        write { it.putString(KEY_DEFAULT_TARGET, pkg) }
    }

    fun clearDefaultTarget() {
        write { it.putString(KEY_DEFAULT_TARGET, "") }
    }

    // ── 已选目标 ────────────────────────────────────────────────────

    /** 用户在设置页勾选、允许被唤起的应用包名（有序）。 */
    fun selectedPackages(): List<String> {
        snapshot?.let { snap ->
            snap[KEY_SELECTED]?.let {
                return it.split(',').map { p -> p.trim() }.filter { p -> p.isNotEmpty() }
            }
        }
        return prefs?.getStringSet(KEY_SELECTED, emptySet())?.sorted() ?: emptyList()
    }

    fun setSelectedPackages(packages: List<String>) {
        write { it.putStringSet(KEY_SELECTED, packages.toSet()) }
    }

    // ── 诊断 ────────────────────────────────────────────────────────

    /** 0 = 只看关键信息，1 = 常规（默认），2 = 全量。 */
    fun logLevel(): Int = int(KEY_LOG_LEVEL, 1)

    fun setLogLevel(value: Int) {
        write { it.putInt(KEY_LOG_LEVEL, value.coerceIn(0, 2)) }
    }

    /**
     * 阶段 0 的探针模式。
     *
     * 默认**关闭**：阶段 1 只装重定向 hook，不装探针，避免多挂点位增加 SystemUI 风险。
     * 需要重新定位链路时再打开。
     */
    fun probeMode(): Boolean = bool(KEY_PROBE_MODE, false)

    fun setProbeMode(value: Boolean) {
        write { it.putBoolean(KEY_PROBE_MODE, value) }
    }

    /**
     * 写配置：本地 prefs（本进程权威，选择框读它）+ [onChanged] 同步落盘
     * （hook 进程读它）。取代原版「本地一份 + 远程一份」的双写。
     */
    private fun write(block: (SharedPreferences.Editor) -> Unit) {
        prefs?.let { runCatching { it.edit().also(block).apply() } }
        runCatching { onChanged?.invoke() }
    }

    // ── 阶段 0 探针细项（只在 probeMode 打开时有意义） ───────────────

    fun probeAssist(): Boolean = bool("probe_assist", true)

    fun probeStartActivity(): Boolean = bool("probe_start_activity", true)

    fun probeBrain(): Boolean = bool("probe_brain", true)

    fun probeMagicVoiceActivity(): Boolean = bool("probe_magicvoice_activity", true)
}
