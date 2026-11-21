package io.github.yuxiximyxx.honortoolbox

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.yuxiximyxx.honortoolbox.navwake.NavWakeScreen
import io.github.yuxiximyxx.honortoolbox.navwake.NavWakeStore
import io.github.yuxiximyxx.honortoolbox.navwake.config.NavConfig
import io.github.yuxiximyxx.honortoolbox.nighticon.Keys
import io.github.yuxiximyxx.honortoolbox.nighticon.NightIconConfig
import io.github.yuxiximyxx.honortoolbox.nighticon.NightIconController
import io.github.yuxiximyxx.honortoolbox.nighticon.NightIconScreen
import io.github.yuxiximyxx.honortoolbox.nighticon.NightIconStore
import io.github.yuxiximyxx.honortoolbox.quickgesture.ConfigWriter
import io.github.yuxiximyxx.honortoolbox.quickgesture.GestureConfig
import io.github.yuxiximyxx.honortoolbox.quickgesture.QuickGestureScreen
import io.github.yuxiximyxx.honortoolbox.shadeclock.ShadeSettingsScreen
import io.github.yuxiximyxx.honortoolbox.shadeclock.ThemeStore
import io.github.yuxiximyxx.honortoolbox.shadeclock.makePrefsReadable
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LocalFloatingNavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 荣耀工具箱单一宿主。v1.2.0 按小乐拍板的信息架构重排：
 * 底栏四栏「首页 / 系统 / 应用 / 设置」。
 *
 * - 首页：纯状态总览（已启用功能数、机型、系统、版本，全部
 *   是真能读到的数据）+「重启作用域」四行直接列在页内点行
 *   执行——v1.0.1 起放在右上角按钮/底部弹窗里的重启集合
 *   本版起只剩首页这一处入口，全应用再无第二个。
 * - 系统：现有全部荣耀功能只在这里出现一次，按组排（手势
 *   与唤醒 / 通知中心 / 桌面含资源库占位 / 耳机占位），每
 *   个功能带总开关、点行进各自完整设置子页（子页内容与
 *   v1.1.0 逐项一致）；首页不再重复列功能。
 * - 应用：占位页，留给以后合入的非荣耀系统应用模块。
 * - 设置：软件主题（跟随系统/浅色/深色）、悬浮底栏开关、
 *   玻璃质感开关（只影响设置应用自身呈现，落盘在应用私有
 *   偏好 [AppSettings]，不碰任何功能配置通道）+ 关于入口。
 *
 * 底栏：悬浮开时是压在滚动内容之上的胶囊，内容可从栏下
 * 滚过；玻璃开时栏体用背景图层录制 + 高斯模糊自绘出真毛
 * 玻璃（miuix 0.8.8 的 FloatingNavigationBar 经源码核查只
 * 是实色背景、无任何模糊/背景采样能力，且其选中项只有加
 * 粗没有药丸底，故胶囊容器与药丸高亮在这里自绘，栏内项
 * 目仍用库的 FloatingNavigationBarItem）；悬浮关时改用库
 * 的贴底 NavigationBar。
 *
 * 配置绑定（原各设置页 onCreate 的事，v1.1.0 起统一在宿主
 * onCreate 做，与逐页打开等价，本版保持）：长按唤醒
 * NavConfig.bind + onChanged 落盘回调；通知中心主题
 * makePrefsReadable；图标日夜切换 NightIconController
 * 初始化（选文件结果由 onActivityResult 转交）；快服务无
 * onCreate 绑定、维持原样。LSPosed 入口不变：MODULE_
 * SETTINGS 与桌面 LAUNCHER 仍都指向本 Activity。
 */
class MainActivity : ComponentActivity() {

