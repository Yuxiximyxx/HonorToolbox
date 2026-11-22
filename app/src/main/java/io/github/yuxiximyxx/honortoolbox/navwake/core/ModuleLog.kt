package io.github.yuxiximyxx.honortoolbox.navwake.core

import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ModuleLog —— 统一日志出口（传统 Xposed API 移植版）。
 *
 * 原 libxposed 版的两路（module.log + android.util.Log）里，module.log 一路
 * 改走 XposedBridge.log（同样进 LSPosed 的模块日志），语义与级别规则不变：
 * 级别由设置页的「日志级别」控制，但 ERROR 永远打。
 *
 * v1.6.2 起加第三路落盘：/sdcard/Download/HonorToolbox/navwake.log。
 * 起因是 v1.6.1 真机反馈长按唤醒不生效，而既有两路日志都要连电脑
 * 或 root 才能看，定不了案。落盘一路不受日志级别门控（安装/拦截
 * 判定/放行原因全量留痕），同一行 2 秒内重复出现只记一次防刷屏；
 * 文件超过 1MB 时整份重开。写失败静默，绝不影响宿主。
 */
object ModuleLog {
    private const val TAG = "NavWake"

    private val LOG_FILE = File("/sdcard/Download/HonorToolbox/navwake.log")
    private const val FILE_CAP_BYTES = 1_000_000L
    private const val DEDUPE_WINDOW_MS = 2_000L
    private val fileLock = Any()
    private val recentLines = HashMap<String, Long>()

    @Volatile
    private var level: Int = 1

    /** 当前是否全量级别（2）—— 阶段 0 定位时靠它决定要不要打调用栈。 */
    val isVerbose: Boolean
        get() = level >= 2

    fun setLevel(value: Int) {
        level = value.coerceIn(0, 2)
    }

    fun v(tag: String = TAG, message: String) {
        emit(Log.VERBOSE, 2, tag, message, null)
    }

    fun d(tag: String = TAG, message: String) {
        emit(Log.DEBUG, 1, tag, message, null)
    }

    fun i(tag: String = TAG, message: String) {
        emit(Log.INFO, 0, tag, message, null)
    }

    fun w(tag: String = TAG, message: String, error: Throwable? = null) {
        emit(Log.WARN, 0, tag, message, error)
    }

    fun e(tag: String = TAG, message: String, error: Throwable? = null) {
        emit(Log.ERROR, 0, tag, message, error)
    }

    private fun emit(priority: Int, requiredLevel: Int, tag: String, message: String, error: Throwable?) {
        // 落盘一路先行且不受级别门控：诊断要的就是被级别挡掉的判定细节。
        fileLog(tag, message, error)
        val allowed = priority == Log.ERROR || level >= requiredLevel
        if (!allowed) return
        runCatching { Log.println(priority, "$TAG/$tag", message) }
        if (error != null) runCatching { Log.println(Log.ERROR, "$TAG/$tag", Log.getStackTraceString(error)) }
        runCatching { XposedBridge.log("$TAG/$tag: $message") }
        if (error != null) runCatching { XposedBridge.log(error) }
    }

    /** 落盘（节流版）：同一行在窗口内重复出现只记第一次；全程静默失败。 */
    private fun fileLog(tag: String, message: String, error: Throwable?) {
        runCatching {
            val key = "$tag|$message"
            val now = System.currentTimeMillis()
            synchronized(fileLock) {
                val last = recentLines[key]
                if (last != null && now - last < DEDUPE_WINDOW_MS) return
                recentLines[key] = now
                if (recentLines.size > 256) recentLines.clear()
                LOG_FILE.parentFile?.mkdirs()
                if (LOG_FILE.exists() && LOG_FILE.length() > FILE_CAP_BYTES) {
                    LOG_FILE.delete()
                }
                val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))
                val sb = StringBuilder()
                sb.append(stamp).append(' ').append(TAG).append('/').append(tag)
                    .append(": ").append(message).append('\n')
                if (error != null) {
                    sb.append(Log.getStackTraceString(error)).append('\n')
                }
                LOG_FILE.appendText(sb.toString())
            }
        }
    }
}
