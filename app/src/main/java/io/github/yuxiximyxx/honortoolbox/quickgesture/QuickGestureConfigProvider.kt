package io.github.yuxiximyxx.honortoolbox.quickgesture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 快服务改向的配置 Provider（v1.6.5）：hook 所在进程（荣耀
 * 快服务）没有任何特殊权限也能跨进程拿到当前配置的通道。
 *
 * 背景：文件通道在快服务进程里读不到（无直读 Download 权
 * 限）；Settings.Global 通道（v1.6.4）写不进去（工具箱没
 * 有 WRITE_SETTINGS、用户未授「修改系统设置」）。本
 * Provider 与资源库增强的 ConfigProvider 同范式：
 * exported、call("get") 返回 Bundle，配置文本不含敏感
 * 信息、不做调用方校验（同 ConfigProvider 的 get 口径）。
 *
 * 数据源与设置页同源：设置页读回展示、系统页开关读写的
 * 是镜像偏好 quickgesture_settings，本 Provider 在工具箱
 * 进程内经 [ConfigStore.readMirrorConfig] 读同一份、按
 * [ConfigStore.format] 供出——页面显示什么，hook 就读到
 * 什么，不经文件、不存在副本与权限问题。
 *
 * 只读：除 call("get") 外不提供任何读写方法。
 */
class QuickGestureConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            ConfigStore.PROVIDER_METHOD_GET -> Bundle().apply {
                putString(
                    ConfigStore.PROVIDER_EXTRA_CONFIG,
                    ConfigStore.format(ConfigStore.readMirrorConfig(ctx)),
                )
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