    private val nightIconController by lazy { NightIconController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = 0x00000000 }
        // 配置绑定（见类注释）：长按唤醒绑定 + 通知中心偏好可读
        NavConfig.bind(getSharedPreferences(NavConfig.PREFS_NAME, MODE_PRIVATE))
        NavConfig.onChanged = { NavWakeStore.syncFromPrefs(this) }
        makePrefsReadable(this)
        // 图标日夜切换控制器初始化（lazy 首次访问即完成原 onCreate 的状态推算）
        nightIconController
        setContent {
            // 设置应用自身的三项通用偏好（软件主题/悬浮底栏/
            // 玻璃质感）：状态在宿主持有，改动即落盘、即生效。
            var themeMode by remember { mutableStateOf(AppSettings.themeMode(this@MainActivity)) }
            var floatingBar by remember { mutableStateOf(AppSettings.floatingBar(this@MainActivity)) }
            var glassBar by remember { mutableStateOf(AppSettings.glassBar(this@MainActivity)) }

            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                AppThemeMode.Light -> false
                AppThemeMode.Dark -> true
                AppThemeMode.System -> systemDark
            }
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
            MiuixTheme(controller = controller) {
                // miuix 弹层组件（通知中心页内部仍在用）内部的
                // NavigationBackHandler 需要宿主提供
                // NavigationEventDispatcherOwner：activity 1.12.4
                // 起 ComponentActivity 本身即实现该接口，这里再
                // 在组合根部显式提供一份作双保险（v1.1.0 起）。
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides this@MainActivity,
                ) {
                    ToolboxHost(
                        nightIconController = nightIconController,
                        themeMode = themeMode,
                        onThemeModeChange = {
                            themeMode = it
                            AppSettings.saveThemeMode(this@MainActivity, it)
                        },
                        floatingBar = floatingBar,
                        onFloatingBarChange = {
                            floatingBar = it
                            AppSettings.saveFloatingBar(this@MainActivity, it)
                        },
                        glassBar = glassBar,
                        onGlassBarChange = {
                            glassBar = it
                            AppSettings.saveGlassBar(this@MainActivity, it)
                        },
                    )
                }
            }
        }
    }

    @Deprecated("图标日夜切换选主题文件沿用 startActivityForResult，转交控制器处理")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        nightIconController.onActivityResult(requestCode, resultCode, data)
    }
}

/** 底栏四栏（v1.2.0）。 */
private enum class ToolboxTab { Home, System, Apps, Settings }

/** 「系统」页内部页：分组总览 / 四个功能的完整设置子页。 */
private enum class SystemPage { Root, QuickGesture, NavWake, Shade, NightIcon }

/** 悬浮底栏占的高度余量：各页滚动内容底部留白加上它，最后一项不被栏遮住。 */
private val FLOATING_BAR_INSET = 100.dp

