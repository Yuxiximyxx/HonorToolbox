package io.github.yuxiximyxx.honortoolbox

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.WindowBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 荣耀工具箱单一宿主（v1.1.0 起）：原先「首页 + 快服务/长按唤醒/
 * 通知中心/图标四个设置页 + 关于」共六个 Activity 收成一个
 * Activity 分栏宿主。底部悬浮胶囊栏（miuix 自带
 * FloatingNavigationBar，毛玻璃与选中药丸高亮都是组件自带）
 * 四栏：首页（总索引，右上角重启入口在此栏顶栏）/ 手势与唤醒
 * （快服务手势改向、长按导航条唤醒同栏，栏内先进索引再进各
 * 自的页，长按的选应用页仍是栏内子页）/ 通知中心（主题设置
 * 整页，内部二级页结构不变）/ 桌面（图标日夜切换整页）。
 * 关于不进底栏，仍从首页底部入口进、内容不变。
 *
 * 配置绑定迁移（原各设置页 onCreate 里做的事，现统一在宿主
 * onCreate 做一遍，与逐页打开等价）：
 * 1. 长按唤醒：NavConfig.bind(镜像偏好) + onChanged 落盘回调
 *    （原 NavWakeSettingsActivity.onCreate）；
 * 2. 通知中心主题：makePrefsReadable —— 已有设置文件时先确保
 *    hook 侧（SystemUI 进程）能读到（原 ShadeSettingsActivity
 *    .onCreate）；主题戳记的自愈重写仍在通知中心栏组合时由
 *    ShadeSettingsScreen 自己的 LaunchedEffect 做；
 * 3. 图标日夜切换：NightIconController 初始化（读镜像偏好、
 *    推算状态行；原 NightIconSettingsActivity 的 Activity 级
 *    状态机整体搬进控制器，选主题文件的 startActivityForResult
 *    结果由本 Activity 的 onActivityResult 转交它）；
 * 4. 快服务手势改向原本无 onCreate 绑定（组合时读偏好 + 首组
 *    合落盘自愈），维持原样。
 * LSPosed 入口不变：MODULE_SETTINGS 与桌面 LAUNCHER 仍都指向
 * 本 Activity，进来即直达新宿主。
 */
class MainActivity : ComponentActivity() {

