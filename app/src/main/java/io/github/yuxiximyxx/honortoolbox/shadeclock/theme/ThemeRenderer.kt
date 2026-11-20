package io.github.yuxiximyxx.honortoolbox.shadeclock.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import java.io.File
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** 一次绘制的环境量：当前时间、电量、真实天气、屏幕尺寸。 */
class RenderEnv(
    val now: Calendar,
    val batteryLevel: Int,
    /** 荣耀引擎口径：1=充电中，3=已充满，其余=0。 */
    val batteryState: Int,
    val weather: Map<String, EVal>,
    /** 覆盖层宽度 px（= #screen_width）。 */
    val screenWidthPx: Int,
    /** 设备屏幕高度 px（= #screen_height，取面板所在窗口高度）。 */
    val screenHeightPx: Int,
    /**
     * 当前主题的星期编号是否按周一起算（按主题设置下发，
     * v2.0.23）：false=周日起算（默认，#day_of_week 喂引擎原
     * 口径 Calendar 周日=1）；true=周一起算（喂之前换算
     * ((dow+5)%7)+1）。主题 XML 不声明编号口径、无法自动
     * 分辨，只能按主题存一份，见 EvalContext。
     */
    val weekdayMondayStart: Boolean = false,
)

/**
 * 变量解析上下文（每次绘制新建）：内建变量 → Weather.* 真实值 →
 * 主题 Var/VarArray（懒求值 + 本轮缓存）→ 未知一律 0。
 * month 按引擎惯例 0 起（VarArray 月份表靠它索引）；day_of_week 的
 * 基线是 Java Calendar 口径周日=1…周六=7（荣耀引擎 #day_of_week
 * 原口径，v2.0.23 改回——此前按周一=1 喂，.hwt 样本的星期图集
 * 是周日=1 编号（亲读 w_1=SUN、w_4=WED、w_5=THU），周四被喂
 * 4 选中 WED、差一天）。但编号口径是著者约定、XML 里不声明
 * （两个已知主题都只有 srcid="#day_of_week" 一句），云镜图集
 * 恰是按周一=1 编的（其在真锁屏上本就快一天，是著者偏差；
 * 旧喂法凑巧替它抵消过）。故 v2.0.23 起按主题存一份模式
 * （env.weekdayMondayStart，设置页按主题切）：周一起算的主题
 * 喂之前换算 ((dow+5)%7)+1（周日→7、周一→1、周四→4）；默认
 * 周日起算、原值直喂，AAA 这类主题装上即对，云镜需在设置
 * 里对该主题切一次「周一起算」。
 */
class EvalContext(
    private val model: ThemeModel,
    env: RenderEnv,
) : VarResolver {

    private val builtins: Map<String, EVal>
    private val weather: Map<String, EVal> = env.weather
    private val cache = HashMap<String, EVal>()
    private val resolving = HashSet<String>()

    /** Weather.* 无真实值被引用过的次数（Text 据此整段留空）。 */
    var missingWeatherTouches = 0
        private set

    init {
        val cal = env.now
        // 基线：Calendar 原值即周日=1…周六=7，与引擎
        // #day_of_week 一致；当前主题若按周一起算编号，换算
        // 后再喂（(dow+5)%7)+1：周日 1→7、周一 2→1、周四 5→4
        val dowRaw = cal.get(Calendar.DAY_OF_WEEK)
        val dow = if (env.weekdayMondayStart) (dowRaw + 5) % 7 + 1 else dowRaw
        builtins = mapOf(
            "hour24" to EVal.Num(cal.get(Calendar.HOUR_OF_DAY).toDouble()),
            "hour" to EVal.Num(cal.get(Calendar.HOUR).toDouble()),
            "minute" to EVal.Num(cal.get(Calendar.MINUTE).toDouble()),
            "second" to EVal.Num(cal.get(Calendar.SECOND).toDouble()),
            "year" to EVal.Num(cal.get(Calendar.YEAR).toDouble()),
            "month" to EVal.Num(cal.get(Calendar.MONTH).toDouble()),
            "date" to EVal.Num(cal.get(Calendar.DAY_OF_MONTH).toDouble()),
            "day_of_week" to EVal.Num(dow.toDouble()),
            "battery_level" to EVal.Num(env.batteryLevel.toDouble()),
            "battery_state" to EVal.Num(env.batteryState.toDouble()),
            "screen_width" to EVal.Num(env.screenWidthPx.toDouble()),
            "screen_height" to EVal.Num(env.screenHeightPx.toDouble()),
            "lunar_calendar" to EVal.Str(""),
        )
    }

    override fun resolve(name: String): EVal {
        builtins[name]?.let { return it }
        if (name.startsWith("Weather.")) {
            weather[name]?.let { return it }
            missingWeatherTouches++
            return EVal.Missing
        }
        cache[name]?.let { return it }
        if (!resolving.add(name)) return EVal.Num(0.0)
        val value = compute(name)
        resolving.remove(name)
        cache[name] = value
        return value
    }

    private fun compute(name: String): EVal {
        for (arr in model.varArrays) {
            if (arr.varName == name) {
                val idx = Expr.eval(arr.indexExpr, this).asNum().toInt()
                val item = arr.items.getOrNull(idx) ?: return EVal.Num(0.0)
                return if (arr.numeric) {
                    EVal.Num(item.toDoubleOrNull() ?: 0.0)
                } else {
                    EVal.Str(item)
                }
            }
        }
        return when (val def = model.vars[name]) {
            is VarDef.ExprDef -> Expr.eval(def.expression, this)
            is VarDef.AnimDef -> EVal.Num(def.finalValue)
            null -> EVal.Num(0.0)
        }
    }
}

