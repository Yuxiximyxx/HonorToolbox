package io.github.yuxiximyxx.honortoolbox

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
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
import io.github.yuxiximyxx.honortoolbox.navwake.NavWakeSettingsActivity
import io.github.yuxiximyxx.honortoolbox.navwake.NavWakeStore
import io.github.yuxiximyxx.honortoolbox.nighticon.NightIconConfig
import io.github.yuxiximyxx.honortoolbox.nighticon.NightIconSettingsActivity
import io.github.yuxiximyxx.honortoolbox.nighticon.NightIconStore
import io.github.yuxiximyxx.honortoolbox.quickgesture.ConfigWriter
import io.github.yuxiximyxx.honortoolbox.quickgesture.GestureConfig
import io.github.yuxiximyxx.honortoolbox.quickgesture.QuickGestureSettingsActivity
import io.github.yuxiximyxx.honortoolbox.shadeclock.ShadeSettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 荣耀工具箱首页：按使用场景分组的索引（手势与唤醒 / 通知
 * 中心 / 桌面 / 耳机），不按来源模块组织。每行一个功能：
 * 点行进该功能的设置页，行尾开关是该功能的总开关（与各自
 * 设置页里的开关同一份配置）。第二阶段的功能先占位。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = 0x00000000 }
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
            MiuixTheme(controller = controller) { HomeScreen() }
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
private fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(HomeState()) }
    var syncHint by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { loadHomeState(context) }
            state = loaded
        }
    }

    // 每次回到首页都重读（各设置页可能刚改过）
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
                        onClick = {
                            context.startActivity(
                                Intent(context, QuickGestureSettingsActivity::class.java)
                            )
                        },
                    )
                    FeatureRow(
                        title = "长按导航条唤醒",
                        summary = "把长按导航条唤醒 YOYO 改成打开自选应用",
                        checked = state.navwakeEnabled,
                        onCheckedChange = { setNavwake(it) },
                        onClick = {
                            context.startActivity(
                                Intent(context, NavWakeSettingsActivity::class.java)
                            )
                        },
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
                        onClick = {
                            context.startActivity(
                                Intent(context, ShadeSettingsActivity::class.java)
                            )
                        },
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
                        onClick = {
                            context.startActivity(
                                Intent(context, NightIconSettingsActivity::class.java)
                            )
                        },
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
                        onClick = {
                            context.startActivity(Intent(context, AboutActivity::class.java))
                        },
                    )
                }
            }
        }
    }
}

/** 功能行：点行进设置页，行尾开关是总开关。 */
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
