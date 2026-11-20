package io.github.yuxiximyxx.honortoolbox.shadeclock

import io.github.yuxiximyxx.honortoolbox.shadeclock.theme.ThemePackage
import java.io.File
import java.util.zip.ZipFile

/**
 * 覆盖层设置的跨进程通道：App 侧把「选中主题/戳记/高度档/顶部样式」
 * 写成一个小文件 /sdcard/Download/HonorToolbox/shadeclock/stamp.txt（经
 * MediaStore 写入，普通应用对公共 Download 目录的合规写路径）；
 * SystemUI 侧用普通文件 IO 读（它对 Download 的读写已被探针日志
 * 实证可行）。主题资产放在 /sdcard/Download/HonorToolbox/shadeclock/
 * themes/<id>/：v2.0.18 起只存主题包本体一份（theme.pkg，渲染器
 * 直接读 zip），不再解压散图（散图会被图库索引）；旧版解压目录
 * （manifest.xml + 资产 + fonts/）在设置页迁移打包前仍可经
 * [themeEntry] 兜底读取。目录根放 .nomedia 兜底防图库扫描。
 *
 * stamp.txt 格式（12 行）：themeId / stamp / heightMode / topStyle /
 * showBackground（1=画主题整幅背景图、0=不画，v2.0.3 加；旧 4 行
 * 文件缺此行时按 0 处理，与设置页开关默认关一致）/
 * showWidgets（1=画温度/风向/电量数据小模块、0=不画，v2.0.5 加；
 * 旧 5 行文件缺此行时按 0 处理，与设置页开关默认关一致）/
 * offsetDp（覆盖层垂直偏移，整数 dp，负值上移，v2.0.7 加；
 * 旧 6 行文件缺此行时按 0 处理）/
 * hideCarrier（1=隐藏运营商文字、0=不隐藏，v2.0.8 加；设置页
 * 开关默认开，旧 7 行文件缺此行时按 1 处理，与默认一致）/
 * offsetXDp（覆盖层水平偏移，整数 dp，负值左移、正值右移，
 * v2.0.23 加；旧 8 行文件缺此行时按 0 处理）/
 * weekdayMondayStart（选中主题的星期编号模式，1=周一起算、
 * 0=周日起算，v2.0.23 加；按主题记，设置侧写戳记时取当前
 * 选中主题的那一份下发。两个已知主题的 XML 都只有
 * srcid="#day_of_week" 一句、不声明编号口径，渲染器无法
 * 自动分辨，只能按主题存一份；旧 9 行文件缺此行时按 0
 * 处理，即引擎原口径周日=1，AAA 这类周日=1 编号的主题
 * 默认即对）/
 * cardMode（1=头部显示应用卡片、0=显示主题时钟簇，
 * v2.0.32 加；全局试验开关、设置页默认开，旧 10 行文件
 * 缺此行时按 1 处理，与默认一致；只有明确写 0 才是时钟
 * 簇）/
 * cardProvider（头部卡片的 provider 组件，flatten 串
 * 「包名/类名」，v2.0.32 加；全局一份、设置页自选，
 * 缺行/空串时按默认的天气小卡处理）。前 10 行与旧版
 * 逐行同义，新行只追加在末尾——新旧两版交叉读写不串位。
 * SystemUI 侧每次覆盖层挂载与每秒 tick 比对文件 mtime 与内容，
 * 变了就重解析主题、重载位图——切换主题免强停系统界面。
 *
 * 同目录还有一个一次性标记文件 restart.txt（v2.0.12）：设置页
 * 「重启系统界面」按钮把请求时刻（epoch 毫秒）写进去；SystemUI
 * 侧的看守（ShadeClockHook 进程级轮询，与覆盖层是否挂载无关）
 * 读到「本进程启动之后」的新请求就先把文件内容覆写为已消费，
 * 再结束自己进程——SystemUI 是常驻应用、会被系统拉起，模块
 * 随新进程重新加载，等同强停系统界面，不依赖 root。消费后
 * 覆写 + 只认本进程启动后的时间戳，双保险防重启后重复触发。
 */
