package io.github.yuxiximyxx.honortoolbox.navwake.core

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * ModuleLog —— 统一日志出口（传统 Xposed API 移植版）。
 *
 * 原 libxposed 版的两路（module.log + android.util.Log）里，module.log 一路
 * 改走 XposedBridge.log（同样进 LSPosed 的模块日志），语义与级别规则不变：
 * 级别由设置页的「日志级别」控制，但 ERROR 永远打。
 */
object ModuleLog {
    private const val TAG = "NavWake"

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
        val allowed = priority == Log.ERROR || level >= requiredLevel
        if (!allowed) return
        runCatching { Log.println(priority, "$TAG/$tag", message) }
        if (error != null) runCatching { Log.println(Log.ERROR, "$TAG/$tag", Log.getStackTraceString(error)) }
        runCatching { XposedBridge.log("$TAG/$tag: $message") }
        if (error != null) runCatching { XposedBridge.log(error) }
    }
}