    private val nightIconController by lazy { NightIconController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = 0x00000000 }
        // 配置绑定迁移（见类注释）：长按唤醒绑定 + 通知中心偏好可读
        NavConfig.bind(getSharedPreferences(NavConfig.PREFS_NAME, MODE_PRIVATE))
        NavConfig.onChanged = { NavWakeStore.syncFromPrefs(this) }
        makePrefsReadable(this)
        // 图标日夜切换控制器初始化（lazy 首次访问即完成原 onCreate 的状态推算）
        nightIconController
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
            MiuixTheme(controller = controller) {
                // v1.1.0 拆地雷（双保险）：miuix 弹层组件
                // （WindowBottomSheet 等）内部的 NavigationBackHandler
                // 强制要 NavigationEventDispatcherOwner。activity
                // 1.12.4 起 ComponentActivity 本身即实现该接口并经
                // ViewTree 提供；这里再在组合根部显式提供一份
                // CompositionLocal，保证经 Dialog 承载的底部弹窗
                // 子组合也能拿到（CompositionLocal 会穿过 Dialog
                // 子组合继承下去）。
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides this@MainActivity,
                ) {
                    ToolboxHost(nightIconController = nightIconController)
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

/** 底栏四栏。 */
private enum class ToolboxTab { Home, Gesture, Shade, Desktop }

/** 「手势与唤醒」栏内部页：索引 / 快服务手势改向 / 长按导航条唤醒。 */
private enum class GesturePage { Index, QuickGesture, NavWake }

@Composable
private fun ToolboxHost(nightIconController: NightIconController) {
    var tab by remember { mutableStateOf(ToolboxTab.Home) }
    var gesturePage by remember { mutableStateOf(GesturePage.Index) }
    var showAbout by remember { mutableStateOf(false) }

    // 关于：首页底部入口进的栏外页（自带顶栏与返回），内容不变。
    if (showAbout) {
        BackHandler { showAbout = false }
        AboutScreen(onBack = { showAbout = false })
        return
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        bottomBar = {
            FloatingNavigationBar(
                mode = FloatingNavigationBarDisplayMode.IconAndText,
            ) {
                FloatingNavigationBarItem(
                    selected = tab == ToolboxTab.Home,
                    onClick = { tab = ToolboxTab.Home },
                    icon = MiuixIcons.All,
                    label = "首页",
                )
                FloatingNavigationBarItem(
                    selected = tab == ToolboxTab.Gesture,
                    onClick = { tab = ToolboxTab.Gesture },
                    icon = MiuixIcons.Tune,
                    label = "手势与唤醒",
                )
                FloatingNavigationBarItem(
                    selected = tab == ToolboxTab.Shade,
                    onClick = { tab = ToolboxTab.Shade },
                    icon = MiuixIcons.Theme,
                    label = "通知中心",
                )
                FloatingNavigationBarItem(
                    selected = tab == ToolboxTab.Desktop,
                    onClick = { tab = ToolboxTab.Desktop },
                    icon = MiuixIcons.GridView,
                    label = "桌面",
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                ToolboxTab.Home -> HomeScreen(
                    onOpenQuickGesture = {
                        gesturePage = GesturePage.QuickGesture
                        tab = ToolboxTab.Gesture
                    },
                    onOpenNavWake = {
                        gesturePage = GesturePage.NavWake
                        tab = ToolboxTab.Gesture
                    },
                    onOpenShade = { tab = ToolboxTab.Shade },
                    onOpenNightIcon = { tab = ToolboxTab.Desktop },
                    onOpenAbout = { showAbout = true },
                )
                ToolboxTab.Gesture -> when (gesturePage) {
                    GesturePage.Index -> GestureIndexScreen(
                        onOpenQuickGesture = { gesturePage = GesturePage.QuickGesture },
                        onOpenNavWake = { gesturePage = GesturePage.NavWake },
                    )
                    GesturePage.QuickGesture -> QuickGestureScreen(
                        onExit = { gesturePage = GesturePage.Index },
                    )
                    GesturePage.NavWake -> NavWakeScreen(
                        onBack = { gesturePage = GesturePage.Index },
                    )
                }
                ToolboxTab.Shade -> ShadeSettingsScreen()
                ToolboxTab.Desktop -> NightIconScreen(
                    state = nightIconController.uiState,
                    onBack = null,
                    onEnabledChange = { nightIconController.onEnabledChange(it) },
                    onPickLight = { nightIconController.pick(Keys.Slot.LIGHT) },
                    onPickDark = { nightIconController.pick(Keys.Slot.DARK) },
                    onApply = { nightIconController.applyNow() },
                )
            }
        }
    }
}

/** 「手势与唤醒」栏索引页：两个功能的入口（开关在各自页里，首页栏也有总开关）。 */
@Composable
private fun GestureIndexScreen(
    onOpenQuickGesture: () -> Unit,
    onOpenNavWake: () -> Unit,
) {
    val context = LocalContext.current
    val qgSummary = remember {
        val prefs = context.getSharedPreferences("quickgesture_settings", Context.MODE_PRIVATE)
        val pkg = prefs.getString("package", "") ?: ""
        if (pkg.isEmpty()) {
            "把底角斜滑手势改成打开自选应用"
        } else {
            val label = try {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(ai).toString()
            } catch (t: Throwable) {
                pkg
            }
            "当前目标：$label"
        }
    }
    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = "手势与唤醒",
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
                    .padding(bottom = 24.dp),
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SuperArrow(
                        title = "快服务手势改向",
                        summary = qgSummary,
                        onClick = onOpenQuickGesture,
                    )
                    SuperArrow(
                        title = "长按导航条唤醒",
                        summary = "把长按导航条唤醒 YOYO 改成打开自选应用",
                        onClick = onOpenNavWake,
                    )
                }
            }
        }
    }
}

/** 首页各功能开关的快照（从各自配置通道读，读不到按默认）。 */
private data class HomeState(
    val shadeclockEnabled: Boolean = true,
    val navwakeEnabled: Boolean = true,
    val quickgestureEnabled: Boolean = false,
    val quickgestureSummary: String = "把底角斜滑手势改成打开自选应用",
    val nighticonEnabled: Boolean = false,
)