object StampStore {

    const val TOP_STYLE_ORIGINAL = "original"
    const val TOP_STYLE_THEME = "theme"

    /**
     * 头部卡片默认 provider（v2.0.32）：天气小卡——探针日志
     * 实测 com.hihonor.android.totemweather 的
     * card.small.SmallCardWeatherProvider（min 532×560 px，
     * 无 configure 要求时绑上即出内容）。设置页没选过卡、
     * 或戳记第 12 行缺失/损坏时按它处理。
     */
    const val DEFAULT_CARD_PROVIDER =
        "com.hihonor.android.totemweather/" +
            "com.hihonor.android.totemweather.card.small.SmallCardWeatherProvider"

    const val HEIGHT_FOLLOW = 0
    const val HEIGHT_THIRD = 1
    const val HEIGHT_HALF = 2

    /**
     * 覆盖层垂直偏移范围（dp）：负值上移、正值下移（v2.0.7 立
     * -120～+40；v2.0.24 放宽到 -400～+160：有些主题的时钟簇
     * 本身在画布中部偏下，-120 拉满仍偏下）。只是滑杆/落盘的
     * 钳制界，存量已存值（如 -120）仍在界内、原样有效；戳记
     * 行位与 dp 语义不变。
     */
    const val OFFSET_DP_MIN = -400
    const val OFFSET_DP_MAX = 160

    /** 覆盖层水平偏移范围（dp）：负值左移、正值右移，对称（v2.0.23）。 */
    const val OFFSET_X_DP_MIN = -120
    const val OFFSET_X_DP_MAX = 120

    /** MediaStore 相对目录（无首尾斜杠）。 */
    const val STAMP_REL_DIR = "Download/HonorToolbox/shadeclock"
    const val THEMES_REL_DIR = "Download/HonorToolbox/shadeclock/themes"
    const val STAMP_NAME = "stamp.txt"
    const val RESTART_NAME = "restart.txt"

    /** 主题包本体在主题目录里的固定文件名（v2.0.18）。 */
    const val THEME_PACKAGE_NAME = "theme.pkg"
    const val NOMEDIA_NAME = ".nomedia"

    val BASE_DIR = File("/sdcard/Download/HonorToolbox/shadeclock")
    val THEMES_DIR = File(BASE_DIR, "themes")
    val STAMP_FILE = File(BASE_DIR, "stamp.txt")
    val RESTART_FILE = File(BASE_DIR, "restart.txt")

    data class Stamp(
        val themeId: String,
        val stamp: Long,
        val heightMode: Int,
        val topStyle: String,
        /** 是否绘制主题的整幅背景图；默认 false（设置页开关默认关）。 */
        val showBackground: Boolean = false,
        /** 是否绘制温度/风向/电量数据小模块；默认 false（设置页开关默认关，v2.0.5）。 */
        val showWidgets: Boolean = false,
        /** 覆盖层垂直偏移（dp，负值上移）；默认 0（v2.0.7）。 */
        val offsetDp: Int = 0,
        /** 是否隐藏运营商文字（锁屏/控制中心/通知中心三处）；默认 true（设置页开关默认开，v2.0.8）。 */
        val hideCarrier: Boolean = true,
        /** 覆盖层水平偏移（dp，负值左移、正值右移）；默认 0（v2.0.23）。 */
        val offsetXDp: Int = 0,
        /** 选中主题的星期编号是否按周一起算；默认 false=周日起算（引擎原口径，v2.0.23）。 */
        val weekdayMondayStart: Boolean = false,
        /** 头部显示应用卡片（与主题时钟簇互斥）；默认 true（设置页开关默认开，v2.0.32）。 */
        val cardMode: Boolean = true,
        /** 头部卡片的 provider 组件 flatten 串；默认天气小卡（v2.0.32）。 */
        val cardProvider: String = DEFAULT_CARD_PROVIDER,
    )

