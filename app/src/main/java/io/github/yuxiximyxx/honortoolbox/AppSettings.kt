package io.github.yuxiximyxx.honortoolbox

import android.content.Context

/** 工具箱设置应用的软件主题（只影响设置应用自身的界面）。 */
internal enum class AppThemeMode { System, Light, Dark }

/**
 * 设置应用自身的通用偏好：软件主题、悬浮底栏、玻璃质感
 * （v1.2.0 起，「设置」页）。只存应用私有的 SharedPreferences，
 * 与任何功能配置通道（features.txt / 各功能落盘文件 / 功能
 * 镜像偏好）完全隔离——这三项只决定设置应用自己长什么样，
 * 不影响任何 hook 与功能行为。
 */
internal object AppSettings {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_FLOATING_BAR = "floating_bar"
    private const val KEY_GLASS_BAR = "glass_bar"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun themeMode(context: Context): AppThemeMode =
        when (prefs(context).getString(KEY_THEME_MODE, null)) {
            "light" -> AppThemeMode.Light
            "dark" -> AppThemeMode.Dark
            else -> AppThemeMode.System
        }

    fun saveThemeMode(context: Context, mode: AppThemeMode) {
        val value = when (mode) {
            AppThemeMode.System -> "system"
            AppThemeMode.Light -> "light"
            AppThemeMode.Dark -> "dark"
        }
        prefs(context).edit().putString(KEY_THEME_MODE, value).apply()
    }

    /** 悬浮底栏：默认开；关掉后底栏贴底、改为普通导航栏。 */
    fun floatingBar(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_BAR, true)

    fun saveFloatingBar(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_BAR, enabled).apply()
    }

    /** 玻璃质感：默认开；关掉后悬浮底栏变实色不透明、无模糊。 */
    fun glassBar(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GLASS_BAR, true)

    fun saveGlassBar(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLASS_BAR, enabled).apply()
    }
}