@Composable
private fun ToolboxHost(
    nightIconController: NightIconController,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    floatingBar: Boolean,
    onFloatingBarChange: (Boolean) -> Unit,
    glassBar: Boolean,
    onGlassBarChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(ToolboxTab.Home) }
    var systemPage by remember { mutableStateOf(SystemPage.Root) }
    var showAbout by remember { mutableStateOf(false) }

    // ── 各功能总开关快照（首页计数与系统页开关共用一份）──
    var state by remember { mutableStateOf(HomeState()) }
    var syncHint by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            state = withContext(Dispatchers.IO) { loadHomeState(context) }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun setShadeclock(enabled: Boolean) {
        state = state.copy(shadeclockEnabled = enabled)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ToolboxStore.writeFeatureEnabled(context, FeatureGate.SHADE_CLOCK, enabled)
            }
            syncHint = if (ok) null else "配置同步失败，请再操作一次"
            if (!ok) refresh()
        }
    }

    fun setNavwake(enabled: Boolean) {
        state = state.copy(navwakeEnabled = enabled)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { NavWakeStore.setEnabled(context, enabled) }
            syncHint = if (ok) null else "配置同步失败，请再操作一次"
            if (!ok) refresh()
        }
    }

    fun setQuickgesture(enabled: Boolean) {
        state = state.copy(quickgestureEnabled = enabled)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { writeQuickgestureEnabled(context, enabled) }
            syncHint = if (ok) null else "配置同步失败，请再操作一次"
            if (!ok) refresh()
        }
    }

    fun setNighticon(enabled: Boolean) {
        state = state.copy(nighticonEnabled = enabled)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val mirror = context.getSharedPreferences("nighticon_settings", Context.MODE_PRIVATE)
                mirror.edit().putBoolean("enabled", enabled).apply()
                val cur = NightIconStore.readState(context) ?: NightIconConfig.State()
                NightIconStore.writeConfig(context, cur.copy(enabled = enabled))
            }
            syncHint = if (ok) null else "配置同步失败，请再操作一次"
            if (!ok) refresh()
        }
    }

    fun selectTab(next: ToolboxTab) {
        // 已在系统页子页时再点「系统」栏：回系统页总览
        if (next == ToolboxTab.System && tab == ToolboxTab.System) {
            systemPage = SystemPage.Root
        }
        tab = next
    }

    // 关于：从「设置」页进入的栏外页（自带顶栏与返回），内容不变。
    if (showAbout) {
        BackHandler { showAbout = false }
        AboutScreen(onBack = { showAbout = false })
        return
    }

    // 系统页子页的返回：各子页自己的返回处理优先（快服务页内
    // 二级页、通知中心页内二级页都有自己的 BackHandler，注册
    // 更晚者先响应）；落到这里时回系统页总览。
    BackHandler(enabled = tab == ToolboxTab.System && systemPage != SystemPage.Root) {
        systemPage = SystemPage.Root
    }

    val bottomInset = if (floatingBar) FLOATING_BAR_INSET else 0.dp

    @Composable
    fun TabContent() {
        when (tab) {
            ToolboxTab.Home -> HomeScreen(
                state = state,
                syncHint = syncHint,
                bottomInset = bottomInset,
                onRestartSystemUi = { restartSystemUi(context, scope) },
                onRestartLauncher = { restartLauncher(context, scope) },
                onForceStopServiceCenter = { forceStopServiceCenter(context, scope) },
            )
            ToolboxTab.System -> when (systemPage) {
                SystemPage.Root -> SystemScreen(
                    state = state,
                    syncHint = syncHint,
                    bottomInset = bottomInset,
                    onToggleQuickGesture = { setQuickgesture(it) },
                    onToggleNavWake = { setNavwake(it) },
                    onToggleShade = { setShadeclock(it) },
                    onToggleNightIcon = { setNighticon(it) },
                    onOpenQuickGesture = { systemPage = SystemPage.QuickGesture },
                    onOpenNavWake = { systemPage = SystemPage.NavWake },
                    onOpenShade = { systemPage = SystemPage.Shade },
                    onOpenNightIcon = { systemPage = SystemPage.NightIcon },
                )
                SystemPage.QuickGesture -> QuickGestureScreen(
                    onExit = { systemPage = SystemPage.Root },
                    bottomInset = bottomInset,
                )
                SystemPage.NavWake -> NavWakeScreen(
                    onBack = { systemPage = SystemPage.Root },
                    bottomInset = bottomInset,
                )
                SystemPage.Shade -> ShadeSettingsScreen(
                    onExit = { systemPage = SystemPage.Root },
                    bottomInset = bottomInset,
                )
                SystemPage.NightIcon -> NightIconScreen(
                    state = nightIconController.uiState,
                    onBack = { systemPage = SystemPage.Root },
                    onEnabledChange = { nightIconController.onEnabledChange(it) },
                    onPickLight = { nightIconController.pick(Keys.Slot.LIGHT) },
                    onPickDark = { nightIconController.pick(Keys.Slot.DARK) },
                    onApply = { nightIconController.applyNow() },
                    bottomInset = bottomInset,
                )
            }
            ToolboxTab.Apps -> AppsScreen(bottomInset = bottomInset)
            ToolboxTab.Settings -> SettingsScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                floatingBar = floatingBar,
                onFloatingBarChange = onFloatingBarChange,
                glassBar = glassBar,
                onGlassBarChange = onGlassBarChange,
                onOpenAbout = { showAbout = true },
                bottomInset = bottomInset,
            )
        }
    }

    if (floatingBar) {
        // 背景图层：内容整层录制进 backdropLayer，悬浮栏在自
        // 己的绘制里取栏下那一块做高斯模糊——内容是真的从
        // 栏下滚过的（不预留实色底带），玻璃开时透出毛玻璃。
        val backdropLayer = rememberGraphicsLayer()
        var contentOrigin by remember { mutableStateOf(Offset.Zero) }
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { contentOrigin = it.positionInRoot() }
                    .then(
                        if (glassBar) {
                            Modifier.drawWithContent {
                                backdropLayer.record(
                                    density = this,
                                    layoutDirection = layoutDirection,
                                    size = IntSize(
                                        size.width.roundToInt(),
                                        size.height.roundToInt(),
                                    ),
                                ) { this@drawWithContent.drawContent() }
                                drawLayer(backdropLayer)
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                TabContent()
            }
            FloatingGlassBar(
                backdropLayer = backdropLayer,
                contentOrigin = contentOrigin,
                glass = glassBar,
                tab = tab,
                onSelect = { selectTab(it) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                TabContent()
            }
            NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
                NavigationBarItem(
                    selected = tab == ToolboxTab.Home,
                    onClick = { selectTab(ToolboxTab.Home) },
                    icon = MiuixIcons.All,
                    label = "首页",
                )
                NavigationBarItem(
                    selected = tab == ToolboxTab.System,
                    onClick = { selectTab(ToolboxTab.System) },
                    icon = MiuixIcons.Tune,
                    label = "系统",
                )
                NavigationBarItem(
                    selected = tab == ToolboxTab.Apps,
                    onClick = { selectTab(ToolboxTab.Apps) },
                    icon = MiuixIcons.GridView,
                    label = "应用",
                )
                NavigationBarItem(
                    selected = tab == ToolboxTab.Settings,
                    onClick = { selectTab(ToolboxTab.Settings) },
                    icon = MiuixIcons.Settings,
                    label = "设置",
                )
            }
        }
    }
}

