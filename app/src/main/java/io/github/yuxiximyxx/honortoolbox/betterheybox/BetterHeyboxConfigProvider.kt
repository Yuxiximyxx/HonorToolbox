package io.github.yuxiximyxx.honortoolbox.betterheybox

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 小黑盒增强的配置 Provider（v1.7.0）：与快服务改向的
 * QuickGestureConfigProvider 同范式（exported、call 供数），
 * 差别是本功能设置项多且有软件内面板的回写需求，故除
 * [HeyboxConfigContract.METHOD_GET_ALL] 全量供出外，还开
 * [HeyboxConfigContract.METHOD_PUT] 单键回写：小黑盒软件内
 * 设置面板改动经此落回同一份镜像，两个入口不双轨。
 * 配置内容为模块自身的开关与文本，不含账号敏感信息，
 * 与既有 Provider 同口径不做调用方校验。
 */
class BetterHeyboxConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            HeyboxConfigContract.METHOD_GET_ALL -> {
                val out = Bundle()
                for ((key, value) in HeyboxSettingsStore.prefs(ctx).all) {
                    when (value) {
                        is Boolean -> out.putBoolean(
                            HeyboxConfigContract.encodeKey(HeyboxConfigContract.TYPE_BOOL, key), value,
                        )
                        is String -> out.putString(
                            HeyboxConfigContract.encodeKey(HeyboxConfigContract.TYPE_STRING, key), value,
                        )
                    }
                }
                out
            }
            HeyboxConfigContract.METHOD_PUT -> {
                val key = arg ?: return null
                val type = extras?.getString(HeyboxConfigContract.EXTRA_TYPE) ?: return null
                val value = extras.getString(HeyboxConfigContract.EXTRA_VALUE) ?: return null
                val editor = HeyboxSettingsStore.prefs(ctx).edit()
                when (type) {
                    HeyboxConfigContract.TYPE_BOOL -> editor.putBoolean(key, value == "true")
                    HeyboxConfigContract.TYPE_STRING -> editor.putString(key, value)
                    else -> return null
                }
                editor.apply()
                Bundle()
            }
            else -> null
        }
    }

    override fun query(
        uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
