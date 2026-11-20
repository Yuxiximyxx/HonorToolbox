package io.github.yuxiximyxx.honortoolbox

import java.io.File

/**
 * 功能总开关的跨进程通道（hook 侧读取）：设置应用把各功能开关
 * 写到 /sdcard/Download/HonorToolbox/features.txt（一行一功能，
 * 形如 shadeclock=1），统一入口在分发 hook 前现读；文件或对应
 * 行缺失时按调用方给的默认值处理（与各功能原模块的默认一致）。
 * 与其他落盘通道同口径：正本与「features (n).txt」副本都是
 * 候选，取 mtime 最新的一份。
 *
 * 注意：自带总开关语义的功能（手势改向、长按导航条唤醒、
 * 图标日夜切换的 enabled 都在各自的配置文件里）不经本通道，
 * 避免两处开关互相打架；本通道目前只管通知中心主题。
 */
object FeatureGate {

    const val SHADE_CLOCK = "shadeclock"

    private val BASE_DIR = File("/sdcard/Download/HonorToolbox")
    private val CANDIDATE_NAME = Regex("""features( \(\d+\))?\.txt""")

    fun isEnabled(feature: String, default: Boolean): Boolean {
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
        val file = best ?: return default
        return try {
            parse(file.readText())[feature] ?: default
        } catch (t: Throwable) {
            default
        }
    }

    fun parse(text: String): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim() == "1"
        }
        return out
    }

    fun format(states: Map<String, Boolean>): String {
        val sb = StringBuilder()
        for ((k, v) in states.toSortedMap()) {
            sb.append(k).append('=').append(if (v) '1' else '0').append('\n')
        }
        return sb.toString()
    }
}
