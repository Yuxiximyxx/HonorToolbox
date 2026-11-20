package io.github.yuxiximyxx.honortoolbox.shadeclock.theme

import java.util.zip.ZipFile

/**
 * 主题包（.hnt/.hwt 本质都是 zip）里锁屏目录的定位：导入侧校验
 * 与渲染侧读取共用同一套口径——先读 unlock/theme.xml 的
 * dynamicPath（缺省 "lockscreen"），再确认
 * unlock/<dynamicPath>/manifest.xml 存在。
 */
object ThemePackage {

    /** 返回形如 "unlock/lockscreen/" 的条目前缀；找不到锁屏 manifest 返回 null。 */
    fun lockscreenPrefix(zip: ZipFile): String? {
        var dynamicPath = "lockscreen"
        val themeXml = zip.getEntry("unlock/theme.xml")
        if (themeXml != null) {
            try {
                val text = zip.getInputStream(themeXml).readBytes()
                    .toString(Charsets.UTF_8)
                val m = Regex("""dynamicPath\s*=\s*"([^"]+)"""").find(text)
                if (m != null) dynamicPath = m.groupValues[1]
            } catch (t: Throwable) {
                // theme.xml 读不出就按缺省 lockscreen 找
            }
        }
        val prefix = "unlock/$dynamicPath/"
        return if (zip.getEntry(prefix + "manifest.xml") != null) prefix else null
    }
}