    fun themeDir(id: String): File = File(THEMES_DIR, id)

    /** 主题包本体文件（themes/<id>/theme.pkg）。 */
    fun themePackage(id: String): File = File(themeDir(id), THEME_PACKAGE_NAME)

    /**
     * SystemUI 侧定位主题入口：优先包本体文件，但包必须真含
     * 锁屏 manifest 才算数（v2.0.19）——旧迁移可能留下残包，
     * 残包在时不能把还完好的解压目录挡住；残包且无解压目录
     * 时如实返回 null（调用方回退原头部），不拿坏包冒充。
     * 旧版解压目录（设置页迁移还没跑到时）以 manifest.xml
     * 存在为凭兜底；两者都没有返回 null。
     */
    fun themeEntry(id: String): File? {
        val pkg = themePackage(id)
        if (pkg.isFile && isValidPackage(pkg)) return pkg
        val dir = themeDir(id)
        if (File(dir, "manifest.xml").isFile) return dir
        return null
    }

    /** 包本体有效性探针：能当 zip 打开且定位得到锁屏 manifest。 */
    private fun isValidPackage(file: File): Boolean {
        return try {
            ZipFile(file).use { ThemePackage.lockscreenPrefix(it) != null }
        } catch (t: Throwable) {
            false
        }
    }

    fun formatStamp(
        themeId: String,
        stamp: Long,
        heightMode: Int,
        topStyle: String,
        showBackground: Boolean,
        showWidgets: Boolean,
        offsetDp: Int,
        hideCarrier: Boolean,
        offsetXDp: Int = 0,
        weekdayMondayStart: Boolean = false,
        cardMode: Boolean = true,
        cardProvider: String = DEFAULT_CARD_PROVIDER,
    ): String =
        "$themeId\n$stamp\n$heightMode\n$topStyle\n" +
            "${if (showBackground) 1 else 0}\n${if (showWidgets) 1 else 0}\n" +
            "${offsetDp.coerceIn(OFFSET_DP_MIN, OFFSET_DP_MAX)}\n" +
            "${if (hideCarrier) 1 else 0}\n" +
            "${offsetXDp.coerceIn(OFFSET_X_DP_MIN, OFFSET_X_DP_MAX)}\n" +
            "${if (weekdayMondayStart) 1 else 0}\n" +
            "${if (cardMode) 1 else 0}\n" +
            "${cardProvider.ifEmpty { DEFAULT_CARD_PROVIDER }}\n"

    // ---- 戳记多候选取胜（v2.0.19）----
    // 设置 App 经 MediaStore 写 stamp.txt：应用被卸载重装后，
    // 旧安装创建的 stamp.txt 不再归新安装所有，新安装查不到
    // 它、插入同名文件又被系统改名成「stamp (1).txt」，写入
    // 静默落到副本上，正本永远停在重装前的内容——真机实证：
    // 重装后在设置里改选主题，SystemUI 读到的 themeId/戳记
    // 纹丝不动。SystemUI 侧有普通文件 IO，能看全目录：把
    // stamp.txt 与全部「stamp (n).txt」都当候选，按内容里
    // 的时间戳（第 2 行）取最大者为有效戳记；胜者不是正本
    // 时顺手把内容回写正本做一次修复收敛。restart.txt 同理。

    private val STAMP_CANDIDATE_NAME = Regex("""stamp( \(\d+\))?\.txt""")
    private val RESTART_CANDIDATE_NAME = Regex("""restart( \(\d+\))?\.txt""")

    /** 最近一次胜出的戳记文件名（诊断用：判断是否走了副本采纳）。 */
    @Volatile
    var lastStampSource: String? = null
        private set

    private class StampCandidate(val file: File, val stamp: Stamp, val raw: String)

