package com.better.heybox

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import com.better.heybox.watch.WatchConfig
import io.github.yuxiximyxx.honortoolbox.betterheybox.HeyboxConfigContract
import java.util.concurrent.Executors

/**
 * 配置门面（荣耀工具箱合入版）：对外 API 与原模块一致
 * （init/get/getBoolean/setBoolean/getString/setString/
 * contains），内部换成三层读序——
 *
 * 1. 工具箱镜像快照：经 [HeyboxConfigContract] 的 Provider
 *    全量拉取（TTL 1.2 秒），工具箱设置页与小黑盒软件内
 *    面板的写入都汇到这份镜像，是用户设置的权威来源；
 * 2. 宿主本地存储：小黑盒私有目录下的原 `betterheybox`
 *    SharedPreferences。旧独立版的全部设置本来就存这里，
 *    镜像未包含的键回退读它——旧配置天然迁移，不需搬运；
 *    同时它仍是运行时状态（打卡日期、已读列表、版本下限
 *    等）的落点，[get] 原样返回它，与原语义一致；
 * 3. 调用方给的默认值。
 *
 * 写入：先写本地（原行为与副作用保留：WatchConfig /
 * PostFilterHook / PostDetailCleanHook 的失效通知），若是
 * 用户设置键（布尔默认值表内或字符串设置键集合内）再异步
 * 回推镜像，保证两个设置入口看到同一份值。快照刷新时若
 * 发现镜像值变化，逐键回调 MainModule.onSettingChanged，
 * 等价于原模块设置变更的推送语义（延迟安装的 hook 会因此
 * 被补装）。
 */
object HeyboxPrefs {

    const val PREFS_NAME = "betterheybox"

    private const val MIRROR_TTL_MS = 1_200L

    @Volatile private var sPrefs: SharedPreferences? = null

    @Volatile private var mirror: Map<String, Any> = emptyMap()

    @Volatile private var mirrorAt = 0L