/**
 * 数据驱动的锁屏主题渲染器：运行时解析选中主题的 manifest.xml，
 * 按原始坐标 1:1 绘制（1 设计单位 = 1px，不缩放）。
 * 子集：Group/Image/Text/DateTime/Time + Var/VarArray/VariableAnimation
 * （动画一律取终态静态渲染）。缺图跳过并记一行日志，未知变量容错为 0。
 */
class ThemeRenderer(private val log: (String) -> Unit) {

    private var source: ThemeSource? = null
    private var model: ThemeModel? = null
    private var typeface: Typeface? = null
    private val bitmaps = HashMap<String, Bitmap?>()
    private val missingLogged = HashSet<String>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 最近一次绘制的内容最下缘（根坐标 px，含垂直偏移），「跟随内容」高度用。 */
    var contentBottomPx: Float = 0f
        private set

    /**
     * 最近一次绘制的内容簇最上缘（根坐标 px，与 [contentBottomPx]
     * 同一统计口径：整屏级背景与屏幕底部区域的元素不计）。
     * 无可统计元素时为 [Float.MAX_VALUE]。垂直偏移的钳制用
     * （簇顶不小于 0，v2.0.7）。
     */
    var contentTopPx: Float = Float.MAX_VALUE
        private set

    /**
     * 最近一次绘制的内容簇最左/最右缘（根坐标 px，统计口径与
     * [contentTopPx] 同——整屏级背景与屏幕底部区域的元素不计）。
     * 无可统计元素时 left 为 [Float.MAX_VALUE]、right 为 0。
     * 水平偏移的钳制用（簇不许移出画布左右，v2.0.23）。
     */
    var contentLeftPx: Float = Float.MAX_VALUE
        private set
    var contentRightPx: Float = 0f
        private set

    /**
     * 覆盖层垂直偏移（px，负值上移）：draw 时整体加到根 y 上，
     * 所有元素连同内容顶/底统计一起平移（v2.0.7）。由
     * HeaderOverlay 按 stamp 的 offsetDp 换算并钳制后赋值。
     */
    @Volatile
    var offsetYPx: Float = 0f

    /**
     * 覆盖层水平偏移（px，负值左移、正值右移）：与 [offsetYPx]
     * 同机制，draw 时整体加到根 x 上（v2.0.23）。由
     * HeaderOverlay 按 stamp 的 offsetXDp 换算并钳制后赋值。
     */
    @Volatile
    var offsetXPx: Float = 0f

    /** 最近一次 draw 实际绘制的可见元素数（覆盖层诊断用）。 */
    var lastDrawnElements: Int = 0
        private set
    private var drawnCounter = 0

    /**
     * 是否绘制主题的整幅背景图（设置页「主题背景图」开关，v2.0.3）。
     * false 时：文档顺序里第一个 dst 覆盖画布 ≥95% 的 Image 视为
     * 背景、跳过不画（云镜的蓝天整图 xr.png 即此），其余元素照常；
     * 通知栏透出原背景，不再是一块硬边矩形。true 时与 v2.0.2 一致。
     * 由 HeaderOverlay 按 stamp 在每次挂载时赋值。
     */
    @Volatile
    var showBackgroundImage: Boolean = true
    private var backgroundHandled = false
    private var backgroundSkipLogged = false

    /**
     * 是否绘制数据小模块（设置页「小模块（温度/风向/电量）」开关，
     * v2.0.5，默认关）。false 时跳过 [widgetSkipNodes] 里的节点：
     * 表达式/选图依赖 Weather.today.* 或 #battery_level /
     * #battery_state 的元素，以及它们所在的小模块组整组（组里的
     * 静态图标一并跳过——云镜底部温度/风向/电量三列即此结构）。
     * 时间、日期、星期、静态文字与图片照常；跟随内容高度按剩余
     * 元素统计。由 HeaderOverlay 按 stamp 在每次挂载时赋值。
     */
    @Volatile
    var showWidgets: Boolean = false
    private var widgetSkipNodes: Set<XNode> = emptySet()

