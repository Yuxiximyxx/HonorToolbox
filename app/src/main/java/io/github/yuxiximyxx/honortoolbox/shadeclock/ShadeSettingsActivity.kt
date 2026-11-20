package io.github.yuxiximyxx.honortoolbox.shadeclock

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 设置页（v2.0.3 起）：顶部样式（原系统头部 / 锁屏主题样式）、主题
 * 管理（导入/切换/删除）、区域高度三档、字号滑杆（原头部模式）；
 * v2.0.7 加「垂直偏移」滑杆（-400dp～+160dp，v2.0.24 由
 * -120dp～+40dp 放宽，覆盖层整体上下移，
 * 经 stamp 第 7 行传 SystemUI 侧）；v2.0.8 加「隐藏运营商文字」
 * 开关（默认开，stamp 第 8 行，锁屏/控制中心/通知中心三处统管）；
 * v2.0.12 加「重启系统界面」按钮（写 restart.txt 请求标记，
 * SystemUI 侧看守读到后结束自己进程被系统拉起，等同强停、
 * 不依赖 root，省得每次验版手动强停）。
 * 覆盖层相关设置经 stamp.txt（公共 Download 目录）传给 SystemUI
 * 侧，切换免强停；字号仍走 SharedPreferences「settings」，由 hook
 * 侧在 SystemUI 进程启动时经 XSharedPreferences 读取。
 * 样式照 MagicPods v2.11 的 MIUIX 写法。
 * v2.0.25：页面组织改 MIUI 式二级页面（v2.0.24 的自研折叠
 * 分组被小乐以「不 miuix、不美观」否决；miuix 0.8.8 本身也
 * 没有官方折叠/可展开分节组件）——主页只留版本说明卡、一列
 * SuperArrow 入口（主题管理/位置/显示与开关/原系统头部/说
 * 明）与常显的「系统界面」（重启按钮），每组点进独立子页
 * （SmallTopAppBar+返回、Card 分组、组件全用 miuix 词汇）。
 * 设置项、存储键、戳记格式一律未动；v2.0.24 的 ui_section_*
 * 展开状态键弃用、打开时清掉。
 */
class ShadeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = 0x00000000 }
        // 已有设置文件时先确保 hook 侧（SystemUI 进程）能读到
        makePrefsReadable(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val view = LocalView.current
            SideEffect {
                runCatching {
                    val act = view.context as? Activity
                    val w = act?.window ?: return@runCatching
                    val ctl = WindowCompat.getInsetsController(w, view)
                    ctl.isAppearanceLightStatusBars = !dark
                    ctl.isAppearanceLightNavigationBars = !dark
                }
            }
            val controller = remember(dark) {
                ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
            }
            MiuixTheme(controller = controller) { SettingsScreen() }
        }
    }
}

private const val PREFS_NAME = "settings"
private const val KEY_CLOCK_SIZE = "clock_size_dp"
private const val KEY_DATE_SIZE = "date_size_dp"
private const val CLOCK_DP_MIN = 36
private const val CLOCK_DP_MAX = 68
private const val DATE_DP_MIN = 12
private const val DATE_DP_MAX = 24

// 设置页页面状态（v2.0.25 二级页面）：朴素字符串状态切换，
// 不引入导航库；系统返回键在子页时先回主页（见 SettingsScreen
// 里的 BackHandler）。
private const val PAGE_HOME = "home"
private const val PAGE_THEMES = "themes"
private const val PAGE_POSITION = "position"
private const val PAGE_DISPLAY = "display"
private const val PAGE_CARD = "card"
private const val PAGE_FONTSIZE = "fontsize"
private const val PAGE_ABOUT = "about"

/**
 * 让 SystemUI 进程能读到本应用的 settings.xml：XSharedPreferences 是
 * 跨进程直读文件，需要 shared_prefs 目录可穿越、文件可读。尽力而为，
 * 失败时 hook 侧会回退默认值并在日志里写明。
 */
private fun makePrefsReadable(context: Context) {
    try {
        val dir = File(context.filesDir.parentFile, "shared_prefs")
        dir.setExecutable(true, false)
        dir.setReadable(true, false)
        File(dir, "$PREFS_NAME.xml").setReadable(true, false)
    } catch (t: Throwable) {
        // 忽略：hook 侧有默认值兜底与诊断日志
    }
}