/**
 * 悬浮胶囊底栏：压在内容之上，玻璃开时栏体是背景内容的
 * 高斯模糊 + 半透明主题色（真毛玻璃）；玻璃关时是实色不
 * 透明。选中项带药丸底（库的 FloatingNavigationBarItem 只
 * 有加粗、无药丸，药丸靠给选中项加背景修饰实现）。
 */
@Composable
private fun FloatingGlassBar(
    backdropLayer: GraphicsLayer,
    contentOrigin: Offset,
    glass: Boolean,
    tab: ToolboxTab,
    onSelect: (ToolboxTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val pillColor = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.10f)
    var barOrigin by remember { mutableStateOf(Offset.Zero) }
    val blurLayer = rememberGraphicsLayer()
    SideEffect {
        blurLayer.renderEffect = BlurEffect(64f, 64f, TileMode.Clamp)
    }
    Row(
        modifier = modifier
            .padding(horizontal = 18.dp)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(bottom = 12.dp)
            .onGloballyPositioned { barOrigin = it.positionInRoot() }
            .shadow(elevation = 12.dp, shape = shape)
            .then(
                if (glass) {
                    Modifier
                        .clip(shape)
                        .drawWithContent {
                            blurLayer.record(
                                density = this,
                                layoutDirection = layoutDirection,
                                size = IntSize(
                                    size.width.roundToInt(),
                                    size.height.roundToInt(),
                                ),
                            ) {
                                translate(
                                    -(barOrigin.x - contentOrigin.x),
                                    -(barOrigin.y - contentOrigin.y),
                                ) {
                                    drawLayer(backdropLayer)
                                }
                            }
                            drawLayer(blurLayer)
                            drawRect(surface.copy(alpha = 0.55f))
                            drawContent()
                        }
                } else {
                    Modifier.background(surface, shape)
                },
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalFloatingNavigationBarDisplayMode provides FloatingNavigationBarDisplayMode.IconAndText,
        ) {
            val tabs = listOf(
                Triple(ToolboxTab.Home, MiuixIcons.All, "首页"),
                Triple(ToolboxTab.System, MiuixIcons.Tune, "系统"),
                Triple(ToolboxTab.Apps, MiuixIcons.GridView, "应用"),
                Triple(ToolboxTab.Settings, MiuixIcons.Settings, "设置"),
            )
            for ((value, icon, label) in tabs) {
                val selected = tab == value
                FloatingNavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(value) },
                    icon = icon,
                    label = label,
                    modifier = if (selected) {
                        Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(pillColor)
                            .padding(horizontal = 7.dp)
                    } else {
                        Modifier.padding(horizontal = 7.dp)
                    },
                )
            }
        }
    }
}