    private val pushExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "betterheybox-prefs-push").apply { isDaemon = true }
    }

    /** 字符串型用户设置键（写入时回推镜像）；运行时状态键不在此列。 */
    private val STRING_USER_KEYS: Set<String> = setOf(
        App.KEY_DAILY_TASK_PICTURE, App.KEY_DAILY_TASK_NORMAL, App.KEY_DAILY_TASK_CHANNEL,
        App.KEY_SHARE_CHANNEL,
        App.KEY_MY_TASK_TEXT, App.KEY_MY_TASK_KEYWORDS, App.KEY_MY_TASK_MAX_PER_DAY,
        App.KEY_VIDEO_DIR, App.KEY_IMAGE_CACHE_DIR,
        App.KEY_SCREENSHOT_HIDE_CUSTOM,
        App.KEY_POST_DETAIL_HIDE_IDS,
        App.KEY_POST_MIN_LEVEL, App.KEY_POST_MIN_LIKE, App.KEY_POST_MIN_COMMENT,
        App.KEY_POST_MIN_FAVOUR, App.KEY_POST_KEYWORDS, App.KEY_POST_TAGS,
        App.KEY_AI_PROVIDER, App.KEY_AI_BASE_URL, App.KEY_AI_MODEL, App.KEY_AI_TOKEN,
        App.KEY_AI_PROMPT, App.KEY_AI_MAX_TOKENS,
        App.KEY_COMMENT_KEYWORDS,
        App.KEY_GAME_LIB_HIDE_TYPES, App.KEY_GAME_LIB_HIDE_ENTRIES,
        App.KEY_GAME_LIB_HIDE_SECTION_NAMES,
        App.KEY_MSG_BADGE_ENTRIES, App.KEY_MSG_FULL_HIDE_ENTRIES,
        App.KEY_BROWSER_REDIRECT_FORCE, App.KEY_BROWSER_REDIRECT_BLOCK, App.KEY_BROWSER_TARGET,
        App.KEY_WEBVIEW_ENTRY_URL,
        App.KEY_WATCH_USERS, App.KEY_WATCH_KEYWORDS, App.KEY_WATCH_TOPICS,
        App.KEY_WATCH_INTERVAL_MIN, App.KEY_WATCH_WINDOW_DAYS, App.KEY_WATCH_WINDOW_MIN,
        App.KEY_WATCH_PUSH_DINGTALK, App.KEY_WATCH_PUSH_WXPUSHER,
        App.KEY_WATCH_PUSH_ONEBOT, App.KEY_WATCH_PUSH_CUSTOM,
        "comment_panel_actions",
    )

    @JvmStatic
    fun init(context: Context?) {
        if (context != null && sPrefs == null) {
            sPrefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        refreshMirror(force = false)
    }

    @JvmStatic
    fun get(): SharedPreferences? {
        var prefs = sPrefs
        if (prefs == null) {
            val context = App.resolveAppContext()
            if (context != null) {
                prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sPrefs = prefs
            }
        }
        return prefs
    }

    @JvmStatic
    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        refreshMirror(force = false)
        val mirrored = mirror[key]
        if (mirrored is Boolean) return mirrored
        val prefs = get() ?: return defaultValue
        if (key == App.KEY_SCREENSHOT_HIDE_IMAGE_COUNT) {
            val legacyValue = prefs.all[key]
            if (legacyValue is String) {
                val repaired = legacyValue.toBooleanStrictOrNull() ?: defaultValue
                prefs.edit().putBoolean(key, repaired).apply()
                return repaired
            }
        }
        return prefs.getBoolean(key, defaultValue)
    }

    @JvmStatic
    fun contains(key: String): Boolean {
        refreshMirror(force = false)
        return mirror.containsKey(key) || (get()?.contains(key) ?: false)
    }

    @JvmStatic
    fun setBoolean(key: String, value: Boolean): Boolean {
        val prefs = get() ?: return false
        prefs.edit().putBoolean(key, value).apply()
        afterLocalWrite(key)
        if (isUserKey(key)) pushToMirror(key, HeyboxConfigContract.TYPE_BOOL, value.toString())
        return true
    }

    @JvmStatic
    fun getString(key: String, defaultValue: String?): String? {
        refreshMirror(force = false)
        val mirrored = mirror[key]
        if (mirrored is String) return mirrored
        val prefs = get()
        return if (prefs != null) prefs.getString(key, defaultValue) else defaultValue
    }

    @JvmStatic
    fun setString(key: String, value: String?): Boolean {
        val prefs = get() ?: return false
        prefs.edit().putString(key, value).apply()
        afterLocalWrite(key)
        if (isUserKey(key) && value != null) {
            pushToMirror(key, HeyboxConfigContract.TYPE_STRING, value)
        }
        return true
    }

    /** 原写入副作用：与原模块一致的失效通知。 */
    private fun afterLocalWrite(key: String) {
        if (isWatchKey(key)) {
            WatchConfig.invalidate()
        }
        if (isFeedFilterKey(key)) {
            val hook = com.better.heybox.hooks.PostFilterHook.get()
            hook?.invalidateConfig()
        }
        if (isPostDetailKey(key)) {
            com.better.heybox.hooks.PostDetailCleanHook.refresh()
        }
    }

    private fun isUserKey(key: String): Boolean =
        App.BOOLEAN_DEFAULTS.containsKey(key) || key in STRING_USER_KEYS

    private fun pushToMirror(key: String, type: String, value: String) {
        // 本进程内先行生效，避免快照刷新前读到旧值
        val parsed: Any = if (type == HeyboxConfigContract.TYPE_BOOL) value == "true" else value
        mirror = mirror + (key to parsed)
        pushExecutor.execute {
            try {
                val ctx = App.resolveAppContext() ?: return@execute
                val extras = Bundle().apply {
                    putString(HeyboxConfigContract.EXTRA_TYPE, type)
                    putString(HeyboxConfigContract.EXTRA_VALUE, value)
                }
                ctx.contentResolver.call(
                    HeyboxConfigContract.URI, HeyboxConfigContract.METHOD_PUT, key, extras,
                )
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun refreshMirror(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - mirrorAt < MIRROR_TTL_MS) return
        mirrorAt = now
        val ctx = App.resolveAppContext() ?: return
        try {
            val bundle = ctx.contentResolver.call(
                HeyboxConfigContract.URI, HeyboxConfigContract.METHOD_GET_ALL, null, null,
            ) ?: return
            val next = HashMap<String, Any>()
            for (encoded in bundle.keySet()) {
                val sep = encoded.indexOf(':')
                if (sep <= 0) continue
                val type = encoded.substring(0, sep)
                val key = encoded.substring(sep + 1)
                when (type) {
                    HeyboxConfigContract.TYPE_BOOL ->
                        next[key] = bundle.getBoolean(encoded, false)
                    HeyboxConfigContract.TYPE_STRING ->
                        bundle.getString(encoded)?.let { next[key] = it }
                }
            }
            val prev = mirror
            if (next == prev) return
            mirror = next
            val module = MainModule.get() ?: return
            val changed = (prev.keys + next.keys).filter { prev[it] != next[it] }
            for (key in changed) {
                if (isUserKey(key)) {
                    try {
                        module.onSettingChanged(key)
                    } catch (ignored: Throwable) {
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun isWatchKey(key: String?): Boolean = key != null && key.startsWith("watch_")

    private fun isFeedFilterKey(key: String?): Boolean {
        if (key == null) {
            return false
        }
        return key.startsWith("post_filter_") || key.startsWith("post_min_")
                || "promote_ad" == key || "block_video_post" == key
                || "flow_diagnose" == key || "verbose_log" == key
                || "single_column_feed" == key
                || "ai_base_url" == key || "ai_model" == key
    }

    private fun isPostDetailKey(key: String?): Boolean =
        key != null && key.startsWith("post_detail_")
}