    private fun parseStampText(text: String): Stamp? {
        val lines = text.split('\n').map { it.trim() }
        if (lines.size < 4 || lines[0].isEmpty()) return null
        return Stamp(
            themeId = lines[0],
            stamp = lines[1].toLongOrNull() ?: 0L,
            heightMode = lines[2].toIntOrNull() ?: HEIGHT_FOLLOW,
            topStyle = lines[3].ifEmpty { TOP_STYLE_ORIGINAL },
            // 第 5 行缺失（旧版 stamp）按关处理，与开关默认一致
            showBackground = lines.getOrNull(4) == "1",
            // 第 6 行缺失（v2.0.4 及更旧 stamp）按关处理，与开关默认一致
            showWidgets = lines.getOrNull(5) == "1",
            // 第 7 行缺失（v2.0.6 及更旧 stamp）按 0 处理
            offsetDp = (lines.getOrNull(6)?.toIntOrNull() ?: 0)
                .coerceIn(OFFSET_DP_MIN, OFFSET_DP_MAX),
            // 第 8 行缺失（v2.0.7 及更旧 stamp）按开处理，与开关默认开一致；
            // 只有明确写 0 才是不隐藏
            hideCarrier = lines.getOrNull(7) != "0",
            // 第 9 行缺失（v2.0.22 及更旧 stamp）按 0 处理
            offsetXDp = (lines.getOrNull(8)?.toIntOrNull() ?: 0)
                .coerceIn(OFFSET_X_DP_MIN, OFFSET_X_DP_MAX),
            // 第 10 行缺失（v2.0.22 及更旧 stamp）按周日起算处理，
            // 与引擎原口径一致；只有明确写 1 才是周一起算
            weekdayMondayStart = lines.getOrNull(9) == "1",
            // 第 11 行缺失（v2.0.31 及更旧 stamp）按开处理，与
            // 设置页开关默认开一致；只有明确写 0 才是时钟簇
            cardMode = lines.getOrNull(10) != "0",
            // 第 12 行缺失/为空（同上）按默认天气小卡处理
            cardProvider = lines.getOrNull(11)
                ?.takeIf { it.isNotEmpty() } ?: DEFAULT_CARD_PROVIDER,
        )
    }

    private fun scanStampCandidates(): List<StampCandidate> {
        val out = ArrayList<StampCandidate>()
        val files = try {
            BASE_DIR.listFiles() ?: emptyArray()
        } catch (t: Throwable) {
            emptyArray()
        }
        for (f in files) {
            if (!f.isFile || !STAMP_CANDIDATE_NAME.matches(f.name)) continue
            try {
                val raw = f.readText()
                val stamp = parseStampText(raw) ?: continue
                out.add(StampCandidate(f, stamp, raw))
            } catch (t: Throwable) {
                // 读不出的候选跳过
            }
        }
        return out
    }

    /** 胜者：内容时间戳最大；并列取 mtime 最新。 */
    private fun pickStampWinner(): StampCandidate? {
        val candidates = scanStampCandidates()
        if (candidates.isEmpty()) return null
        return candidates.maxWithOrNull(
            compareBy<StampCandidate> { it.stamp.stamp }
                .thenBy { it.file.lastModified() }
        )
    }