/**
 * 首页：纯状态总览 + 重启区。状态只用真能读到的数据（功能
 * 开关快照、机型、Android 版本、工具箱版本），不编造读不到
 * 的状态。重启四行直接列在页内，点行即执行——全应用唯一
 * 的重启入口（右上角按钮与底部弹窗已撤除）。
 */
@Composable
private fun HomeScreen(
    state: HomeState,
    syncHint: String?,
    bottomInset: Dp,
    onRestartSystemUi: () -> Unit,
    onRestartLauncher: () -> Unit,
    onForceStopServiceCenter: () -> Unit,
) {
    val enabledCount = listOf(
        state.quickgestureEnabled,
        state.navwakeEnabled,
        state.shadeclockEnabled,
        state.nighticonEnabled,
    ).count { it }
    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = "荣耀工具箱",
                color = Color.Transparent,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp + bottomInset),
            ) {
                SmallTitle(
                    text = "状态",
                    insideMargin = PaddingValues(start = 4.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "已启用功能",
                        summary = "$enabledCount / 4（开关在「系统」页）",
                    )
                    BasicComponent(
                        title = "机型",
                        summary = "${Build.MANUFACTURER} ${Build.MODEL}",
                    )
                    BasicComponent(
                        title = "系统",
                        summary = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                    )
                    BasicComponent(
                        title = "工具箱版本",
                        summary = BuildConfig.VERSION_NAME,
                    )
                }

                if (syncHint != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        syncHint,
                        fontSize = 13.sp,
                        color = Color(0xFFE5484D),
                    )
                }

                SmallTitle(
                    text = "重启作用域",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    RestartRow(
                        title = "重启系统界面",
                        summary = "通知中心主题的改动靠它生效",
                        onClick = onRestartSystemUi,
                    )
                    RestartRow(
                        title = "重启长按导航条唤醒",
                        summary = "和「重启系统界面」是同一个动作，长按唤醒的开关改动靠它生效",
                        onClick = onRestartSystemUi,
                    )
                    RestartRow(
                        title = "重启桌面",
                        summary = "图标日夜切换的改动靠它生效",
                        onClick = onRestartLauncher,
                    )
                    RestartRow(
                        title = "强停快服务",
                        summary = "快服务手势改向首次启用作用域后点它",
                        onClick = onForceStopServiceCenter,
                    )
                }

                Spacer(Modifier.height(20.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "从旧模块换过来？装上荣耀工具箱后，请在 LSPosed 里停用或卸载原来的同名模块，避免同一功能被挂两遍。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** 首页重启区的一行：点行直接执行，行尾一个重启图标示意可点。 */
@Composable
private fun RestartRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        onClick = onClick,
        endActions = {
            Icon(
                imageVector = MiuixIcons.Refresh,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
            )
        },
    )
}

