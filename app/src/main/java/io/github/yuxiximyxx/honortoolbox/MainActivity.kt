package io.github.yuxiximyxx.honortoolbox

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.yuxiximyxx.honortoolbox.libraryplus.LibraryPlusScreen
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
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
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
 * 滚过；玻璃开时栏体是 Kyant backdrop 真采样液态玻璃。
 * v1.2.3 按泥坛现行实现逐参数对齐（详见 FloatingGlassBar
 * 注释）：LIQUID 档整表（叠纱 α0.32、饱和 1.5、模糊
 * 8dp、透镜 24/24 带色散、高光 0.85）+ aligned surround
 * 对齐边缘；几何改泥坛公式（72dp 槽宽 + 6dp 内边距、
 * 封顶 380dp、栏底间隙 10dp）。v1.2.2 的磨砂档在真机
 * 上被批「折射、通透、模糊、色散一个都没有」：磨砂档本
 * 就是三档里最弱的（叠纱 α0.55、透镜仅 20/6、无色散），
 * 且当时窗口未沉浸、栏正下方恰是各页留白的纯色区，均匀
 * 场采样后仍是均匀色——本版连窗口沉浸一起改（见
 * onCreate），内容真铺到栏下，录制节点内再按泥坛
 * recordedPageBackground 定式先铺一层页面背景，保证采
 * 样纹理处处不透明（泥坛 v1.7.8 踩过：纹理缺背景层、
 * 透明像素被采样压黑，栏会变成熏黑胶囊）。玻璃关时同
 * 一胶囊变实色，悬浮关时改用库的贴底 NavigationBar。
 *
 * 激活判定（v1.2.3）：首页大卡改双信号取或——自检标记
 * （需 LSPosed 勾自身作用域）或激活状态文件（任一宿主
 * 进程的 hook 加载时写，见 ActivationStatus）；文件版
 * 本落后于本版时是「旧版 hook 还在跑」的中间态，不再
 * 误报未激活。
 *
 * 页面底色口径（v1.2.1）：各页 Scaffold 底色统一用
 * colorScheme.surface（浅色 #F7F7F7、夜间纯黑），卡片用
 * surfaceContainer（浅色白、夜间 #242424）——miuix 浅色
 * 方案的 background 与 surfaceContainer 同为纯白，早先各
 * 页显式写 background 时白卡压白底、卡片完全看不见，这
 * 是 v1.2.0 首页「没有卡片」的根因。
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
        // 全应用沉浸（v1.2.3，泥坛 MainActivity 同款断言）：
        // 状态栏与系统导航条全透明、内容铺到屏幕物理底边，
        // 悬浮底栏浮在内容之上采样真内容，不再有实色黑带。
        // 各页顶部避让由 miuix Scaffold 的 systemBars
        // insets 处理（其 contentWindowInsets 默认含
        // systemBars），顶栏不会顶进状态栏。
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = 0x00000000
            window.navigationBarColor = 0x00000000
        }
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
private enum class SystemPage { Root, QuickGesture, NavWake, Shade, NightIcon, LibraryPlus }