    /** SystemUI 侧读取；无任何可用候选返回 null。 */
    fun readStamp(): Stamp? {
        val winner = pickStampWinner() ?: return null
        lastStampSource = winner.file.name
        // 收敛（v2.0.19 回写正本；v2.0.20 起连副本一并清掉）：
        // 胜者内容回写正本后，把其余候选副本删掉——目录里最
        // 终只剩正本一份，下一轮采纳不再有歧义。v2.0.19 真机
        // 出现过「选了新主题、目录里却始终没有一份能被采纳
        // 的最新戳记」：写入侧（设置 App 经 MediaStore）落盘
        // 形态不可控，读者侧把存量副本收敛干净，至少保证已
        // 落盘的写入不被一堆旧副本淹没。删除口径保守：只删
        // 时间戳严格小于胜者、或与胜者内容完全相同的副本；
        // 胜者副本本身仅在正本已确实持有其内容后才删。删除
        // 失败无妨，采纳结果不受影响。
        var canonicalHasWinner = winner.file.name == STAMP_NAME
        if (!canonicalHasWinner) {
            try {
                STAMP_FILE.writeText(winner.raw)
                canonicalHasWinner = true
            } catch (t: Throwable) {
                // 尽力而为：回写失败时保留胜者副本，不删它
            }
        }
        try {
            for (c in scanStampCandidates()) {
                if (c.file.name == STAMP_NAME) continue
                val isWinner = c.file.absolutePath == winner.file.absolutePath
                if (isWinner && !canonicalHasWinner) continue
                if (c.stamp.stamp < winner.stamp.stamp || c.raw == winner.raw) {
                    c.file.delete()
                }
            }
        } catch (t: Throwable) {
            // 尽力而为
        }
        return winner.stamp
    }

    /** 诊断用：全部戳记候选的摘要（文件名=themeId@时间戳），供预热日志留证候选全貌。 */
    fun stampCandidatesSummary(): String {
        val candidates = scanStampCandidates()
        if (candidates.size <= 1) return ""
        return candidates.joinToString(prefix = "[", postfix = "]") {
            "${it.file.name}=${it.stamp.themeId}@${it.stamp.stamp}"
        }
    }

    fun stampMtime(): Long = try {
        pickStampWinner()?.file?.lastModified()
            ?: if (STAMP_FILE.isFile) STAMP_FILE.lastModified() else 0L
    } catch (t: Throwable) {
        0L
    }

    private fun restartCandidateFiles(): List<File> {
        val out = ArrayList<File>()
        val files = try {
            BASE_DIR.listFiles() ?: emptyArray()
        } catch (t: Throwable) {
            emptyArray()
        }
        for (f in files) {
            if (f.isFile && RESTART_CANDIDATE_NAME.matches(f.name)) out.add(f)
        }
        if (RESTART_FILE.isFile && out.none { it.name == RESTART_NAME }) {
            out.add(RESTART_FILE)
        }
        return out
    }

    /**
     * SystemUI 侧读取重启请求（v2.0.12；v2.0.19 起多候选）：内容
     * 是设置页写入的请求时刻（epoch 毫秒）。与 stamp.txt 同理，
     * 重装后设置页的写入可能落到「restart (n).txt」副本上，故
     * 扫全部候选取最大值；无候选/内容不是数字返回 null（已消费
     * 的文件内容被覆写为 "0"，0 值由调用方按「不晚于进程启动」
     * 判废）。
     */
    fun readRestartRequestMs(): Long? {
        var best: Long? = null
        for (f in restartCandidateFiles()) {
            try {
                val v = f.readText().trim().toLongOrNull() ?: continue
                if (best == null || v > best) best = v
            } catch (t: Throwable) {
                // 读不出的候选跳过
            }
        }
        return best
    }

    /**
     * SystemUI 侧消费重启请求：把全部候选文件内容覆写为 "0"。
     * 在结束进程之前调用——新进程读到的就是已消费内容，配合
     * 「只认本进程启动之后的时间戳」双保险防重复触发。覆写失败
     * 不抛（调用方该重启还是重启，时间戳判定仍能挡住新进程）。
     */
    fun consumeRestartRequest() {
        for (f in restartCandidateFiles()) {
            try {
                if (f.name == RESTART_NAME) {
                    f.writeText("0")
                } else if (!f.delete()) {
                    // 副本删不掉就覆写判废，别留下可被误读的旧请求
                    f.writeText("0")
                }
            } catch (t: Throwable) {
                // 尽力而为
            }
        }
    }
}
