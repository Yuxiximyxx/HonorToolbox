package io.github.yuxiximyxx.honortoolbox.betterheybox

import android.content.Context
import android.content.SharedPreferences
import com.better.heybox.App

/**
 * 工具箱进程侧的小黑盒增强配置存储：设置页读写都落在这份
 * 镜像偏好上，[BetterHeyboxConfigProvider] 把它供给小黑盒
 * 进程现读。键名与默认值与原模块完全一致（见
 * [App.BOOLEAN_DEFAULTS] 与各字符串键的原语义）。
 */
object HeyboxSettingsStore {

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(HeyboxConfigContract.MIRROR_PREFS, Context.MODE_PRIVATE)

    fun bool(context: Context, key: String): Boolean {
        val p = prefs(context)
        if (p.contains(key)) return p.getBoolean(key, false)
        return App.BOOLEAN_DEFAULTS[key] ?: false
    }

    fun putBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun string(context: Context, key: String, default: String = ""): String =
        prefs(context).getString(key, default) ?: default

    fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }
}