/**
 * 系统页：现有全部荣耀功能只在这里出现一次，按组排。每行
 * 带总开关（与 v1.1.0 首页行尾开关同一套读写通道），点行
 * 进各自的完整设置子页。资源库与耳机是第二阶段占位。
 */
@Composable
private fun SystemScreen(
    state: HomeState,
    syncHint: String?,
    bottomInset: Dp,
    onToggleQuickGesture: (Boolean) -> Unit,
    onToggleNavWake: (Boolean) -> Unit,
    onToggleShade: (Boolean) -> Unit,
    onToggleNightIcon: (Boolean) -> Unit,
    onOpenQuickGesture: () -> Unit,
    onOpenNavWake: () -> Unit,
    onOpenShade: () -> Unit,
    onOpenNightIcon: () -> Unit,
) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = "系统",
                color = Color.Transparent,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp + bottomInset),
            ) {
                SmallTitle(
                    text = "手势与唤醒",
                    insideMargin = PaddingValues(start = 4.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    FeatureRow(
                        title = "快服务手势改向",
                        summary = state.quickgestureSummary,
                        checked = state.quickgestureEnabled,
                        onCheckedChange = onToggleQuickGesture,
                        onClick = onOpenQuickGesture,
                    )
                    FeatureRow(
                        title = "长按导航条唤醒",
                        summary = "把长按导航条唤醒 YOYO 改成打开自选应用",
                        checked = state.navwakeEnabled,
                        onCheckedChange = onToggleNavWake,
                        onClick = onOpenNavWake,
                    )
                }

                SmallTitle(
                    text = "通知中心",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    FeatureRow(
                        title = "通知中心主题",
                        summary = "在通知中心顶部渲染锁屏主题样式，也可隐藏运营商文字",
                        checked = state.shadeclockEnabled,
                        onCheckedChange = onToggleShade,
                        onClick = onOpenShade,
                    )
                }

                SmallTitle(
                    text = "桌面",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    FeatureRow(
                        title = "图标日夜切换",
                        summary = "桌面图标随系统日夜模式在两套主题之间切换",
                        checked = state.nighticonEnabled,
                        onCheckedChange = onToggleNightIcon,
                        onClick = onOpenNightIcon,
                    )
                    PlaceholderRow(
                        title = "资源库增强",
                        summary = "下一阶段合入",
                    )
                }

                SmallTitle(
                    text = "耳机",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    PlaceholderRow(
                        title = "OPPO Enco Free4 设备中心",
                        summary = "下一阶段合入",
                    )
                    PlaceholderRow(
                        title = "索尼 WH-1000XM4 设备中心",
                        summary = "下一阶段合入",
                    )
                }

                if (syncHint != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        syncHint,
                        fontSize = 13.sp,
                        color = Color(0xFFE5484D),
                    )
                }
            }
        }
    }
}

/** 应用页：占位。以后合入的非荣耀系统应用模块放这里，本版无功能项。 */
@Composable
private fun AppsScreen(bottomInset: Dp) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = "应用",
                color = Color.Transparent,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp + bottomInset),
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "这里以后放不是荣耀系统的应用模块——给某个第三方应用做的增强（比如掌盟那类）会出现在这一页。现在还没有这类功能合进来。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 设置页：全应用通用设置 + 关于。三项偏好只影响设置应用
 * 自身呈现（落盘见 [AppSettings]），选中/拨动即时生效。
 */