/** 首页大卡的激活三态（v1.2.3）：已激活 / 旧版 hook 还在跑 / 未激活。 */
private enum class ActivationUiState { Active, ActiveOld, Inactive }

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
    // 首页状态大卡的真值：激活状态走双信号（自检标记或
    // 激活状态文件，见下方 activationFile），作用域个数
    // 读模块声明的作用域数组。
    // 标记在 ON_RESUME 时随 refresh 一并重读：用户刚在
    // LSPosed 里启用完切回来（进程已被注入）能立刻翻状态。
    var moduleActive by remember { mutableStateOf(ModuleActivation.isActive()) }
    // 激活状态文件（任一宿主进程的 hook 加载时写，见
    // ActivationStatus）：与自检标记双信号取或。标记要
    // LSPosed 勾工具箱自身作用域才命中，用户只勾「推荐
    // 应用」时命不中——文件信号兜底；文件 versionCode
    // 落后于本版说明宿主里跑的还是旧版 hook（中间态）。
    var activationFile by remember { mutableStateOf<ActivationStatus.FileInfo?>(null) }
    val activationState = when {
        moduleActive -> ActivationUiState.Active
        activationFile == null -> ActivationUiState.Inactive
        activationFile!!.versionCode >= BuildConfig.VERSION_CODE -> ActivationUiState.Active
        else -> ActivationUiState.ActiveOld
    }
    val scopeCount = remember {
        context.resources.getStringArray(R.array.xposedscope).size
    }
    var tab by remember { mutableStateOf(ToolboxTab.Home) }
    var systemPage by remember { mutableStateOf(SystemPage.Root) }
    var showAbout by remember { mutableStateOf(false) }

    // ── 各功能总开关快照（首页计数与系统页开关共用一份）──
    var state by remember { mutableStateOf(HomeState()) }
    var syncHint by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        moduleActive = ModuleActivation.isActive()
        scope.launch {
            state = withContext(Dispatchers.IO) { loadHomeState(context) }
            activationFile = withContext(Dispatchers.IO) {
                // 先占位建文件（已有则不动），保证宿主进程之后
                // 覆盖写的是设置应用自有文件、读得回来
                ActivationStatus.ensurePlaceholder(context)
                ActivationStatus.read(context)
            }
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

    // 悬浮栏下的滚动留白（泥坛 tabDockContentBottomPadding
    // 定版）：栏高 + 栏底间隙 + 呼吸 16dp + 本机导航条
    // inset。窗口已沉浸、页面背景本身铺到物理底边，这段
    // 留白只是滚动余量——保证最后一项能滚出栏的遮挡，
    // 不是实色底带。
    val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = if (floatingBar) {
        DOCK_HEIGHT + DOCK_BOTTOM_GAP + 16.dp + navBottomPadding
    } else {
        0.dp
    }

    @Composable
    fun TabContent() {
        when (tab) {
            ToolboxTab.Home -> HomeScreen(
                state = state,
                syncHint = syncHint,
                activationState = activationState,
                activationFileVersion = activationFile?.versionCode,
                scopeCount = scopeCount,
                bottomInset = bottomInset,
                onOpenLsposed = { openLsposedManager(context) },
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
                    onOpenLibraryPlus = { systemPage = SystemPage.LibraryPlus },
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
                SystemPage.LibraryPlus -> LibraryPlusScreen(
                    onBack = { systemPage = SystemPage.Root },
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
        // 玻璃采样：内容整层经 Kyant 的 layerBackdrop 录制成
        // 采样源，悬浮栏（内容 Box 的兄弟节点、不在录制子树
        // 内，不会自采样）在自己的绘制里消费它做液态玻璃。
        // 玻璃关时不录制、栏体走实色。
        val backdrop = rememberLayerBackdrop()
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (glassBar) Modifier.layerBackdrop(backdrop) else Modifier)
                    // 录制节点内先铺一层页面背景（泥坛
                    // recordedPageBackground 定式）：Kyant 只
                    // 录本节点画出的东西，纹理没画到的区域是
                    // 透明的、采样时会被压成近黑——背景先进
                    // 纹理，采样到的处处是完整页面。屏幕上无
                    // 变化，各页 Scaffold 本来就画这个颜色。
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                TabContent()
            }
            FloatingGlassBar(
                backdrop = backdrop,
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

/** 悬浮 Dock 每栏的槽宽（泥坛 MainTabDock 定版：72dp）。 */
private val DOCK_SLOT_WIDTH = 72.dp

/** 悬浮 Dock 内边距：槽行与胶囊边缘之间（泥坛定版 6dp）。 */
private val DOCK_INNER_PADDING = 6.dp

/** 悬浮 Dock 高度（泥坛定版 68dp）。 */
private val DOCK_HEIGHT = 68.dp

/** 悬浮 Dock 宽度的绝对上限（泥坛定版 380dp）。 */
private val DOCK_MAX_WIDTH = 380.dp

/** Dock 底边与系统导航区之间的间隙（泥坛定版 10dp）。 */
private val DOCK_BOTTOM_GAP = 10.dp

/**
 * 泥坛 LIQUID（液态）档外壳的对齐边缘模型（aligned
 * surround）：双峰边缘——上弧最亮、下弧次之——以两道
 * border 呈现（宽而弱的内发光带 + 窄的边缘线），外加
 * 向外的多层光晕与定色投影。数值逐项照泥坛 MainTabDock
 * 的 dockAlignedSurround / DockHaloLayers（源自贴吧
 * FloatingDock 的定版表）。
 */
private data class DockSurround(
    val rimTop: Color,
    val rimBottom: Color,
    val haloBase: Color,
    val shadowColor: Color,
)

private fun dockSurround(dark: Boolean): DockSurround = if (dark) {
    DockSurround(
        rimTop = Color.White.copy(alpha = 0.22f),
        rimBottom = Color.White.copy(alpha = 0.10f),
        haloBase = Color.White.copy(alpha = 0.05f),
        shadowColor = Color.Black.copy(alpha = 0.22f),
    )
} else {
    DockSurround(
        rimTop = Color.White.copy(alpha = 0.90f),
        rimBottom = Color.Black.copy(alpha = 0.10f),
        haloBase = Color.Black.copy(alpha = 0.045f),
        shadowColor = Color.Black.copy(alpha = 0.10f),
    )
}

/** 对齐边缘的光晕层表：描边宽 dp → alpha 系数（泥坛定版）。 */
private val DOCK_HALO_LAYERS: List<Pair<Float, Float>> = listOf(
    0.75f to 0.30f, 1.5f to 0.22f, 2.25f to 0.16f, 3f to 0.117f,
    3.75f to 0.085f, 4.5f to 0.062f, 5.25f to 0.045f, 6f to 0.033f,
)

/** 双峰边缘的四段垂直渐变刷（泥坛 dockRimBrush；工具箱无高光微调，系数恒 1）。 */
private fun dockRimBrush(surround: DockSurround, factor: Float): Brush {
    val top = surround.rimTop.copy(
        alpha = (surround.rimTop.alpha * factor).coerceAtMost(1f),
    )
    val bottom = surround.rimBottom.copy(
        alpha = (surround.rimBottom.alpha * factor).coerceAtMost(1f),
    )
    return Brush.verticalGradient(
        0f to top,
        0.35f to top.copy(alpha = 0f),
        0.65f to bottom.copy(alpha = 0f),
        1f to bottom,
    )
}

/** 光晕：多层同心描边逐层向外扩、alpha 递减（泥坛 drawDockHalo）。 */
private fun DrawScope.drawDockHalo(haloBase: Color) {
    DOCK_HALO_LAYERS.forEach { (widthDp, factor) ->
        val wPx = widthDp.dp.toPx()
        val half = wPx / 2f
        drawRoundRect(
            color = haloBase.copy(alpha = haloBase.alpha * factor),
            topLeft = Offset(-half, -half),
            size = Size(size.width + wPx, size.height + wPx),
            cornerRadius = CornerRadius(size.height / 2f + half, size.height / 2f + half),
            style = Stroke(width = wPx),
        )
    }
}

/**
 * 悬浮胶囊底栏（v1.2.3 按泥坛现行实现逐参数对齐，v1.3.0 补齐
 * 药丸拖动移植）：
 *
 * - 几何：高 68dp；宽 = 栏数 × 72dp 槽宽 + 两侧 6dp 内
 *   边距，封顶屏宽 −32dp 与 380dp；放置是居中 + 先吃
 *   导航条 inset 再留 10dp 间隙（泥坛 MainTabBar 的摆
 *   法），窗口已沉浸、栏下就是真内容。
 * - 玻璃开：泥坛 LIQUID 档整表——叠纱深 #121212 α0.32
 *   / 浅 #FAFAFA α0.32，效果链饱和 1.5 → 模糊 8dp →
 *   透镜 24/24 带色散，高光 1.0 × 0.85（泥坛外壳系数
 *   DockLiquidHighlightScale）；外壳边缘走 aligned
 *   surround（双峰边缘 + 八层光晕 + 定色 10dp 投影）。
 *   LIQUID 全表与 v1.2.3 一字未动。
 * - 选中药丸：LIQUID 档的近黑薄膜（深 α0.12 / 浅
 *   α0.08）。v1.3.0 起药丸是真透镜且可拖动（泥坛
 *   MainTabDock 的拖动系统补齐移植）：[DampedDragAnimation]
 *   目录弹簧组原样（value ζ1.0/k1000、press ζ1.0/k1000、
 *   scaleX ζ0.6/k250、scaleY ζ0.7/k250、velocity
 *   ζ0.5/k300、pressedScale 78/56），手势决策走纯手势机
 *   [DockGestureMachine]（点按 = 零位移拖动、松手吸附最近
 *   栏并导航）；按压时药丸透镜放大（折射 10/14 ×
 *   pressProgress，色散开），胶囊整体橡皮筋平移（4dp
 *   EaseOut 上限）并绽放（上限 +5%）。
 * - 与泥坛的取舍（工具箱标签常显口径优先）：泥坛的可见行
 *   标签随药丸接近才长出/淡入，且药丸采样的是页面 +
 *   tabsBackdrop 强调色副本的组合纹理——被药丸盖住的选中
 *   字形只能经药丸纹理到达屏幕。工具箱四栏标签必须常显，
 *   若照搬组合采样，可见行字形与副本字形会在药丸滑动时
 *   错位重影。故本版舍弃 tabsBackdrop 副本：药丸只采样
 *   页面 backdrop 做透镜，可见行（图标 + 文字常显、字形色
 *   随药丸位置在未选色与强调色之间 lerp）画在药丸之上，
 *   字形永远清晰不重影；代价是按压时被药丸盖住的字形不
 *   随透镜变形（页面内容仍被透镜放大）。
 * - 玻璃关：同一胶囊变实色，药丸退化为同色薄膜块（拖动、
 *   吸附、按压缩放照常）；悬浮关走库的贴底 NavigationBar
 *   （宿主侧处理，不在这里）。
 */
@Composable
private fun FloatingGlassBar(
    backdrop: LayerBackdrop,
    glass: Boolean,
    tab: ToolboxTab,
    onSelect: (ToolboxTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        Triple(ToolboxTab.Home, MiuixIcons.All, "首页"),
        Triple(ToolboxTab.System, MiuixIcons.Tune, "系统"),
        Triple(ToolboxTab.Apps, MiuixIcons.GridView, "应用"),
        Triple(ToolboxTab.Settings, MiuixIcons.Settings, "设置"),
    )
    val shape = RoundedCornerShape(percent = 50)
    val scheme = MiuixTheme.colorScheme
    val darkBar = scheme.surfaceContainer.luminance() < 0.5f
    val surround = dockSurround(darkBar)
    // LIQUID 档叠纱（深 #121212 α0.32 / 浅 #FAFAFA α0.32）
    val glassTint = if (darkBar) {
        Color(0xFF121212).copy(alpha = 0.32f)
    } else {
        Color(0xFFFAFAFA).copy(alpha = 0.32f)
    }
    // LIQUID 档选中药丸：近黑薄膜（深 α0.12 / 浅 α0.08）
    val pillColor = Color.Black.copy(alpha = if (darkBar) 0.12f else 0.08f)
    val idleContent = if (darkBar) Color(0xFFF2F2F7) else Color(0xFF3A3A3C)
    val accent = scheme.primary
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val dockWidth = minOf(
        DOCK_SLOT_WIDTH * tabs.size + DOCK_INNER_PADDING * 2,
        (screenWidth - 32.dp).coerceAtLeast(0.dp),
        DOCK_MAX_WIDTH,
    )
    val slotWidth = (dockWidth - DOCK_INNER_PADDING * 2) / tabs.size
    val density = LocalDensity.current
    val slotWidthPx = with(density) { slotWidth.toPx() }
    val dockWidthPx = with(density) { dockWidth.toPx() }
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val selectedIndex = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)

    // ── 拖动系统（泥坛 MainTabDock 同构，弹簧参数见类注释）──
    val animationScope = rememberCoroutineScope()
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density, dockWidthPx) {
        derivedStateOf {
            val fraction = (offsetAnimation.value / dockWidthPx).coerceIn(-1f, 1f)
            with(density) {
                4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }
    val onSelectState = rememberUpdatedState(onSelect)
    val tabsState = rememberUpdatedState(tabs)
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabs.size - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
        )
    }
    // 手势决策只在纯手势机里做（泥坛 v1.7.5 的教训：释放时的
    // 导航目标必须来自手势本身的位置，不能读动画的异步状态，
    // 否则快速点按会导航不到）。这里只把指针流翻译成机器
    // 事件、把机器意图执行到动画引擎上。
    val gestureMachine = remember(slotWidthPx, tabs.size, isLtr) {
        DockGestureMachine(
            slotWidthPx = slotWidthPx,
            tabCount = tabs.size,
            isLtr = isLtr,
        )
    }
    fun executeDockIntent(intent: DockGestureIntent) {
        when (intent) {
            is DockGestureIntent.PressStarted -> {
                if (intent.index != dampedDragAnimation.targetValue.roundToInt()) {
                    dampedDragAnimation.updateValue(intent.index.toFloat())
                }
                dampedDragAnimation.press()
            }
            is DockGestureIntent.DragTo -> {
                dampedDragAnimation.updateValue(intent.value)
                animationScope.launch {
                    offsetAnimation.snapTo(offsetAnimation.value + intent.deltaPx)
                }
            }
            is DockGestureIntent.ReleaseSelect -> {
                val wasCurrent = intent.index == currentIndex
                currentIndex = intent.index
                dampedDragAnimation.animateToValue(intent.index.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
                dampedDragAnimation.release()
                if (wasCurrent) {
                    // 点按已选中栏：currentIndex 不变、下方收集器
                    // 不会触发，直接走宿主选择——「系统页子页时
                    // 再点系统栏回总览」的既有行为靠这条保留。
                    tabsState.value.getOrNull(intent.index)?.let {
                        onSelectState.value(it.first)
                    }
                }
            }
            DockGestureIntent.ReleaseCancel -> {
                dampedDragAnimation.animateToValue(currentIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
                dampedDragAnimation.release()
            }
        }
    }
    val dockGestureModifier = Modifier.pointerInput(gestureMachine) {
        awaitEachGesture {
            awaitFirstDown(false, PointerEventPass.Initial)
            val down = awaitFirstDown(false)
            gestureMachine.onEvent(
                DockGestureEvent.Down(down.position.x, currentIndex),
            ).forEach(::executeDockIntent)
            val pointerId = down.id
            var finished = false
            while (!finished) {
                val event = awaitPointerEvent()
                val change = event.changes.fastFirstOrNull { it.id == pointerId }
                when {
                    change == null -> {
                        gestureMachine.onEvent(DockGestureEvent.Cancel)
                            .forEach(::executeDockIntent)
                        finished = true
                    }
                    change.changedToUpIgnoreConsumed() -> {
                        gestureMachine.onEvent(DockGestureEvent.Up)
                            .forEach(::executeDockIntent)
                        finished = true
                    }
                    change.position != change.previousPosition -> {
                        gestureMachine.onEvent(
                            DockGestureEvent.Move(change.position.x),
                        ).forEach(::executeDockIntent)
                    }
                }
            }
        }
    }
    // 外部选中变化（宿主切页）只同步药丸位置；导航由下面的
    // 收集器在 currentIndex 变化时统一发出（宿主回调有守卫，
    // 落到当前栏时是空操作）。
    LaunchedEffect(selectedIndex) {
        currentIndex = selectedIndex
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }
            .drop(1)
            .collectLatest { index ->
                dampedDragAnimation.animateToValue(index.toFloat())
                tabsState.value.getOrNull(index)?.let {
                    onSelectState.value(it.first)
                }
            }
    }

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = DOCK_BOTTOM_GAP),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(dockWidth)
                .height(DOCK_HEIGHT)
                .graphicsLayer {
                    // 胶囊橡皮筋平移 + 按压绽放（泥坛同款上限）
                    translationX = panelOffset
                    val bloom = 1f + 0.05f * dampedDragAnimation.pressProgress
                    scaleX = bloom
                    scaleY = bloom
                },
        ) {
            // ── 外壳：玻璃开走 LIQUID 链 + 对齐边缘；关走实色 ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(
                        elevation = 10.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = surround.shadowColor,
                        spotColor = surround.shadowColor,
                    )
                    .then(
                        if (glass) {
                            Modifier
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { shape },
                                    effects = {
                                        colorControls(saturation = 1.5f)
                                        blur(8.dp.toPx())
                                        lens(
                                            refractionHeight = min(
                                                24.dp.toPx(),
                                                size.minDimension / 2f,
                                            ),
                                            refractionAmount = min(
                                                24.dp.toPx(),
                                                size.minDimension,
                                            ),
                                            chromaticAberration = true,
                                        )
                                    },
                                    highlight = { Highlight(alpha = 0.85f) },
                                    shadow = { null },
                                    onDrawSurface = { drawRect(glassTint) },
                                )
                                // 光晕贴在轮廓外侧：必须在 clip 之前画，
                                // 否则被裁掉（泥坛同款顺序）
                                .drawBehind { drawDockHalo(surround.haloBase) }
                        } else {
                            Modifier.background(scheme.surfaceContainer, shape)
                        },
                    )
                    .clip(shape)
                    .then(
                        if (glass) {
                            Modifier
                                .border(3.dp, dockRimBrush(surround, 0.25f), shape)
                                .border(1.5.dp, dockRimBrush(surround, 1f), shape)
                        } else {
                            Modifier.border(
                                1.dp,
                                if (darkBar) {
                                    Color.White.copy(alpha = 0.10f)
                                } else {
                                    Color.Black.copy(alpha = 0.07f)
                                },
                                shape,
                            )
                        },
                    ),
            )
            // ── 内层条：药丸（透镜）+ 可见行（常显）+ 手势层 ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DOCK_INNER_PADDING),
            ) {
                // 药丸：只采样页面 backdrop（取舍见函数头注释）。
                // 静止时透镜为零、表面就是 v1.2.3 的近黑薄膜，
                // 观感与静态版一致；按压时透镜 10/14 ×
                // pressProgress 放大身后的页面内容。
                val pillModifier = if (glass) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            val p = dampedDragAnimation.pressProgress
                            lens(
                                refractionHeight = 10.dp.toPx() * p,
                                refractionAmount = 14.dp.toPx() * p,
                                chromaticAberration = true,
                            )
                        },
                        highlight = {
                            Highlight(
                                alpha = 0.85f * dampedDragAnimation.pressProgress,
                            )
                        },
                        shadow = {
                            Shadow(alpha = dampedDragAnimation.pressProgress)
                        },
                        innerShadow = {
                            val p = dampedDragAnimation.pressProgress
                            InnerShadow(radius = 8.dp * p, alpha = p)
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val p = dampedDragAnimation.pressProgress
                            drawRect(pillColor, alpha = 1f - p)
                            drawRect(Color.Black.copy(alpha = 0.03f * p))
                        },
                    )
                } else {
                    Modifier.background(pillColor, shape)
                }
                Box(
                    Modifier
                        .width(slotWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            translationX =
                                if (isLtr) {
                                    dampedDragAnimation.value * slotWidthPx
                                } else {
                                    -dampedDragAnimation.value * slotWidthPx
                                }
                            if (!glass) {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                            }
                        }
                        .then(pillModifier),
                )
                // 可见行：图标 + 文字常显（工具箱既有口径），画在
                // 药丸之上保证字形不被透镜/薄膜压沉；条目只带
                // 语义（无指针输入），点按与拖动都由手势层经
                // 手势机统一处理（泥坛 DockItem 同款分工）。
                Row(Modifier.fillMaxSize()) {
                    tabs.forEachIndexed { index, (value, icon, label) ->
                        val progress = (1f - abs(index.toFloat() - dampedDragAnimation.value))
                            .coerceIn(0f, 1f)
                        val contentColor = lerp(idleContent, accent, progress)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(shape)
                                .semantics(mergeDescendants = true) {
                                    role = Role.Tab
                                    selected = tab == value
                                    onClick { currentIndex = index; true }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = contentColor,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .graphicsLayer {
                                            val s = 1f +
                                                0.2f * progress * dampedDragAnimation.pressProgress
                                            scaleX = s
                                            scaleY = s
                                        },
                                )
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    lineHeight = 13.sp,
                                    color = contentColor,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
                // 手势层：盖满内层条，按下即入机（无 touch slop，
                // 点按是零位移拖动），与泥坛的姿势一致。
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(dockGestureModifier),
                )
            }
        }
    }
}