@Composable
private fun HomeScreen(
    onOpenQuickGesture: () -> Unit,
    onOpenNavWake: () -> Unit,
    onOpenShade: () -> Unit,
    onOpenNightIcon: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(HomeState()) }
    var syncHint by remember { mutableStateOf<String?>(null) }
    var showRestartSheet by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { loadHomeState(context) }
            state = loaded
        }
    }

    // 每次回到首页都重读（各栏可能刚改过）
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
            val ok = withContext(Dispatchers.IO) {
                NavWakeStore.setEnabled(context, enabled)
            }
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

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = "荣耀工具箱",
                color = Color.Transparent,
                actions = {
                    IconButton(onClick = { showRestartSheet = true }) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "重启作用域",
                        )
                    }
                },
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
                    .padding(bottom = 24.dp),
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
                        onCheckedChange = { setQuickgesture(it) },
                        onClick = onOpenQuickGesture,
                    )
                    FeatureRow(
                        title = "长按导航条唤醒",
                        summary = "把长按导航条唤醒 YOYO 改成打开自选应用",
                        checked = state.navwakeEnabled,
                        onCheckedChange = { setNavwake(it) },
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
                        onCheckedChange = { setShadeclock(it) },
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
                        onCheckedChange = { setNighticon(it) },
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
                        syncHint!!,
                        fontSize = 13.sp,
                        color = Color(0xFFE5484D),
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

                Spacer(Modifier.height(16.dp))
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

    // 右上角重启按钮的集合：各宿主的重启/强停动作都收在这里，
    // 各功能页不再各自放按钮（v1.0.1 起集中到首页右上角）。
    //
    // 呈现史：v1.0.1 初版用 miuix WindowBottomSheet，因宿主缺
    // NavigationEventDispatcherOwner 一点就闪退；v1.0.2 临时换
    // Material3 AlertDialog 承载，v1.0.3 又修了它的夜间配色。
    // v1.1.0 地雷已拆（activity-compose 升 1.12.4 +
    // CompositionLocal 显式提供 Owner，见 MainActivity 与构建
    // 脚本注释），本版回归 miuix WindowBottomSheet：底色与行
    // 文字同出 miuix 主题，日间浅底深字、夜间深底浅字，不会
    // 再有 v1.0.2 的白底白字。点按钮只弹窗，点某一行才执行，
    // 点外部/按返回/下滑只关窗；四行文案与执行机制（restart
    // .txt / root 强停）与 v1.0.3 逐字逐行一致。
    // 「重启长按导航条唤醒」与「重启系统界面」共用同一条
    // restart.txt 机制（长按唤醒的 hook 同在 SystemUI 进程），
    // 单列一行只是让人找得到，不新造通道。
    WindowBottomSheet(
        show = showRestartSheet,
        title = "重启作用域",
        onDismissRequest = { showRestartSheet = false },
    ) {
        Column {
            BasicComponent(
                title = "重启系统界面",
                summary = "通知中心主题的改动靠它生效",
                onClick = {
                    showRestartSheet = false
                    restartSystemUi(context, scope)
                },
            )
            BasicComponent(
                title = "重启长按导航条唤醒",
                summary = "和「重启系统界面」是同一个动作，长按唤醒的开关改动靠它生效",
                onClick = {
                    showRestartSheet = false
                    restartSystemUi(context, scope)
                },
            )
            BasicComponent(
                title = "重启桌面",
                summary = "图标日夜切换的改动靠它生效",
                onClick = {
                    showRestartSheet = false
                    restartLauncher(context, scope)
                },
            )
            BasicComponent(
                title = "强停快服务",
                summary = "快服务手势改向首次启用作用域后点它",
                onClick = {
                    showRestartSheet = false
                    forceStopServiceCenter(context, scope)
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
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
 * 「强停快服务」（v1.0.1 新增入口）：快服务手势改向的宿主进程
 * 没有重启令牌通道，机制与「重启桌面」的 root 强停同款；root
 * 不可用时只能提示去系统设置里手动强停。
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

/** 功能行：点行进该功能的栏/页，行尾开关是总开关。 */
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

/** 首页开关写手势改向配置：改镜像偏好 + 落盘文件（保留已选目标）。 */
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