/** 子页返回按钮（照 miuix 官方示例的 BackNavigationIcon 写法）。 */
@Composable
private fun BackNavigationIcon(onClick: () -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    IconButton(onClick = onClick) {
        Icon(
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            imageVector = MiuixIcons.Back,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 当前页（v2.0.25 二级页面）：主页是入口列，子页承载各组
    // 设置；状态只在本次打开内保留，不落盘。
    var page by remember { mutableStateOf(PAGE_HOME) }
    BackHandler(enabled = page != PAGE_HOME) { page = PAGE_HOME }

    // v2.0.24 折叠分组的展开状态键 ui_section_* 自 v2.0.25
    // 起弃用，打开设置页时顺手清掉（纯呈现偏好、无数据）。
    remember {
        val e = prefs.edit()
        for (id in listOf("style", "position", "themes", "height", "fontsize", "about")) {
            e.remove("ui_section_$id")
        }
        e.apply()
    }

    var topStyleTheme by remember {
        mutableStateOf(
            prefs.getString(ThemeStore.KEY_TOP_STYLE, StampStore.TOP_STYLE_THEME) !=
                StampStore.TOP_STYLE_ORIGINAL
        )
    }
    var heightMode by remember {
        mutableIntStateOf(prefs.getInt(ThemeStore.KEY_HEIGHT_MODE, StampStore.HEIGHT_FOLLOW))
    }
    var showBackground by remember {
        mutableStateOf(prefs.getBoolean(ThemeStore.KEY_SHOW_BACKGROUND, false))
    }
    var showWidgets by remember {
        mutableStateOf(prefs.getBoolean(ThemeStore.KEY_SHOW_WIDGETS, false))
    }
    var hideCarrier by remember {
        mutableStateOf(prefs.getBoolean(ThemeStore.KEY_HIDE_CARRIER, true))
    }
    // 头部卡片（v2.0.32）：全局一份——模式开关默认开、默认
    // 选中天气小卡；选卡列表在「头部卡片」二级页按应用分组
    // 折叠展示，进页后在 IO 线程枚举一次全机 provider。
    var cardMode by remember {
        mutableStateOf(prefs.getBoolean(ThemeStore.KEY_CARD_MODE, true))
    }
    var cardProvider by remember {
        mutableStateOf(
            prefs.getString(ThemeStore.KEY_CARD_PROVIDER, StampStore.DEFAULT_CARD_PROVIDER)
                ?: StampStore.DEFAULT_CARD_PROVIDER
        )
    }
    var cardGroups by remember { mutableStateOf<List<CardAppGroup>>(emptyList()) }
    // 选卡搜索（v2.0.33）：按应用名/卡片名过滤，与系统
    // 卡片目录同款交互；非空时命中分组自动展开。
    var cardQuery by remember { mutableStateOf("") }
    var expandedCardPkg by remember {
        mutableStateOf<String?>(
            (prefs.getString(ThemeStore.KEY_CARD_PROVIDER, StampStore.DEFAULT_CARD_PROVIDER)
                ?: StampStore.DEFAULT_CARD_PROVIDER).substringBefore('/'),
        )
    }
    var restartStatus by remember { mutableStateOf("") }
    var themes by remember { mutableStateOf(ThemeStore.loadThemes(prefs)) }
    var selectedThemeId by remember {
        mutableStateOf(prefs.getString(ThemeStore.KEY_SELECTED_THEME, "") ?: "")
    }
    // 垂直偏移按主题记（v2.0.21）：滑杆永远显示/修改「当前
    // 选中主题」的那一份；先跑存量迁移（旧全局值搬给当前
    // 主题），再读当前主题的值作初值。
    var offsetDp by remember {
        ThemeStore.migrateLegacyOffset(prefs)
        mutableIntStateOf(
            ThemeStore.offsetForTheme(
                prefs,
                prefs.getString(ThemeStore.KEY_SELECTED_THEME, "") ?: "",
            )
        )
    }
    // 水平偏移同样按主题记（v2.0.23）：与垂直并列的一张映射，
    // 滑杆同样只编当前选中主题的那一份。
    var offsetXDp by remember {
        mutableIntStateOf(
            ThemeStore.offsetXForTheme(
                prefs,
                prefs.getString(ThemeStore.KEY_SELECTED_THEME, "") ?: "",
            )
        )
    }
    // 星期编号模式同样按主题记（v2.0.23）：默认周日起算，
    // 开关只编当前选中主题的那一份。
    var weekdayMondayStart by remember {
        mutableStateOf(
            ThemeStore.weekdayMondayStartForTheme(
                prefs,
                prefs.getString(ThemeStore.KEY_SELECTED_THEME, "") ?: "",
            )
        )
    }
    var importStatus by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }

    var clockSize by remember {
        mutableIntStateOf(
            prefs.getInt(KEY_CLOCK_SIZE, 54).coerceIn(CLOCK_DP_MIN, CLOCK_DP_MAX)
        )
    }
    var dateSize by remember {
        mutableIntStateOf(
            prefs.getInt(KEY_DATE_SIZE, 16).coerceIn(DATE_DP_MIN, DATE_DP_MAX)
        )
    }

    fun syncStamp() {
        scope.launch(Dispatchers.IO) {
            // v2.0.20：写入带回读校验，再失败要让用户看见，
            // 不再静默丢弃（v2.0.19 选了主题不跟随就是静默
            // 失败吃掉的）。
            val ok = ThemeStore.writeStamp(context, prefs)
            if (!ok) {
                withContext(Dispatchers.Main) {
                    importStatus = "设置同步到系统界面失败，请再操作一次"
                }
            }
        }
    }

    // 存量主题迁移（v2.0.18）：旧版解压在公共目录里的散图会被
    // 图库显示出来；设置页每次打开时在 IO 线程幂等跑一遍——把
    // 散图打包回主题包本体、删散图与其 MediaStore 条目。有主题
    // 被迁移时重写一次 stamp，让 SystemUI 侧随之改从包本体
    // 重载（迁移前它经旧目录兼容读法照常渲染，不受影响）。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val migrated = withContext(Dispatchers.IO) {
            ThemeStore.migrateLegacyThemes(context, prefs)
        }
        if (migrated > 0) {
            themes = ThemeStore.loadThemes(prefs)
            importStatus = "已把 $migrated 个旧主题打包成单文件（图库里的散图已清）"
        }
        // 无论有没有迁移都重写一次戳记（v2.0.19）：重装后
        // stamp.txt 可能已不归本安装所有、写入会落到副本上，
        // 靠 SystemUI 侧的多候选采纳跟上；每次打开设置页都
        // 重申当前选择，保证自愈路径不需要用户多点任何东西。
        syncStamp()
    }

    // 头部卡片选卡列表（v2.0.32）：全机 provider 有数百个，
    // 枚举+读 label 放 IO 线程，进设置页时备好一次即可。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (cardGroups.isEmpty()) {
            cardGroups = withContext(Dispatchers.IO) { loadCardGroups(context) }
        }
    }

    // 选卡目录 dump（v2.0.33）：进「头部卡片」页时在 IO 线程
    // 生成一次 /sdcard/Download/shadeclock_carddump.txt，
    // 记录设置进程视角下全量 provider 的可读字段与 receiver
    // metaData 键名——定案两件事：设置侧枚举被调用方过滤的
    // 范围（.32 里天气/日历/时钟/智慧空间四组整组缺失）、
    // 系统「服务卡片」目录的精选标记。本进程只跑一次。
    androidx.compose.runtime.LaunchedEffect(page) {
        if (page == PAGE_CARD) {
            withContext(Dispatchers.IO) { CardDump.writeOnce(context) }
        }
    }

    fun saveTopStyle(theme: Boolean) {
        topStyleTheme = theme
        prefs.edit()
            .putString(
                ThemeStore.KEY_TOP_STYLE,
                if (theme) StampStore.TOP_STYLE_THEME else StampStore.TOP_STYLE_ORIGINAL,
            )
            .apply()
        syncStamp()
    }

    fun saveHeightMode(mode: Int) {
        heightMode = mode
        prefs.edit().putInt(ThemeStore.KEY_HEIGHT_MODE, mode).apply()
        syncStamp()
    }

    fun saveShowBackground(value: Boolean) {
        showBackground = value
        prefs.edit().putBoolean(ThemeStore.KEY_SHOW_BACKGROUND, value).apply()
        syncStamp()
    }

    fun saveShowWidgets(value: Boolean) {
        showWidgets = value
        prefs.edit().putBoolean(ThemeStore.KEY_SHOW_WIDGETS, value).apply()
        syncStamp()
    }

    fun saveHideCarrier(value: Boolean) {
        hideCarrier = value
        prefs.edit().putBoolean(ThemeStore.KEY_HIDE_CARRIER, value).apply()
        syncStamp()
    }

    fun saveCardMode(value: Boolean) {
        cardMode = value
        prefs.edit().putBoolean(ThemeStore.KEY_CARD_MODE, value).apply()
        syncStamp()
    }

    fun saveCardProvider(value: String) {
        if (value == cardProvider) return
        cardProvider = value
        prefs.edit().putString(ThemeStore.KEY_CARD_PROVIDER, value).apply()
        syncStamp()
    }

    fun saveOffsetDp(value: Int) {
        if (value == offsetDp) return
        offsetDp = value
        // 只写当前选中主题的那一份（v2.0.21），别的主题不受影响
        ThemeStore.setOffsetForTheme(prefs, selectedThemeId, value)
        syncStamp()
    }

    fun saveOffsetXDp(value: Int) {
        if (value == offsetXDp) return
        offsetXDp = value
        ThemeStore.setOffsetXForTheme(prefs, selectedThemeId, value)
        syncStamp()
    }

    fun saveWeekdayMondayStart(value: Boolean) {
        if (value == weekdayMondayStart) return
        weekdayMondayStart = value
        ThemeStore.setWeekdayMondayStartForTheme(prefs, selectedThemeId, value)
        syncStamp()
    }

    fun selectTheme(id: String) {
        selectedThemeId = id
        prefs.edit().putString(ThemeStore.KEY_SELECTED_THEME, id).apply()
        // 滑杆跟随主题：切过去就显示那个主题自己记着的偏移
        offsetDp = ThemeStore.offsetForTheme(prefs, id)
        offsetXDp = ThemeStore.offsetXForTheme(prefs, id)
        weekdayMondayStart = ThemeStore.weekdayMondayStartForTheme(prefs, id)
        syncStamp()
    }

    fun deleteTheme(id: String) {
        scope.launch(Dispatchers.IO) {
            ThemeStore.deleteTheme(context, prefs, id)
            withContext(Dispatchers.Main) {
                themes = ThemeStore.loadThemes(prefs)
                if (selectedThemeId == id) {
                    selectedThemeId = ""
                    offsetDp = ThemeStore.offsetForTheme(prefs, "")
                    offsetXDp = ThemeStore.offsetXForTheme(prefs, "")
                    weekdayMondayStart = ThemeStore.weekdayMondayStartForTheme(prefs, "")
                }
                importStatus = "已删除主题"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importStatus = "正在导入…"
        scope.launch(Dispatchers.IO) {
            val result = ThemeImporter.importTheme(context, uri)
            withContext(Dispatchers.Main) {
                importing = false
                when (result) {
                    is ThemeImporter.Result.Ok -> {
                        val newThemes = ThemeStore.loadThemes(prefs) + result.entry
                        ThemeStore.saveThemes(prefs, newThemes)
                        themes = newThemes
                        selectedThemeId = result.entry.id
                        prefs.edit()
                            .putString(ThemeStore.KEY_SELECTED_THEME, result.entry.id)
                            .apply()
                        // 新主题的偏移从它自己那份起算（没调过是 0）
                        offsetDp = ThemeStore.offsetForTheme(prefs, result.entry.id)
                        offsetXDp = ThemeStore.offsetXForTheme(prefs, result.entry.id)
                        weekdayMondayStart =
                            ThemeStore.weekdayMondayStartForTheme(prefs, result.entry.id)
                        importStatus = "已导入并选中「${result.entry.name}」"
                        syncStamp()
                    }
                    is ThemeImporter.Result.Err -> {
                        importStatus = result.message
                    }
                }
            }
        }
    }

    /**
     * 「重启系统界面」（v2.0.12）：把请求时刻写进 restart.txt，
     * SystemUI 侧看守在 1 秒内读到后结束自己进程、被系统拉起，
     * 模块随之重新加载——等同强停系统界面，不依赖 root。点了
     * 立即给反馈、不弹确认框：动作只影响系统界面本身、一两秒
     * 自行恢复，符合少打扰口径。
     */
    fun requestRestart() {
        restartStatus = "已发送重启请求，系统界面马上会重启（通知栏会闪断一两秒）"
        scope.launch(Dispatchers.IO) {
            val ok = ThemeStore.writeRestartRequest(context)
            if (!ok) {
                withContext(Dispatchers.Main) {
                    restartStatus = "发送失败，请再点一次"
                }
            }
        }
    }

    fun saveClockSize(value: Int) {
        if (value == clockSize) return
        clockSize = value
        prefs.edit().putInt(KEY_CLOCK_SIZE, value).apply()
        makePrefsReadable(context)
    }

    fun saveDateSize(value: Int) {
        if (value == dateSize) return
        dateSize = value
        prefs.edit().putInt(KEY_DATE_SIZE, value).apply()
        makePrefsReadable(context)
    }

    val pageTitle = when (page) {
        PAGE_THEMES -> "主题管理"
        PAGE_POSITION -> "位置"
        PAGE_DISPLAY -> "显示与开关"
        PAGE_CARD -> "头部卡片"
        PAGE_FONTSIZE -> "原系统头部"
        PAGE_ABOUT -> "说明"
        else -> "通知中心"
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = pageTitle,
                color = Color.Transparent,
                navigationIcon = {
                    if (page != PAGE_HOME) {
                        BackNavigationIcon { page = PAGE_HOME }
                    } else {
                        BackNavigationIcon {
                            (context as? Activity)?.finish()
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // padding 必须排在 verticalScroll 前面（v2.0.33
                // 修顶部重叠）：反过来写时顶栏 inset 是滚动内容
                // 的一部分，一滚动就被卷走，列表行会滑到状态
                // 栏/顶栏底下与标题、时间重叠（.32 选卡页截图
                // 实证）；先 padding 则视口本身被 inset 框住，
                // 内容永远不进顶栏区域。
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                when (page) {
                    PAGE_THEMES -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(vertical = 4.dp),
                        ) {
                            Column {
                                SuperArrow(
                                    title = "导入主题文件",
                                    summary = if (importing) "正在导入…" else "选择 .hnt / .hwt 主题包",
                                    onClick = {
                                        importLauncher.launch(arrayOf("*/*"))
                                    },
                                    enabled = !importing,
                                )
                                if (importStatus.isNotEmpty()) {
                                    Text(
                                        importStatus,
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                }
                                if (themes.isEmpty()) {
                                    Text(
                                        "还没有导入主题。导入后点主题名即可切换。",
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                for (theme in themes) {
                                    val selected = theme.id == selectedThemeId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectTheme(theme.id) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                theme.name,
                                                fontSize = 15.sp,
                                                color = MiuixTheme.colorScheme.onSurface,
                                            )
                                            if (selected) {
                                                Text(
                                                    "当前使用",
                                                    fontSize = 12.sp,
                                                    color = MiuixTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        Text(
                                            "删除",
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier
                                                .clickable { deleteTheme(theme.id) }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    PAGE_POSITION -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Column {
                                SizeSliderRow(
                                    title = "垂直偏移",
                                    value = offsetDp,
                                    min = StampStore.OFFSET_DP_MIN,
                                    max = StampStore.OFFSET_DP_MAX,
                                    onValueChange = { saveOffsetDp(it) },
                                )
                                SizeSliderRow(
                                    title = "左右偏移",
                                    value = offsetXDp,
                                    min = StampStore.OFFSET_X_DP_MIN,
                                    max = StampStore.OFFSET_X_DP_MAX,
                                    onValueChange = { saveOffsetXDp(it) },
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                SuperSwitch(
                                    title = "星期编号按周一起算",
                                    summary = "关（默认）：星期图按周日=1 编号（AAA 这类）；开：按周一=1 编号（云镜这类）。主题文件不声明编号口径、星期差一天时切它；同样按主题各记各的。",
                                    checked = weekdayMondayStart,
                                    onCheckedChange = { saveWeekdayMondayStart(it) },
                                )
                                Text(
                                    "垂直：负数上移、正数下移；左右：负数左移、正数右移。每个主题单独记自己的偏移：滑杆调的是当前选中的主题，切到别的主题显示的是那个主题的值。主题顶部的留白是给锁屏天空的，在通知中心里显空时往上调；偏移最多把时钟簇推到贴边，不会移出画面。",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }

                    PAGE_DISPLAY -> {
                        SmallTitle(
                            text = "顶部样式",
                            insideMargin = PaddingValues(start = 4.dp),
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            SuperSwitch(
                                title = "锁屏主题样式",
                                summary = "开：用导入的锁屏主题渲染顶部；关：系统原头部",
                                checked = topStyleTheme,
                                onCheckedChange = { saveTopStyle(it) },
                            )
                            SuperSwitch(
                                title = "主题背景图",
                                summary = "开：连主题的整幅背景图一起画；关：只画时钟等元素，透出通知栏原背景",
                                checked = showBackground,
                                onCheckedChange = { saveShowBackground(it) },
                            )
                            SuperSwitch(
                                title = "小模块（温度/风向/电量）",
                                summary = "开：连温度、风向、电量小模块一起画；关：只显示时间样式（默认关）",
                                checked = showWidgets,
                                onCheckedChange = { saveShowWidgets(it) },
                            )
                            SuperSwitch(
                                title = "隐藏运营商文字",
                                summary = "开：锁屏、控制中心、通知中心左上角的运营商文字（中国联通等）不显示（默认开）",
                                checked = hideCarrier,
                                onCheckedChange = { saveHideCarrier(it) },
                            )
                        }
                        SmallTitle(
                            text = "区域高度",
                            insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(vertical = 4.dp),
                        ) {
                            Column {
                                HeightModeRow("跟随内容", "高度取所画元素的最下缘", heightMode == 0) {
                                    saveHeightMode(0)
                                }
                                HeightModeRow("屏高 1/3", "覆盖层固定占屏幕高度三分之一", heightMode == 1) {
                                    saveHeightMode(1)
                                }
                                HeightModeRow("屏高 1/2", "覆盖层固定占屏幕高度二分之一", heightMode == 2) {
                                    saveHeightMode(2)
                                }
                            }
                        }
                    }

                    PAGE_CARD -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Column {
                                SuperSwitch(
                                    title = "头部使用卡片",
                                    summary = "开：通知中心头部显示下面选中的应用卡片；关：显示锁屏主题时钟（默认开）",
                                    checked = cardMode,
                                    onCheckedChange = { saveCardMode(it) },
                                )
                                Text(
                                    "放进通知中心的是新建的卡片实例、不是桌面那张的克隆：天气、日历这类绑上就出内容；文字、联系人这类带配置的卡本版只绑不配、显示默认内容。改完开关或选完卡，下拉一次通知中心即生效（不行就用「重启系统界面」）。高度特别大的卡会把通知挤下去很多，选卡时留意尺寸。",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                        SmallTitle(
                            text = "选择卡片",
                            insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                        )
                        // 搜索（v2.0.33）：按应用名/卡片名过滤，
                        // 与系统卡片目录同款交互；搜索时命中
                        // 分组自动展开、只列命中的卡（应用名
                        // 本身命中则列全组）。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextField(
                                value = cardQuery,
                                onValueChange = { cardQuery = it },
                                label = "搜索卡片",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            if (cardQuery.isNotEmpty()) {
                                Text(
                                    "清除",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { cardQuery = "" }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(vertical = 4.dp),
                        ) {
                            Column {
                                val query = cardQuery.trim()
                                val visibleGroups = if (query.isEmpty()) {
                                    cardGroups
                                } else {
                                    cardGroups.filter { g ->
                                        g.appLabel.contains(query, ignoreCase = true) ||
                                            g.entries.any {
                                                it.label.contains(query, ignoreCase = true)
                                            }
                                    }
                                }
                                if (cardGroups.isEmpty()) {
                                    Text(
                                        "正在读取本机卡片…",
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                } else if (visibleGroups.isEmpty()) {
                                    Text(
                                        "没有匹配「$query」的卡片",
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                for (group in visibleGroups) {
                                    val searching = query.isNotEmpty()
                                    val expanded = searching ||
                                        expandedCardPkg == group.packageName
                                    val shownEntries = if (searching &&
                                        !group.appLabel.contains(query, ignoreCase = true)
                                    ) {
                                        group.entries.filter {
                                            it.label.contains(query, ignoreCase = true)
                                        }
                                    } else {
                                        group.entries
                                    }
                                    val groupHasSelected =
                                        group.entries.any { it.component == cardProvider }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                expandedCardPkg =
                                                    if (expandedCardPkg == group.packageName) {
                                                        null
                                                    } else {
                                                        group.packageName
                                                    }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // 应用图标（v2.0.33）：向系统
                                        // 卡片目录的形态靠（图标+应用
                                        // 名+卡数）；图标取不到就只
                                        // 显示文字，不留空位。
                                        group.icon?.let { icon ->
                                            Image(
                                                bitmap = icon,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(9.dp)),
                                            )
                                            Spacer(modifier = Modifier.size(12.dp))
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                group.appLabel,
                                                fontSize = 15.sp,
                                                color = MiuixTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                "${group.entries.size} 张卡片",
                                                fontSize = 12.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                        }
                                        if (groupHasSelected) {
                                            Text(
                                                "已选其一",
                                                fontSize = 12.sp,
                                                color = MiuixTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    if (expanded) {
                                        for (entry in shownEntries) {
                                            val selected = entry.component == cardProvider
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        saveCardProvider(entry.component)
                                                    }
                                                    .padding(
                                                        start = 32.dp, end = 16.dp,
                                                        top = 8.dp, bottom = 8.dp,
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        entry.label.ifEmpty { entry.component },
                                                        fontSize = 14.sp,
                                                        color = MiuixTheme.colorScheme.onSurface,
                                                    )
                                                    Text(
                                                        entry.sizeText,
                                                        fontSize = 12.sp,
                                                        color = MiuixTheme.colorScheme
                                                            .onSurfaceVariantSummary,
                                                    )
                                                }
                                                if (selected) {
                                                    Text(
                                                        "当前使用",
                                                        fontSize = 12.sp,
                                                        color = MiuixTheme.colorScheme.primary,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    PAGE_FONTSIZE -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Column {
                                SizeSliderRow(
                                    title = "时钟字号",
                                    value = clockSize,
                                    min = CLOCK_DP_MIN,
                                    max = CLOCK_DP_MAX,
                                    onValueChange = { saveClockSize(it) },
                                )
                                SizeSliderRow(
                                    title = "日期字号",
                                    value = dateSize,
                                    min = DATE_DP_MIN,
                                    max = DATE_DP_MAX,
                                    onValueChange = { saveDateSize(it) },
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                Text(
                                    "字号只对原系统头部模式有效，改完回主页点「重启系统界面」即可生效。",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }

                    PAGE_ABOUT -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Column {
                                Text(
                                    "切换主题、改区域高度、调垂直/左右偏移、开关锁屏主题样式、主题背景图与隐藏运营商文字：下次下拉通知栏即生效（约 1 秒内自动刷新），不用强停系统界面。",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "字号滑杆只对原系统头部模式有效，改完点主页「重启系统界面」即可生效（等同强停，不用重启手机）。",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Text(
                                    "温度/风向小模块读系统天气服务，与锁屏同源（「小模块」开关默认关，打开后才画）；主题里的入场动画只按终态静态呈现，秒数逐秒切换。",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Text(
                                    "关掉工具箱首页的「通知中心主题」开关，或在 LSPosed 里停用荣耀工具箱，再强停一次系统界面，通知中心即恢复原样。诊断日志在 /sdcard/Download/shadeclock_probe.log，主题资产在 /sdcard/Download/HonorToolbox/shadeclock/themes/。",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }

                    else -> {
                        // 主页：版本说明卡 + SuperArrow 入口列 +
                        // 常显的系统界面（重启按钮，动作行不藏进
                        // 子页）。
                        SmallTitle(
                            text = "主题时钟",
                            insideMargin = PaddingValues(start = 4.dp),
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Column {
                                Text(
                                    "在通知栏顶部盖一层自绘覆盖层，按荣耀锁屏主题的样式渲染（数字图集时钟、日期、电量、天气小模块等），主题可导入、可切换。",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "覆盖层构建或渲染出任何问题都会自动卸下、恢复系统原头部，不会让系统界面崩溃。",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        SmallTitle(
                            text = "设置",
                            insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(vertical = 4.dp),
                        ) {
                            Column {
                                SuperArrow(
                                    title = "主题管理",
                                    summary = themes.firstOrNull { it.id == selectedThemeId }
                                        ?.let { "当前使用：${it.name}" }
                                        ?: "导入并选择锁屏主题",
                                    onClick = { page = PAGE_THEMES },
                                )
                                SuperArrow(
                                    title = "位置",
                                    summary = "垂直/左右偏移、星期编号（按主题记）",
                                    onClick = { page = PAGE_POSITION },
                                )
                                SuperArrow(
                                    title = "显示与开关",
                                    summary = "顶部样式、背景图、小模块、运营商、区域高度",
                                    onClick = { page = PAGE_DISPLAY },
                                )
                                SuperArrow(
                                    title = "头部卡片",
                                    summary = if (cardMode) {
                                        "已开启：头部显示应用卡片（点此选卡）"
                                    } else {
                                        "已关闭：头部显示主题时钟（点此开启/选卡）"
                                    },
                                    onClick = { page = PAGE_CARD },
                                )
                                SuperArrow(
                                    title = "原系统头部",
                                    summary = "原头部模式的时钟/日期字号",
                                    onClick = { page = PAGE_FONTSIZE },
                                )
                                SuperArrow(
                                    title = "说明",
                                    summary = "使用说明与注意事项",
                                    onClick = { page = PAGE_ABOUT },
                                )
                            }
                        }
                        SmallTitle(
                            text = "系统界面",
                            insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(vertical = 4.dp),
                        ) {
                            Column {
                                SuperArrow(
                                    title = "重启系统界面",
                                    summary = "等同强停系统界面：字号等需要重载的改动点它即可生效，不用去设置里强停",
                                    onClick = { requestRestart() },
                                )
                                if (restartStatus.isNotEmpty()) {
                                    Text(
                                        restartStatus,
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 区域高度三档的选择行。 */
@Composable
private fun HeightModeRow(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                summary,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (selected) {
            Text(
                "当前",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** 字号滑杆行：标题 + 当前值（dp）+ Slider，拖动按整数 dp 步进、变化即落盘。 */
@Composable
private fun SizeSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${value}dp",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                onValueChange(it.roundToInt().coerceIn(min, max))
            },
            valueRange = min.toFloat()..max.toFloat(),
            // 小跨度逐 dp 设 steps（吸附感好）；大跨度（如垂直
            // 偏移 v2.0.24 放宽到 560dp）逐 dp 设 steps 会让每
            // 步不足一个像素、拖动发涩，改连续滑动，落值仍按
            // 整数 dp 取整。
            steps = if (max - min <= 100) {
                (max - min - 1).coerceAtLeast(0)
            } else {
                0
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- 头部卡片选卡（v2.0.32）----

/** 一个 provider 条目：组件 flatten 串、卡片名、尺寸文案。 */
private data class CardProviderEntry(
    val component: String,
    val label: String,
    val sizeText: String,
)

/** 按来源应用分组的卡片列表（icon 为应用图标位图，取不到为 null）。 */
private data class CardAppGroup(
    val packageName: String,
    val appLabel: String,
    val icon: ImageBitmap?,
    val entries: List<CardProviderEntry>,
)

/**
 * 探针实测有服务卡片的来源应用（天气/日历/时钟/联系人/
 * 图库/钱包/智慧空间/健康使用平板 + 文字/语录卡片的来源
 * 启动器 + 笔记），选卡列表里按探针包名钉在最前；其余
 * 应用按名称排后面。
 */
private val CARD_KNOWN_PACKAGES = listOf(
    "com.hihonor.android.totemweather",
    "com.hihonor.calendar",
    "com.hihonor.deskclock",
    "com.hihonor.contacts",
    "com.hihonor.photos",
    "com.hihonor.wallet",
    "com.hihonor.magichome",
    "com.hihonor.parentcontrol",
    "com.hihonor.android.launcher",
    "com.hihonor.notepad",
)

/**
 * 选卡页尺寸文案（v2.0.33 改口径）：provider 原始 minW×minH
 * 千奇百怪（0×0、3584×805、3150×140 都有），直接换算 dp
 * 标出来对用户毫无意义。优先级：声明了格子数就显示
 * 「W×H 格」；否则显示这张卡按 CardHost 现行布局规则放进
 * 头部后的实际占用——原生尺寸取 minW×minH（任一为 0 走
 * CardHost 同款兜底 700×350px，与 [CardHost] 的
 * FALLBACK_NATIVE_W/H 一致）、比头部可用宽更宽时等比
 * 缩小（scale≤1、永不放大），换算 dp 后标注「实际占用」。
 * 可用宽在设置侧以屏幕宽 px 近似（通知面板全宽、卡片槽
 * 与头部同宽）。
 */
private fun cardSizeText(
    info: AppWidgetProviderInfo,
    density: Float,
    availWidthPx: Int,
): String {
    if (info.targetCellWidth > 0 && info.targetCellHeight > 0) {
        return "${info.targetCellWidth}×${info.targetCellHeight} 格"
    }
    val nativeW = if (info.minWidth > 0) info.minWidth else 700
    val nativeH = if (info.minHeight > 0) info.minHeight else 350
    val scale = if (availWidthPx > 0) {
        minOf(1f, availWidthPx.toFloat() / nativeW)
    } else {
        1f
    }
    val wDp = (nativeW * scale / density).roundToInt()
    val hDp = (nativeH * scale / density).roundToInt()
    return "实际占用约 $wDp×$hDp dp"
}

/** 应用图标 Drawable -> 位图（选卡页分组行左侧图标用）。 */
private fun drawableToBitmap(drawable: Drawable, sizePx: Int): ImageBitmap? {
    return try {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        bmp.asImageBitmap()
    } catch (t: Throwable) {
        null
    }
}

/** receiver 的 meta-data（服务卡片身份标记与规范尺寸都挂在这里）。 */
private fun receiverMetaData(
    pm: PackageManager,
    component: ComponentName,
): Bundle? = try {
    pm.getReceiverInfo(component, PackageManager.GET_META_DATA).metaData
} catch (t: Throwable) {
    null
}

/**
 * 荣耀服务卡片身份标记（v2.0.37 定案、v2.0.38 计数校准）：
 * receiver 的 meta-data 带 com.hihonor.android.launcher.
 * card_* 任一键的才是服务卡片。证据：.36 cardxml 全量
 * 比对——身份标记不在 provider 的 XML 里（523 份 XML 全
 * 是标准 android 命名空间、零自定义属性），在 meta-data
 * 这一层。设置进程视角经 carddump 互证同样能看到全部，
 * 本进程过滤不会漏。
 *
 * v2.0.38 独立复核对账（cardxml 与 carddump 两份文件
 * 分别统计、数字一致）：card_* 命中 **112** 张（15 个包；
 * card_size 103、card_category 97、仅 card_* 无尺寸档位
 * 4 张）。另一路统计的 122 = 112 + 9 个 com.vivo.
 * widget_type + 1 个 com.hihonor.auto.widget_type——那是
 * 跨厂商宽口径（vivo/汽车渠道的标记），不是荣耀服务卡，
 * 不采纳。校准项：另有 3 张天气卡只带 launcher 的
 * widgetType / widget_type 键、没有 card_* 键
 * （totemweather 的 HonorCardProvider、
 * HonorImmersionProvider、CarMinCardWeatherProvider，
 * 都是卡片形态 provider）；widget_type 持有者 90 张里
 * 87 张本来就带 card_*，该标记与服务卡高度重合——本版
 * 把它作次级接受条件，最终目录 **115** 张。com.hihonor.
 * widget.type（107 张、仅 34 张与 card_* 重叠）与
 * android.appwidget.honor.provider（31 张、仅 4 张重叠）
 * 都是宽标记，不作判据。这 3 张的 widget_type 取值由
 * .38 的 meta-values dump 回传后再核对。
 *
 * 只留真服务卡的理由有二：一是身份标记（本函数）；二是
 * 材质——小乐点明真服务卡的材质是与系统其他地方一致的
 * 液态玻璃，通用小部件大小材质都不合规范、观感不对。
 */
private fun isHonorServiceCard(meta: Bundle?): Boolean {
    if (meta == null) return false
    val keys = meta.keySet()
    if (keys.any { it.startsWith("com.hihonor.android.launcher.card_") }) return true
    // v2.0.38 校准：3 张天气卡只有 launcher 的 widgetType /
    // widget_type 键（见上方对账），作次级接受条件补进来；
    // vivo / auto 等跨厂商前缀不接受。
    return keys.any {
        it == "com.hihonor.android.launcher.widgetType" ||
            it == "com.hihonor.android.launcher.widget_type"
    }
}

/**
 * 规范尺寸文案：card_size 是服务卡片自己声明的规范档位。
 * 值是字符串就直接用；是资源引用（>=0x7f000000）就到该
 * 应用自己的资源里解成字符串；解不出或是小整数代码就
 * 返回 null、由调用方退回「实际占用」口径。原始值由
 * CardDump 记进 shadeclock_carddump.txt 留证。
 */
private fun cardSizeLabel(
    meta: Bundle?,
    pm: PackageManager,
    pkg: String,
): String? {
    val v = try {
        @Suppress("DEPRECATION")
        meta?.get("com.hihonor.android.launcher.card_size")
    } catch (t: Throwable) {
        null
    } ?: return null
    return when (v) {
        is String -> v.trim().takeIf { it.isNotEmpty() && it.length <= 16 }
        is Int -> {
            if (v >= 0x7f000000) {
                try {
                    pm.getResourcesForApplication(pkg).getString(v)
                        .trim().takeIf { it.isNotEmpty() && it.length <= 16 }
                } catch (t: Throwable) {
                    null
                }
            } else {
                null
            }
        }
        else -> null
    }
}

/**
 * 枚举服务卡片并按应用分组（v2.0.37 起只列真服务卡片，
 * 经典小工具整段去掉——定案见 [isHonorServiceCard]）。
 * 设置应用进程内跑、无需任何权限；只在 IO 线程调用一次。
 */
private fun loadCardGroups(context: Context): List<CardAppGroup> {
    val pm = context.packageManager
    val awm = AppWidgetManager.getInstance(context) ?: return emptyList()
    val density = context.resources.displayMetrics.density
    val availWidthPx = context.resources.displayMetrics.widthPixels
    val byPkg = LinkedHashMap<String, MutableList<CardProviderEntry>>()
    val appLabels = HashMap<String, String>()
    val appIcons = HashMap<String, ImageBitmap?>()
    for (info in awm.installedProviders) {
        val meta = receiverMetaData(pm, info.provider)
        if (!isHonorServiceCard(meta)) continue
        val pkg = info.provider.packageName
        if (!appLabels.containsKey(pkg)) {
            appLabels[pkg] = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (t: Throwable) {
                pkg
            }
            appIcons[pkg] = try {
                drawableToBitmap(pm.getApplicationIcon(pkg), 96)
            } catch (t: Throwable) {
                null
            }
        }
        val label = try {
            info.loadLabel(pm) ?: ""
        } catch (t: Throwable) {
            ""
        }
        byPkg.getOrPut(pkg) { mutableListOf() }.add(
            CardProviderEntry(
                info.provider.flattenToString(),
                label,
                cardSizeLabel(meta, pm, pkg)
                    ?: cardSizeText(info, density, availWidthPx),
            ),
        )
    }
    val groups = byPkg.map { (pkg, entries) ->
        CardAppGroup(pkg, appLabels[pkg] ?: pkg, appIcons[pkg], entries)
    }
    return groups.sortedWith(
        compareBy<CardAppGroup> {
            val idx = CARD_KNOWN_PACKAGES.indexOf(it.packageName)
            if (idx >= 0) idx else CARD_KNOWN_PACKAGES.size
        }.thenBy { it.appLabel },
    )
}
