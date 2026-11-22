package io.github.yuxiximyxx.honortoolbox.betterheybox

import android.net.Uri

/**
 * 小黑盒增强的配置直供合同（v1.7.0 合入，照 v1.6.5 快服务
 * 改向已真机验通的 ContentProvider 直供模式）：
 *
 * - 工具箱设置页把设置写进工具箱私有镜像偏好
 *   [MIRROR_PREFS]，[BetterHeyboxConfigProvider] 对外供出；
 * - 宿主（小黑盒）进程里的 HeyboxPrefs 门面经
 *   ContentResolver.call 拉全量快照（[METHOD_GET_ALL]），
 *   读序：快照 → 宿主本地旧存储 → 默认值；
 * - 小黑盒软件内设置面板的写入经 [METHOD_PUT] 回推镜像，
 *   两个设置入口共用同一份配置、互不覆盖。
 *
 * Bundle 编码：键前缀 `b:` 为 Boolean、`s:` 为 String。
 */
object HeyboxConfigContract {

    const val AUTHORITY = "io.github.yuxiximyxx.honortoolbox.betterheybox"
    val URI: Uri = Uri.parse("content://$AUTHORITY")

    const val METHOD_GET_ALL = "getAll"
    const val METHOD_PUT = "put"

    const val EXTRA_TYPE = "type"
    const val EXTRA_VALUE = "value"
    const val TYPE_BOOL = "b"
    const val TYPE_STRING = "s"

    /** 工具箱进程内的镜像偏好文件名。 */
    const val MIRROR_PREFS = "betterheybox_settings"

    fun encodeKey(type: String, key: String): String = "$type:$key"
}
