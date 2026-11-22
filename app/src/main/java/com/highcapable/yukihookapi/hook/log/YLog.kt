package com.highcapable.yukihookapi.hook.log

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * YukiHookAPI 的 YLog 在传统 Xposed API 上的等价物：同时写
 * logcat 与 LSPosed 日志（XposedBridge.log），与原模块
 * EnvType.BOTH 的语义一致。仅实现原模块实际调用的四个级别。
 */
object YLog {

    enum class EnvType { BOTH, XPOSED_ONLY, ANDROID_ONLY }

    fun debug(msg: String, tr: Throwable? = null, tag: String = "YLog", env: EnvType = EnvType.BOTH) =
        emit(Log.DEBUG, msg, tr, tag, env)

    fun info(msg: String, tr: Throwable? = null, tag: String = "YLog", env: EnvType = EnvType.BOTH) =
        emit(Log.INFO, msg, tr, tag, env)

    fun warn(msg: String, tr: Throwable? = null, tag: String = "YLog", env: EnvType = EnvType.BOTH) =
        emit(Log.WARN, msg, tr, tag, env)

    fun error(msg: String, tr: Throwable? = null, tag: String = "YLog", env: EnvType = EnvType.BOTH) =
        emit(Log.ERROR, msg, tr, tag, env)

    private fun emit(level: Int, msg: String, tr: Throwable?, tag: String, env: EnvType) {
        if (env != EnvType.XPOSED_ONLY) {
            try {
                when (level) {
                    Log.DEBUG -> Log.d(tag, msg, tr)
                    Log.INFO -> Log.i(tag, msg, tr)
                    Log.WARN -> Log.w(tag, msg, tr)
                    else -> Log.e(tag, msg, tr)
                }
            } catch (ignored: Throwable) {
            }
        }
        if (env != EnvType.ANDROID_ONLY) {
            try {
                XposedBridge.log("[$tag] $msg")
                if (tr != null) XposedBridge.log(tr)
            } catch (ignored: Throwable) {
            }
        }
    }
}
