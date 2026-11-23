package io.github.yuxiximyxx.honortoolbox.wetypeglass

import android.net.Uri

/**
 * 输入法玻璃的配置直供合同（v1.8.0 合入 MOS液态输入，照
 * v1.6.5 快服务改向 / v1.7.0 小黑盒增强已验通的
 * ContentProvider 直供模式）：
 *
 * - 工具箱设置页把设置以字符串写进工具箱私有镜像偏好
 *   [MIRROR_PREFS]（键名/默认值与原模块 Config 逐字一致，
 *   原模块本来就全值字符串存储），[WetypeGlassConfigProvider]
 *   对外供出；
 * - 宿主（微信/豆包输入法）进程里的 WgPrefs 经
 *   ContentResolver.call 拉全量快照（[METHOD_GET_ALL]），
 *   拉不到时为空快照、hook 端自然回落 Config.DEFAULTS；
 * - 没有软件内面板回写需求，故只开 getAll 一个方法。
 */
object WgConfigContract {

    const val AUTHORITY = "io.github.yuxiximyxx.honortoolbox.wetypeglass"
    val URI: Uri = Uri.parse("content://$AUTHORITY")

    const val METHOD_GET_ALL = "getAll"

    /** 工具箱进程内的镜像偏好文件名。 */
    const val MIRROR_PREFS = "wetypeglass_settings"
}
