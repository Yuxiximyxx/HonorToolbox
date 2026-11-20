package io.github.yuxiximyxx.honortoolbox.nighticon

import java.io.File

/**
 * 图标日夜切换的落盘通道（合入工具箱时替代原 libxposed 的
 * 远程偏好 + 远程文件；键名、文件名、语义与原版完全一致）：
 * 目录 /sdcard/Download/HonorToolbox/nighticon/
 * - config.txt：一行一键（enabled、light_version、dark_version、
 *   light_has_backup、dark_has_backup、apply_token、
 *   restart_token，键名见 [Keys]）；
 * - light_icons / light_icons_backup / dark_icons /
 *   dark_icons_backup：两套主题解出的内层 zip。
 * 读侧对每个文件名都认正本与「name (n)」副本、取 mtime 最新
 * （与工具箱其他落盘通道同口径：设置侧经 MediaStore 写，
 * 卸载重装后新写入可能落到副本上）。
 */
object NightIconConfig {

    val DIR = File("/sdcard/Download/HonorToolbox/nighticon")
    const val REL_DIR = "Download/HonorToolbox/nighticon"
    const val CONFIG_NAME = "config.txt"

    data class State(
        val enabled: Boolean = false,
        val lightVersion: Long = 0,
        val darkVersion: Long = 0,
        val lightHasBackup: Boolean = false,
        val darkHasBackup: Boolean = false,
        val applyToken: Long = 0,
        val restartToken: Long = 0,
    ) {
        fun version(slot: Keys.Slot): Long =
            if (slot == Keys.Slot.LIGHT) lightVersion else darkVersion

        fun hasBackup(slot: Keys.Slot): Boolean =
            if (slot == Keys.Slot.LIGHT) lightHasBackup else darkHasBackup
    }

    fun parse(text: String): State {
        var enabled = false
        var lightVersion = 0L
        var darkVersion = 0L
        var lightHasBackup = false
        var darkHasBackup = false
        var applyToken = 0L
        var restartToken = 0L
        for (raw in text.split('\n')) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (key) {
                Keys.ENABLED -> enabled = value == "1" || value.equals("true", true)
                Keys.APPLY_TOKEN -> applyToken = value.toLongOrNull() ?: 0L
                Keys.RESTART_TOKEN -> restartToken = value.toLongOrNull() ?: 0L
                Keys.versionKey(Keys.Slot.LIGHT) -> lightVersion = value.toLongOrNull() ?: 0L
                Keys.versionKey(Keys.Slot.DARK) -> darkVersion = value.toLongOrNull() ?: 0L
                Keys.hasBackupKey(Keys.Slot.LIGHT) -> lightHasBackup = value == "1" || value.equals("true", true)
                Keys.hasBackupKey(Keys.Slot.DARK) -> darkHasBackup = value == "1" || value.equals("true", true)
            }
        }
        return State(enabled, lightVersion, darkVersion, lightHasBackup, darkHasBackup, applyToken, restartToken)
    }

    fun format(state: State): String =
        "${Keys.ENABLED}=${if (state.enabled) 1 else 0}\n" +
            "${Keys.versionKey(Keys.Slot.LIGHT)}=${state.lightVersion}\n" +
            "${Keys.versionKey(Keys.Slot.DARK)}=${state.darkVersion}\n" +
            "${Keys.hasBackupKey(Keys.Slot.LIGHT)}=${if (state.lightHasBackup) 1 else 0}\n" +
            "${Keys.hasBackupKey(Keys.Slot.DARK)}=${if (state.darkHasBackup) 1 else 0}\n" +
            "${Keys.APPLY_TOKEN}=${state.applyToken}\n" +
            "${Keys.RESTART_TOKEN}=${state.restartToken}\n"

    /** 按文件名找落盘文件：正本与「name (n)」副本取 mtime 最新；没有返回 null。 */
    fun resolveFile(name: String): File? {
        val files = try {
            DIR.listFiles() ?: emptyArray()
        } catch (t: Throwable) {
            emptyArray()
        }
        val pattern = Regex("""${Regex.escape(name)}( \(\d+\))?""")
        var best: File? = null
        for (f in files) {
            if (!f.isFile || !pattern.matches(f.name)) continue
            if (best == null || f.lastModified() > best.lastModified()) best = f
        }
        return best
    }

    fun readState(): State? {
        val file = resolveFile(CONFIG_NAME) ?: return null
        return try {
            parse(file.readText())
        } catch (t: Throwable) {
            null
        }
    }

    fun configMtime(): Long = try {
        resolveFile(CONFIG_NAME)?.lastModified() ?: 0L
    } catch (t: Throwable) {
        0L
    }
}