@Composable
private fun SettingsScreen(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    floatingBar: Boolean,
    onFloatingBarChange: (Boolean) -> Unit,
    glassBar: Boolean,
    onGlassBarChange: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    bottomInset: Dp,
) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = "设置",
                color = Color.Transparent,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp + bottomInset),
            ) {
                SmallTitle(
                    text = "外观",
                    insideMargin = PaddingValues(start = 4.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "软件主题",
                        summary = "只影响荣耀工具箱自己的界面",
                    )
                    ThemeModeRow(
                        title = "跟随系统",
                        selected = themeMode == AppThemeMode.System,
                        onClick = { onThemeModeChange(AppThemeMode.System) },
                    )
                    ThemeModeRow(
                        title = "浅色",
                        selected = themeMode == AppThemeMode.Light,
                        onClick = { onThemeModeChange(AppThemeMode.Light) },
                    )
                    ThemeModeRow(
                        title = "深色",
                        selected = themeMode == AppThemeMode.Dark,
                        onClick = { onThemeModeChange(AppThemeMode.Dark) },
                    )
                }

                SmallTitle(
                    text = "底栏",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "悬浮底栏",
                        summary = "关掉后底栏贴在屏幕底部、不再悬浮",
                        endActions = {
                            Switch(
                                checked = floatingBar,
                                onCheckedChange = onFloatingBarChange,
                            )
                        },
                    )
                    BasicComponent(
                        title = "玻璃质感",
                        summary = if (floatingBar) {
                            "悬浮底栏透出毛玻璃；关掉后是实色不透明"
                        } else {
                            "悬浮底栏关着时不生效"
                        },
                        enabled = floatingBar,
                        endActions = {
                            Switch(
                                checked = glassBar,
                                onCheckedChange = { if (floatingBar) onGlassBarChange(it) },
                            )
                        },
                    )
                }

                SmallTitle(
                    text = "关于",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    SuperArrow(
                        title = "关于",
                        summary = "版本 ${BuildConfig.VERSION_NAME} · 来源与致谢",
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }
}

/** 软件主题的一个选项行：选中时行尾打勾。 */
@Composable
private fun ThemeModeRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        onClick = onClick,
        endActions = {
            if (selected) {
                Icon(
                    imageVector = MiuixIcons.Ok,
                    contentDescription = "当前选择",
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        },
    )
}

/**
 * 「重启系统界面」：沿用通知中心主题原按钮的实现——写 restart.txt
 * 请求标记，SystemUI 侧看守读到后结束自己进程被系统拉起（等同
 * 强停、不依赖 root）。长按唤醒的总开关改动也靠它生效。
 */