/**
 * 首页：状态总览 + 本机信息 + 重启区（v1.2.1 按已批准效
 * 果图保真重做，v1.2.2 补本机信息卡与大卡跳转）。
 * 顶部渐变状态大卡：激活三态走双信号真值（自检标记或
 * 激活状态文件，见 [ActivationStatus]；旧版 hook 在跑
 * 时是橙色中间态），作用域个数读模块声明的作用域数组；
 * 整卡可点、点一下直接打开 LSPosed 管理器（小乐
 * v1.2.1 点名要的）。其下两张并排小卡（已启用功
 * 能数、工具箱版本）；再下是「本机信息」卡（机型/系统版
 * 本/Android 版本/内核版本，全是真读的系统值）；「重启
 * 作用域」四行收进一张卡、行间细分割线、点行即执行——
 * 全应用唯一的重启入口（右上角按钮与底部弹窗已撤除）；
 * 底部是停用旧模块的提示卡。
 */
@Composable
private fun HomeScreen(
    state: HomeState,
    syncHint: String?,
    activationState: ActivationUiState,
    activationFileVersion: Int?,
    scopeCount: Int,
    bottomInset: Dp,
    onOpenLsposed: () -> Unit,
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
        containerColor = MiuixTheme.colorScheme.surface,
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
                // ── 状态大卡（激活=蓝；旧版 hook 在跑=橙；未激活=灰）──
                val heroBrush = when (activationState) {
                    ActivationUiState.Active -> Brush.linearGradient(
                        listOf(Color(0xFF2F7CF6), Color(0xFF4A9BF5)),
                    )
                    ActivationUiState.ActiveOld -> Brush.linearGradient(
                        listOf(Color(0xFFDD7E06), Color(0xFFF0A13C)),
                    )
                    ActivationUiState.Inactive -> Brush.linearGradient(
                        listOf(Color(0xFF75757A), Color(0xFF55555A)),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(heroBrush)
                        .clickable { onOpenLsposed() },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            text = when (activationState) {
                                ActivationUiState.Active -> "模块运行中"
                                ActivationUiState.ActiveOld -> "已激活 · 旧版运行中"
                                ActivationUiState.Inactive -> "模块未激活"
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = when (activationState) {
                                ActivationUiState.Active ->
                                    "LSPosed 已激活 · 作用域 $scopeCount 个进程"
                                ActivationUiState.ActiveOld ->
                                    "作用域里跑的还是 versionCode ${activationFileVersion ?: "?"}" +
                                        " 的 hook · 重启作用域后更新至本版"
                                ActivationUiState.Inactive ->
                                    "请在 LSPosed 里启用本模块 · 作用域 $scopeCount 个进程"
                            },
                            fontSize = 12.5.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── 两张并排小卡：已启用功能 / 工具箱版本 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                text = "已启用功能",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Row(
                                modifier = Modifier.padding(top = 2.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Text(
                                    text = "$enabledCount",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = " / 4",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                text = "工具箱版本",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = BuildConfig.VERSION_NAME,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }

                // ── 本机信息卡（机型/系统版本/Android/内核，全真值）──
                SmallTitle(
                    text = "本机信息",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    DeviceInfoRow("机型", deviceModelLabel())
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DeviceInfoRow("系统版本", fullSystemVersion())
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DeviceInfoRow(
                        "Android 版本",
                        "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DeviceInfoRow("内核版本", kernelVersion())
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    RestartRow(
                        title = "重启长按导航条唤醒",
                        summary = "和「重启系统界面」是同一个动作，长按唤醒的开关改动靠它生效",
                        onClick = onRestartSystemUi,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    RestartRow(
                        title = "重启桌面",
                        summary = "图标日夜切换的改动靠它生效",
                        onClick = onRestartLauncher,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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

/** 读系统属性（反射 SystemProperties.get），读不到返回 null。 */
private fun systemProp(key: String): String? = runCatching {
    val cls = Class.forName("android.os.SystemProperties")
    cls.getMethod("get", String::class.java).invoke(null, key) as? String
}.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * 机型文案：营销名优先（ro.product.marketname 等，荣耀机
 * 上能读到如 Magic7 Pro），读不到回退 Build.MODEL；两者
 * 不同时并排给出，避免只剩一串型号代码。
 */
private fun deviceModelLabel(): String {
    val market = systemProp("ro.product.marketname")
        ?: systemProp("ro.product.odm.marketname")
        ?: systemProp("ro.config.marketing_name")
    val model = Build.MODEL
    return when {
        market == null || market == model -> model
        else -> "$market（$model）"
    }
}

/**
 * 系统版本完整串：ro.build.display.id 优先（真机形如
 * 11.0.0.117(SP2C00E117R102P1H1)，与系统「关于手机」的
 * 版本号一行同源），读不到回退 Build.DISPLAY 原值。
 */
private fun fullSystemVersion(): String =
    systemProp("ro.build.display.id") ?: Build.DISPLAY

/**
 * 内核版本完整串：/proc/version 首行优先（整串，与系统
 * 「关于手机」的内核版本一行同源），读不到回退
 * os.version 系统属性（短串）。内容只截取不改写。
 */
private fun kernelVersion(): String {
    runCatching { File("/proc/version").readText().trim() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return System.getProperty("os.version")?.takeIf { it.isNotBlank() } ?: "未知"
}

/** 本机信息卡的一行：项名在上、真值在下（值串普遍较长，不挤一行）。 */
@Composable
private fun DeviceInfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            fontSize = 13.5.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 打开 LSPosed 管理器：优先走它的桌面启动 Intent，取不
 * 到就直指其主界面组件，再取不到退到它的应用详情页，
 * 全失败给 Toast。三段都失败的典型原因是管理器被隐藏
 * 或寄生在别的宿主里，此时提示去 LSPosed 寄主里找。
 */
private fun openLsposedManager(context: Context) {
    val pkg = "org.lsposed.manager"
    val launched = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent().apply {
                component = ComponentName(pkg, "org.lsposed.manager.ui.activity.MainActivity")
            }
        context.startActivity(intent)
    }.isSuccess
    if (launched) return
    val detailOpened = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
            },
        )
    }.isSuccess
    if (!detailOpened) {
        Toast.makeText(context, "没找到 LSPosed 管理器", Toast.LENGTH_SHORT).show()
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
    onOpenLibraryPlus: () -> Unit,
) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
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
                    SuperArrow(
                        title = "资源库增强",
                        summary = "隐藏应用、文件夹改名与新建分类、桌面手势、荣耀搜索净化",
                        onClick = onOpenLibraryPlus,
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
        containerColor = MiuixTheme.colorScheme.surface,
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
        containerColor = MiuixTheme.colorScheme.surface,
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