    /**
     * 加载主题（v2.0.18）：entry 是主题包本体文件（zip 直读）或
     * 旧版解压目录（迁移前兼容），按形态选 [ThemeSource]。
     */
    fun load(entry: File): Boolean {
        source?.close()
        source = null
        bitmaps.clear()
        missingLogged.clear()
        val src = openSource(entry)
        source = src
        model = try {
            src?.manifestBytes()?.let { ThemeParser.parse(it) }
        } catch (t: Throwable) {
            log("theme parse failed in $entry: ${t.javaClass.name}: ${t.message}")
            null
        }
        typeface = src?.let { loadTypeface(it) }
        backgroundSkipLogged = false
        widgetSkipNodes = model?.let { computeWidgetSkipNodes(it) } ?: emptySet()
        if (widgetSkipNodes.isNotEmpty()) {
            log("theme widgets: ${widgetSkipNodes.size} node(s) marked as data widgets")
        }
        if (model == null) log("theme load failed: no valid manifest.xml in $entry")
        return model != null
    }

    private fun openSource(entry: File): ThemeSource? {
        return try {
            when {
                entry.isFile -> {
                    val zip = ZipThemeSource(entry)
                    if (zip.valid) {
                        zip
                    } else {
                        zip.close()
                        log("theme package has no lockscreen manifest: $entry")
                        null
                    }
                }
                entry.isDirectory -> DirThemeSource(entry)
                else -> null
            }
        } catch (t: Throwable) {
            log("theme source open failed in $entry: ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    /** 属性串是否直接引用天气/电量数据（#Weather.…、@Weather.…、#battery_…）。 */
    private fun exprDependsOnData(expr: String): Boolean =
        expr.contains("Weather.") ||
            expr.contains("battery_level") ||
            expr.contains("battery_state")

    /**
     * 预计算「数据小模块」跳过集（load 时一次，绘制时只查集合）：
     * 1) 数据依赖变量：定义表达式引用了 Weather./battery 数据的主题
     *    Var（如云镜的 WeatherIcon），及索引带数据依赖的 VarArray
     *    变量——元素经一层 #变量 间接引用数据的也算数据元素。
     * 2) 小模块组：子树里含数据元素、且不含 Time/DateTime/静态
     *    text 文字的 Group 整组入集（保护时钟组不被误伤）；组内
     *    静态图标（温度/风向图标那种自身无数据依赖的）随组跳过。
     * 3) 不在小模块组里的数据元素（混在大组里的）逐个入集。
     */
    private fun computeWidgetSkipNodes(m: ThemeModel): Set<XNode> {
        val dataVars = HashSet<String>()
        for ((name, def) in m.vars) {
            if (def is VarDef.ExprDef && exprDependsOnData(def.expression)) {
                dataVars.add(name)
            }
        }
        for (arr in m.varArrays) {
            if (exprDependsOnData(arr.indexExpr)) dataVars.add(arr.varName)
        }
        fun nodeDepends(node: XNode): Boolean =
            node.attrs.values.any { v ->
                exprDependsOnData(v) || dataVars.any { dv -> v.contains("#$dv") }
            }
        fun subtreeAny(node: XNode, pred: (XNode) -> Boolean): Boolean {
            if (pred(node)) return true
            return node.children.any { subtreeAny(it, pred) }
        }
        fun hasDataVisual(node: XNode): Boolean =
            subtreeAny(node) { it.tag in VISUAL_TAGS && nodeDepends(it) }
        fun hasPrimaryContent(node: XNode): Boolean =
            subtreeAny(node) {
                it.tag == "Time" || it.tag == "DateTime" ||
                    (it.tag == "Text" && !it.attr("text").isNullOrBlank())
            }
        val skip = HashSet<XNode>()
        fun walk(node: XNode) {
            if (node.tag == "Group" && hasDataVisual(node) && !hasPrimaryContent(node)) {
                skip.add(node)
                return
            }
            if (node.tag in VISUAL_TAGS && node.tag != "Group" && nodeDepends(node)) {
                skip.add(node)
            }
            for (c in node.children) walk(c)
        }
        for (root in m.roots) walk(root)
        return skip
    }

    val isLoaded: Boolean get() = model != null

    /**
     * 预热采纳（v2.0.11）：把另一个已 load 完成的渲染器（install
     * 阶段在后台线程预热好的）的主题状态整体接过来——模型、字体、
     * 已解码位图缓存、数据小模块跳过集——本实例的绘制开关
     * （showBackgroundImage / showWidgets / offsetYPx）由调用方按
     * stamp 另行赋值，不在此列。采纳后源实例不得再被使用。
     */
    fun adoptFrom(other: ThemeRenderer) {
        source?.close()
        source = other.source
        model = other.model
        typeface = other.typeface
        bitmaps.clear()
        bitmaps.putAll(other.bitmaps)
        missingLogged.clear()
        missingLogged.addAll(other.missingLogged)
        widgetSkipNodes = other.widgetSkipNodes
        backgroundHandled = false
        backgroundSkipLogged = false
        contentBottomPx = other.contentBottomPx
        contentTopPx = other.contentTopPx
        contentLeftPx = other.contentLeftPx
        contentRightPx = other.contentRightPx
    }

    /**
     * 位图预解码（v2.0.11 预热用）：位图本是首绘时懒解码
     * （[bitmap]），冷进程首帧要现场解码整套图集，是下拉首显
     * 迟滞的一环。这里按 [ThemeSource.imagePaths] 枚举的图片
     * 相对路径逐个预解码进缓存——[bitmap] 的缓存键就是相对
     * 路径，枚举口径与之一致，任何 manifest 能引用到的文件
     * 都会命中。上限 512 个文件防病态主题包。只应在后台线程
     * 调用。
     */
    fun warmBitmapCache() {
        val src = source ?: return
        if (model == null) return
        val paths = try {
            src.imagePaths().take(512)
        } catch (t: Throwable) {
            log("theme bitmap warm scan failed: ${t.javaClass.name}: ${t.message}")
            return
        }
        var warmed = 0
        for (rel in paths) {
            if (bitmap(rel) != null) warmed++
        }
        log("theme bitmap cache warmed: $warmed/${paths.size} image(s)")
    }

    /**
     * 主题字体（v2.0.18 改字节构建）：资产只剩包本体一份、没有
     * 落盘的字体文件可给 createFromFile，改走 Font.Builder
     * （API 29+，minSdk 34 可用）从字节内存构建。注意
     * Font.Builder 只收 direct buffer（v2.0.19 修：ByteBuffer
     * .wrap 的堆缓冲会被它以 IllegalArgumentException 拒绝，
     * 真机日志实证云镜字体每会话必失败一次）。失败回 null，
     * 绘制侧回退系统字体（与旧版 createFromFile 失败同口径）。
     */
    private fun loadTypeface(src: ThemeSource): Typeface? {
        val bytes = src.fontBytes() ?: return null
        return try {
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.flip()
            val font = Font.Builder(buffer).build()
            val family = FontFamily.Builder(font).build()
            Typeface.CustomFallbackBuilder(family).build()
        } catch (t: Throwable) {
            log("theme font load failed: ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    /** 用一块空 Canvas 跑一遍量测，得到内容下缘（顺带簇顶 [contentTopPx]，不真正绘制）。 */
    fun measureContentBottom(env: RenderEnv): Float {
        draw(Canvas(), env)
        return contentBottomPx
    }

    fun draw(canvas: Canvas, env: RenderEnv) {
        val m = model ?: return
        val ctx = EvalContext(m, env)
        contentBottomPx = 0f
        contentTopPx = Float.MAX_VALUE
        contentLeftPx = Float.MAX_VALUE
        contentRightPx = 0f
        drawnCounter = 0
        backgroundHandled = false
        for (root in m.roots) {
            drawNode(root, canvas, ctx, env, offsetXPx, offsetYPx, 1f, 1f)
        }
        lastDrawnElements = drawnCounter
    }

    // ---------- 递归绘制 ----------

    private fun drawNode(
        node: XNode,
        canvas: Canvas,
        ctx: EvalContext,
        env: RenderEnv,
        ox: Float,
        oy: Float,
        scaleAcc: Float,
        alphaAcc: Float,
    ) {
        // 小模块开关关时整组/逐个跳过（v2.0.5）：连表达式都不求值
        if (!showWidgets && widgetSkipNodes.contains(node)) return
        val visExpr = node.attr("visibility")
        if (visExpr != null) {
            val v = Expr.eval(visExpr, ctx)
            if (!v.truthy()) return
        }
        var alpha = alphaAcc
        val alphaExpr = node.attr("alpha")
        if (alphaExpr != null) {
            alpha = (alpha * alphaFactor(Expr.eval(alphaExpr, ctx))).coerceIn(0f, 1f)
            if (alpha <= 0f) return
        }
        if (node.tag in VISUAL_TAGS) drawnCounter++
        when (node.tag) {
            "Group" -> {
                val dx = numAttr(node, "x", ctx)
                val dy = numAttr(node, "y", ctx)
                var groupScale = 1f
                val scaleExpr = node.attr("scale")
                if (scaleExpr != null) {
                    groupScale = Expr.eval(scaleExpr, ctx).asNum().toFloat()
                    if (groupScale == 0f) return
                }
                val nx = ox + dx * scaleAcc
                val ny = oy + dy * scaleAcc
                val ns = scaleAcc * groupScale
                for (child in node.children) {
                    if (child.tag in VISUAL_TAGS) {
                        drawNode(child, canvas, ctx, env, nx, ny, ns, alpha)
                    }
                }
            }
            "Image" -> drawImage(node, canvas, ctx, env, ox, oy, scaleAcc, alpha)
            "Text" -> drawText(node, canvas, ctx, env, ox, oy, scaleAcc, alpha)
            "DateTime" -> {
                val format = node.attr("format") ?: "yyyy-MM-dd"
                val content = formatDate(format, env.now)
                drawTextLike(node, canvas, ctx, env, ox, oy, scaleAcc, alpha, content)
            }
            "Time" -> drawTime(node, canvas, ctx, env, ox, oy, scaleAcc, alpha)
        }
    }

    private fun alphaFactor(v: EVal): Float {
        val a = v.asNum()
        val f = if (a <= 1.0) a else a / 255.0
        return f.toFloat().coerceIn(0f, 1f)
    }

    /** 属性数值：纯数字直读，否则走表达式；缺失/Missing 给 default。 */
    private fun numAttr(node: XNode, name: String, ctx: EvalContext, default: Float = 0f): Float {
        val raw = node.attr(name) ?: return default
        raw.toDoubleOrNull()?.let { return it.toFloat() }
        val v = Expr.eval(raw, ctx)
        return if (v === EVal.Missing) default else v.asNum().toFloat()
    }

    private fun hasNumAttr(node: XNode, name: String): Boolean = node.attr(name) != null

    // ---------- 位图 ----------

    private fun bitmap(relPath: String): Bitmap? {
        if (bitmaps.containsKey(relPath)) return bitmaps[relPath]
        val src = source ?: return null
        var bmp: Bitmap? = null
        val bytes = src.assetBytes(relPath)
        if (bytes != null) {
            bmp = try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (t: Throwable) {
                null
            }
        }
        if (bmp == null && missingLogged.add(relPath)) {
            log("theme image missing or undecodable: $relPath")
        }
        bitmaps[relPath] = bmp
        return bmp
    }

    /** srcid 变体路径：time/time.png + 3 -> time/time_3.png。 */
    private fun variantPath(src: String, id: String): String {
        val dot = src.lastIndexOf('.')
        val slash = src.lastIndexOf('/')
        return if (dot > slash) {
            src.substring(0, dot) + "_" + id + src.substring(dot)
        } else {
            src + "_" + id
        }
    }

    private fun resolveImagePath(node: XNode, ctx: EvalContext): String? {
        var path: String? = null
        val srcExp = node.attr("srcExp")
        if (srcExp != null) {
            val v = Expr.eval(srcExp, ctx)
            if (v === EVal.Missing) return null
            path = v.asStr()
        } else {
            path = node.attr("src")
        }
        if (path.isNullOrEmpty()) return null
        val srcid = node.attr("srcid")
        if (srcid != null) {
            val idVal = Expr.eval(srcid, ctx)
            if (idVal === EVal.Missing) return null
            val id = if (idVal is EVal.Num) formatNum(idVal.v) else idVal.asStr()
            path = variantPath(path, id)
        }
        return path
    }

    // ---------- Image ----------

    private fun drawImage(
        node: XNode,
        canvas: Canvas,
        ctx: EvalContext,
        env: RenderEnv,
        ox: Float,
        oy: Float,
        s: Float,
        alpha: Float,
    ) {
        val path = resolveImagePath(node, ctx) ?: return
        // 约定俗成的假状态栏图不渲染（通知栏有真状态栏）
        if (path.substringAfterLast('/') == "statusbar.png") return
        val bmp = bitmap(path) ?: return

        val wE = if (hasNumAttr(node, "w")) numAttr(node, "w", ctx)
        else if (hasNumAttr(node, "width")) numAttr(node, "width", ctx) else null
        val hE = if (hasNumAttr(node, "h")) numAttr(node, "h", ctx)
        else if (hasNumAttr(node, "height")) numAttr(node, "height", ctx) else null

        var dw = bmp.width.toFloat()
        var dh = bmp.height.toFloat()
        if (wE != null && hE != null) {
            dw = wE; dh = hE
        } else if (wE != null) {
            dw = wE; dh = wE * bmp.height / bmp.width
        } else if (hE != null) {
            dh = hE; dw = hE * bmp.width / bmp.height
        }
        if (dw <= 0f || dh <= 0f) return

        val rw = dw * s
        val rh = dh * s
        val anchorX = ox + numAttr(node, "x", ctx) * s
        val anchorY = oy + numAttr(node, "y", ctx) * s
        val left = when (node.attr("align")) {
            "center" -> anchorX - rw / 2f
            "right" -> anchorX - rw
            else -> anchorX
        }
        val top = when (node.attr("alignV")) {
            "center" -> anchorY - rh / 2f
            "bottom" -> anchorY - rh
            else -> anchorY
        }
        val dst = RectF(left, top, left + rw, top + rh)
        // 主题背景图开关（v2.0.3）：关时跳过文档顺序里第一个覆盖画布
        // ≥95% 的 Image（整幅背景图）。覆盖判定：dst 罩住画布
        // （#screen_width × #screen_height）的 95% 区域。云镜的
        // xr.png 即此；hwt 的 b.png 全屏拼贴也会被跳过（已知取舍，
        // 开关打开即恢复）。跳过后其余全屏图仍照常画。
        if (!showBackgroundImage && !backgroundHandled) {
            val cw = env.screenWidthPx.toFloat()
            val ch = env.screenHeightPx.toFloat()
            // 覆盖判定用偏移前的坐标（v2.0.7 垂直、v2.0.23 水平）：
            // 整体平移后 dst 四边都动了，不还原会漏判整幅背景图
            // （开关关时被误画）。
            val rawLeft = dst.left - offsetXPx
            val rawRight = dst.right - offsetXPx
            val rawTop = dst.top - offsetYPx
            val rawBottom = dst.bottom - offsetYPx
            if (cw > 0f && ch > 0f &&
                rawLeft <= cw * 0.05f && rawTop <= ch * 0.05f &&
                rawRight >= cw * 0.95f && rawBottom >= ch * 0.95f
            ) {
                backgroundHandled = true
                drawnCounter--
                if (!backgroundSkipLogged) {
                    backgroundSkipLogged = true
                    log("theme background image skipped (switch off): $path")
                }
                return
            }
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        if (node.attr("scaleType") == "center_crop") {
            val scale = max(rw / bmp.width, rh / bmp.height)
            val srcW = rw / scale
            val srcH = rh / scale
            val srcL = ((bmp.width - srcW) / 2f).roundToInt().coerceIn(0, bmp.width)
            val srcT = ((bmp.height - srcH) / 2f).roundToInt().coerceIn(0, bmp.height)
            val srcR = (srcL + srcW.roundToInt()).coerceAtMost(bmp.width)
            val srcB = (srcT + srcH.roundToInt()).coerceAtMost(bmp.height)
            canvas.drawBitmap(bmp, Rect(srcL, srcT, srcR, srcB), dst, paint)
        } else {
            canvas.drawBitmap(bmp, null, dst, paint)
        }
        trackContent(env, dst.left, top, dst.right, top + rh, rh)
    }

    // ---------- Text / DateTime ----------

    private fun drawText(
        node: XNode,
        canvas: Canvas,
        ctx: EvalContext,
        env: RenderEnv,
        ox: Float,
        oy: Float,
        s: Float,
        alpha: Float,
    ) {
        val content = resolveTextContent(node, ctx) ?: return
        if (content.isEmpty()) return
        drawTextLike(node, canvas, ctx, env, ox, oy, s, alpha, content)
    }

    private fun resolveTextContent(node: XNode, ctx: EvalContext): String? {
        node.attr("text")?.let { return it }
        val before = ctx.missingWeatherTouches
        node.attr("textExp")?.let { exp ->
            val v = Expr.eval(exp, ctx)
            if (v === EVal.Missing || ctx.missingWeatherTouches != before) return null
            return v.asStr()
        }
        val format = node.attr("format") ?: return null
        val parasRaw = node.attr("paras")
        val args = ArrayList<EVal>()
        if (!parasRaw.isNullOrBlank()) {
            for (token in parasRaw.split(',')) {
                val t = token.trim()
                if (t.isEmpty()) continue
                val v = when {
                    t.startsWith("#") -> ctx.resolve(t.substring(1))
                    t.startsWith("@") -> ctx.resolve(t.substring(1))
                    else -> t.toDoubleOrNull()?.let { EVal.Num(it) } ?: ctx.resolve(t)
                }
                if (v === EVal.Missing || ctx.missingWeatherTouches != before) return null
                args.add(v)
            }
        }
        return safeFormat(format, args)
    }

    private fun safeFormat(format: String, args: List<EVal>): String {
        val converted: List<Any> = args.map { v ->
            when (v) {
                is EVal.Num -> if (v.v == Math.floor(v.v)) v.v.toLong() else v.v
                is EVal.Str -> v.s
                EVal.Missing -> ""
            }
        }
        try {
            @Suppress("SpreadOperator")
            return String.format(Locale.ROOT, format, *converted.toTypedArray())
        } catch (t: Throwable) {
            // 兜底：顺序替换 %d/%s/%f，%% 还原为 %
            val sb = StringBuilder()
            var argIdx = 0
            var i = 0
            while (i < format.length) {
                val c = format[i]
                if (c == '%' && i + 1 < format.length) {
                    val n = format[i + 1]
                    if (n == '%') {
                        sb.append('%')
                        i += 2
                        continue
                    }
                    if ((n == 'd' || n == 's' || n == 'f') && argIdx < args.size) {
                        sb.append(args[argIdx].asStr())
                        argIdx++
                        i += 2
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }

    private fun drawTextLike(
        node: XNode,
        canvas: Canvas,
        ctx: EvalContext,
        env: RenderEnv,
        ox: Float,
        oy: Float,
        s: Float,
        alpha: Float,
        content: String,
    ) {
        val size = numAttr(node, "size", ctx, 30f)
        if (size <= 0f) return
        val bold = node.attr("bold") == "true"
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = makeTypeface(bold)
        paint.textSize = size * s
        paint.color = parseColor(node.attr("color"))
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)

        val textWidth = paint.measureText(content)
        val anchorX = ox + numAttr(node, "x", ctx) * s
        val anchorY = oy + numAttr(node, "y", ctx) * s
        val drawX = when (node.attr("align")) {
            "center" -> anchorX - textWidth / 2f
            "right" -> anchorX - textWidth
            else -> anchorX
        }
        val fm = paint.fontMetrics
        val baseline = when (node.attr("alignV")) {
            "center" -> anchorY - (fm.ascent + fm.descent) / 2f
            "bottom" -> anchorY - fm.descent
            else -> anchorY - fm.ascent
        }
        canvas.drawText(content, drawX, baseline, paint)
        // 内容下缘统计口径（v2.0.3）：Text/DateTime 按「y + 字号实测
        // 行高（descent - ascent）」计下缘。v2.0.2 按 baseline+descent
        // 计，alignV=center 时只算半行高，跟随内容模式下头部高度少算
        // 约半行——云镜三小模块文字（y=888、size 15）下缘实际约 906、
        // 旧口径只算到约 898，文字被盒子下缘切掉一半（真机实证）。
        val lineHeight = fm.descent - fm.ascent
        trackContent(env, drawX, anchorY, drawX + textWidth, anchorY + lineHeight, lineHeight)
    }

    private fun makeTypeface(bold: Boolean): Typeface {
        val base = typeface
        return when {
            base != null && bold -> Typeface.create(base, Typeface.BOLD)
            base != null -> base
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }

    private fun parseColor(raw: String?): Int {
        if (raw.isNullOrBlank()) return Color.WHITE
        return try {
            Color.parseColor(raw.trim())
        } catch (t: Throwable) {
            Color.WHITE
        }
    }

    // ---------- Time ----------

    private fun drawTime(
        node: XNode,
        canvas: Canvas,
        ctx: EvalContext,
        env: RenderEnv,
        ox: Float,
        oy: Float,
        s: Float,
        alpha: Float,
    ) {
        val src = node.attr("src") ?: return
        // srcid 显式选图：单张数字图（充电界面那种只画一位的用法）
        if (node.attr("srcid") != null) {
            val path = resolveImagePath(node, ctx) ?: return
            val bmp = bitmap(path) ?: return
            drawBitmapAnchored(node, canvas, bmp, ox, oy, s, alpha, env, ctx)
            return
        }
        val hour24 = env.now.get(Calendar.HOUR_OF_DAY)
        val minute = env.now.get(Calendar.MINUTE)
        val digits = intArrayOf(hour24 / 10, hour24 % 10, minute / 10, minute % 10)
        val firstBmp = bitmap(variantPath(src, digits[0].toString())) ?: return
        // 步进与绘制宽都用位图自然宽，不取 XML 的 w：云镜 Time 写
        // w=170 但 time_*.png 实宽 140，引擎按位图原宽排位；按 170
        // 步进会把时钟撑宽，压住右侧固定位置的秒数图集（x≈663 起）
        // 与星期图（x≈661 起）——v2.0.2 真机实证，v2.0.3 改。
        // 按 140 步进：时钟右缘 = x 起点 + 560，与两者都不重叠。
        val cellW = firstBmp.width.toFloat()
        val cellH = if (hasNumAttr(node, "h")) numAttr(node, "h", ctx) else firstBmp.height.toFloat()
        if (cellW <= 0f || cellH <= 0f) return
        val dotBmp = bitmap(variantPath(src, "dot"))
        val dotW = if (dotBmp != null && dotBmp.height > 0) {
            dotBmp.width * (cellH / dotBmp.height)
        } else {
            0f
        }
        val totalW = cellW * 4f + dotW
        val anchorX = ox + numAttr(node, "x", ctx) * s
        val anchorY = oy + numAttr(node, "y", ctx) * s
        var curX = when (node.attr("align")) {
            "center" -> anchorX - totalW * s / 2f
            "right" -> anchorX - totalW * s
            else -> anchorX
        }
        val top = when (node.attr("alignV")) {
            "center" -> anchorY - cellH * s / 2f
            "bottom" -> anchorY - cellH * s
            else -> anchorY
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        val startX = curX
        digits.forEachIndexed { idx, d ->
            if (idx == 2 && dotBmp != null) {
                val dst = RectF(curX, top, curX + dotW * s, top + cellH * s)
                canvas.drawBitmap(dotBmp, null, dst, paint)
                curX += dotW * s
            }
            val bmp = bitmap(variantPath(src, d.toString())) ?: return@forEachIndexed
            val dst = RectF(curX, top, curX + cellW * s, top + cellH * s)
            canvas.drawBitmap(bmp, null, dst, paint)
            curX += cellW * s
        }
        trackContent(env, startX, top, curX, top + cellH * s, cellH * s)
    }

    /** 按 Image 的锚点规则画一张位图（Time 的 srcid 单图模式复用）。 */
    private fun drawBitmapAnchored(
        node: XNode,
        canvas: Canvas,
        bmp: Bitmap,
        ox: Float,
        oy: Float,
        s: Float,
        alpha: Float,
        env: RenderEnv,
        ctx: EvalContext,
    ) {
        val wE = if (hasNumAttr(node, "w")) numAttr(node, "w", ctx) else null
        val hE = if (hasNumAttr(node, "h")) numAttr(node, "h", ctx) else null
        var dw = bmp.width.toFloat()
        var dh = bmp.height.toFloat()
        if (wE != null && hE != null) {
            dw = wE; dh = hE
        } else if (wE != null) {
            dw = wE; dh = wE * bmp.height / bmp.width
        } else if (hE != null) {
            dh = hE; dw = hE * bmp.width / bmp.height
        }
        val rw = dw * s
        val rh = dh * s
        val anchorX = ox + numAttr(node, "x", ctx) * s
        val anchorY = oy + numAttr(node, "y", ctx) * s
        val left = when (node.attr("align")) {
            "center" -> anchorX - rw / 2f
            "right" -> anchorX - rw
            else -> anchorX
        }
        val top = when (node.attr("alignV")) {
            "center" -> anchorY - rh / 2f
            "bottom" -> anchorY - rh
            else -> anchorY
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(bmp, null, RectF(left, top, left + rw, top + rh), paint)
        trackContent(env, left, top, left + rw, top + rh, rh)
    }

    /**
     * 内容四缘统计（「跟随内容」高度与垂直/水平偏移钳制用）：整屏级
     * 背景（高 ≥ 屏高一半）与屏幕底部区域的元素（锁屏底部按钮等）
     * 不计入——簇顶取时钟簇的最高元素，而不是整幅背景的 0；
     * 左右缘同口径（全屏画布图被高度规则先排除）。
     */
    private fun trackContent(
        env: RenderEnv,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        height: Float,
    ) {
        if (height >= env.screenHeightPx * 0.5f) return
        if (top >= env.screenHeightPx * 0.75f) return
        if (bottom > contentBottomPx) contentBottomPx = bottom
        if (top < contentTopPx) contentTopPx = top
        if (left < contentLeftPx) contentLeftPx = left
        if (right > contentRightPx) contentRightPx = right
    }

    companion object {
        private val VISUAL_TAGS = setOf("Group", "Image", "Text", "DateTime", "Time")
        private val WEEKDAY_CN = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /**
         * DateTime 的 format 格式化：yyyy/MM/M/dd/d/HH/hh/mm/ss/EE 与
         * 中文等字符直通。hh 按 Java 惯例为 12 小时制（01-12）。
         */
        fun formatDate(format: String, cal: Calendar): String {
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val hour24 = cal.get(Calendar.HOUR_OF_DAY)
            val hour12raw = cal.get(Calendar.HOUR)
            val hour12 = if (hour12raw == 0) 12 else hour12raw
            val minute = cal.get(Calendar.MINUTE)
            val second = cal.get(Calendar.SECOND)
            // WEEKDAY_CN 是周一起表：Calendar 值（周日=1）换算成它的下标
            val cnDowIdx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            val sb = StringBuilder()
            var i = 0
            fun two(v: Int) = v.toString().padStart(2, '0')
            while (i < format.length) {
                when {
                    format.startsWith("yyyy", i) -> { sb.append(year); i += 4 }
                    format.startsWith("MM", i) -> { sb.append(two(month)); i += 2 }
                    format.startsWith("dd", i) -> { sb.append(two(day)); i += 2 }
                    format.startsWith("HH", i) -> { sb.append(two(hour24)); i += 2 }
                    format.startsWith("hh", i) -> { sb.append(two(hour12)); i += 2 }
                    format.startsWith("mm", i) -> { sb.append(two(minute)); i += 2 }
                    format.startsWith("ss", i) -> { sb.append(two(second)); i += 2 }
                    format.startsWith("EE", i) -> { sb.append(WEEKDAY_CN[cnDowIdx]); i += 2 }
                    format[i] == 'M' -> { sb.append(month); i++ }
                    format[i] == 'd' -> { sb.append(day); i++ }
                    format[i] == 'H' -> { sb.append(hour24); i++ }
                    format[i] == 'h' -> { sb.append(hour12); i++ }
                    format[i] == 'm' -> { sb.append(minute); i++ }
                    format[i] == 's' -> { sb.append(second); i++ }
                    format[i] == 'E' -> { sb.append(WEEKDAY_CN[cnDowIdx]); i++ }
                    format[i] == 'a' -> { sb.append(if (hour24 < 12) "上午" else "下午"); i++ }
                    else -> { sb.append(format[i]); i++ }
                }
            }
            return sb.toString()
        }
    }
}