private fun restartSystemUi(context: Context, scope: CoroutineScope) {
    scope.launch {
        val ok = withContext(Dispatchers.IO) { ThemeStore.writeRestartRequest(context) }
        Toast.makeText(
            context,
            if (ok) "已发送重启请求，系统界面马上会重启" else "发送失败，请再试一次",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * 「重启桌面」：与图标日夜切换页原按钮同机制——首选 root
 * `am force-stop`，无 root 时兜底写落盘配置的 restart_token，
 * 由桌面进程复查到后结束自身。
 */
private fun restartLauncher(context: Context, scope: CoroutineScope) {
    scope.launch(Dispatchers.IO) {
        val rootOk = forceStopViaRoot("com.hihonor.android.launcher")
        val msg = when {
            rootOk -> "已重启桌面"
            writeLauncherRestartToken(context) -> "已发送重启桌面的请求"
            else -> "重启桌面失败，请到系统设置里强停桌面"
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * 「强停快服务」：快服务手势改向的宿主进程没有重启令牌
 * 通道，机制与「重启桌面」的 root 强停同款；root 不可用时
 * 只能提示去系统设置里手动强停。
 */
private fun forceStopServiceCenter(context: Context, scope: CoroutineScope) {
    scope.launch(Dispatchers.IO) {
        val rootOk = forceStopViaRoot("com.hihonor.servicecenter")
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                if (rootOk) "已强停快服务" else "强停失败，请到系统设置里手动强停快服务",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

private fun forceStopViaRoot(pkg: String): Boolean = runCatching {
    val p = ProcessBuilder("su", "-c", "am force-stop $pkg")
        .redirectErrorStream(true).start()
    p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
}.getOrDefault(false)

/** 桌面重启兜底令牌（与图标日夜切换页原 writeRestartToken 同口径）。 */
private fun writeLauncherRestartToken(context: Context): Boolean {
    val cur = NightIconStore.readState(context) ?: run {
        val mirror = context.getSharedPreferences("nighticon_settings", Context.MODE_PRIVATE)
        NightIconConfig.State(
            enabled = mirror.getBoolean(Keys.ENABLED, false),
            lightVersion = mirror.getLong("${Keys.Slot.LIGHT.id}_pushed_version", 0),
            darkVersion = mirror.getLong("${Keys.Slot.DARK.id}_pushed_version", 0),
            lightHasBackup = mirror.getBoolean(Keys.hasBackupKey(Keys.Slot.LIGHT), false),
            darkHasBackup = mirror.getBoolean(Keys.hasBackupKey(Keys.Slot.DARK), false),
        )
    }
    return NightIconStore.writeConfig(
        context,
        cur.copy(restartToken = System.currentTimeMillis()),
    )
}

/** 功能行：点行进该功能的设置子页，行尾开关是总开关。 */
@Composable
private fun FeatureRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        onClick = onClick,
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

/** 第二阶段占位行：无开关、不可点。 */
@Composable
private fun PlaceholderRow(
    title: String,
    summary: String,
) {
    BasicComponent(
        title = title,
        summary = summary,
        enabled = false,
    )
}

/** 各功能开关的快照（从各自配置通道读，读不到按默认）。 */
private data class HomeState(
    val shadeclockEnabled: Boolean = true,
    val navwakeEnabled: Boolean = true,
    val quickgestureEnabled: Boolean = false,
    val quickgestureSummary: String = "把底角斜滑手势改成打开自选应用",
    val nighticonEnabled: Boolean = false,
)

private fun loadHomeState(context: Context): HomeState {
    val features = ToolboxStore.readFeatureStates(context)
    val qgPrefs = context.getSharedPreferences("quickgesture_settings", Context.MODE_PRIVATE)
    val qgEnabled = qgPrefs.getBoolean("enabled", false)
    val qgPkg = qgPrefs.getString("package", "") ?: ""
    val qgSummary = if (qgPkg.isEmpty()) {
        "把底角斜滑手势改成打开自选应用"
    } else {
        val label = try {
            val ai = context.packageManager.getApplicationInfo(qgPkg, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (t: Throwable) {
            qgPkg
        }
        "当前目标：$label"
    }
    val niPrefs = context.getSharedPreferences("nighticon_settings", Context.MODE_PRIVATE)
    return HomeState(
        shadeclockEnabled = features[FeatureGate.SHADE_CLOCK] ?: true,
        navwakeEnabled = NavWakeStore.readEnabled(context),
        quickgestureEnabled = qgEnabled,
        quickgestureSummary = qgSummary,
        nighticonEnabled = niPrefs.getBoolean("enabled", false),
    )
}

/** 写手势改向开关：改镜像偏好 + 落盘文件（保留已选目标）。 */
private fun writeQuickgestureEnabled(context: Context, enabled: Boolean): Boolean {
    val prefs = context.getSharedPreferences("quickgesture_settings", Context.MODE_PRIVATE)
    val current = GestureConfig(
        enabled = prefs.getBoolean("enabled", false),
        mode = prefs.getString("mode", GestureConfig.MODE_APP) ?: GestureConfig.MODE_APP,
        pkg = prefs.getString("package", "") ?: "",
        activity = prefs.getString("activity", "") ?: "",
    )
    val next = current.copy(enabled = enabled)
    prefs.edit().putBoolean("enabled", enabled).apply()
    return ConfigWriter.writeConfig(context, next)
}
