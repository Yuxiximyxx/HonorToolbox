package io.github.yuxiximyxx.honortoolbox.wetypeglass

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 输入法玻璃的配置 Provider（v1.8.0）：与快服务改向的
 * QuickGestureConfigProvider 同范式（exported、call 供数）。
 * 镜像偏好里全是字符串值，快照 Bundle 以原键名平铺供出，
 * hook 端 WgPrefs 直接收。配置内容为模块自身的开关与参数，
 * 不含账号敏感信息，与既有 Provider 同口径不做调用方校验。
 */
class WetypeGlassConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != WgConfigContract.METHOD_GET_ALL) return null
        val ctx = context ?: return null
        val out = Bundle()
        for ((key, value) in WgSettingsStore.prefs(ctx).all) {
            if (value is String) out.putString(key, value)
        }
        return out
    }

    override fun query(
        uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
