package io.github.yuxiximyxx.honortoolbox.quickgesture

import io.github.yuxiximyxx.honortoolbox.BuildConfig

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页（MIUIX，v1.0.0）：一页总开关 + 当前目标，选目标走
 * 两级页——先选应用（已安装应用列表、带图标与搜索、有启动
 * 入口的应用排前），进二级页选启动方式（打开应用默认入口 /
 * 该应用的某个 activity，非 exported 的标明可能打不开）。
 *
 * 配置双写：本地 SharedPreferences（UI 状态）+ 落盘文件
 * /sdcard/Download/HonorToolbox/quickgesture.txt（hook 侧通道，
 * 经 ConfigWriter 的 MediaStore 写 + 回读校验）。任一变更
 * 立即双写，hook 侧每次手势命中现读文件，改目标不用强停
 * 快服务、即时生效。写盘失败在页面上明示「同步失败」，
 * 不静默（HonorShadeClock 戳记通道的教训）。
 *
 * v1.1.0：原独立 Activity 已并入工具箱单 Activity 宿主，本页
 * 是「系统」页内的子页（[QuickGestureScreen]），主题与
 * 状态栏图标明暗由宿主统一提供；本页原本没有 onCreate 级
 * 配置绑定（组合时读偏好 + 首组合落盘自愈），维持原样。
 */

private const val PREFS_NAME = "quickgesture_settings"
private const val KEY_ENABLED = "enabled"
private const val KEY_MODE = "mode"
private const val KEY_PACKAGE = "package"
private const val KEY_ACTIVITY = "activity"

private const val PAGE_HOME = "home"
private const val PAGE_APPS = "apps"
private const val PAGE_ACTIVITIES = "activities"

private data class AppEntry(
    val pkg: String,
    val label: String,
    val hasLaunch: Boolean,
)

private data class ActivityEntry(
    val name: String,
    val exported: Boolean,
)

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

/** 应用图标（IO 线程加载、Drawable 转 Bitmap），加载失败返回 null。 */
@Composable
private fun rememberAppIcon(pkg: String): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(pkg)
                val sizePx = 120
                val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
    }
    return icon
}

@Composable
internal fun QuickGestureScreen(onExit: () -> Unit, bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var page by remember { mutableStateOf(PAGE_HOME) }
    BackHandler(enabled = page != PAGE_HOME) {
        page = if (page == PAGE_ACTIVITIES) PAGE_APPS else PAGE_HOME
    }
    // 子页：主页层级按返回 = 回「系统」页总览
    BackHandler(enabled = page == PAGE_HOME) { onExit() }

    var config by remember {
        mutableStateOf(
            GestureConfig(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                mode = prefs.getString(KEY_MODE, GestureConfig.MODE_APP)
                    ?: GestureConfig.MODE_APP,
                pkg = prefs.getString(KEY_PACKAGE, "") ?: "",
                activity = prefs.getString(KEY_ACTIVITY, "") ?: "",
            )
        )
    }
    var syncError by remember { mutableStateOf<String?>(null) }

    /** 任一变更的统一落盘：先存本地偏好，再写 hook 通道文件并校验。 */
    fun persist(next: GestureConfig) {
        config = next
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_MODE, next.mode)
            .putString(KEY_PACKAGE, next.pkg)
            .putString(KEY_ACTIVITY, next.activity)
            .apply()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ConfigWriter.writeConfig(context, next)
            }
            syncError = if (ok) null else "配置同步失败，请再操作一次"
        }
    }

    // 打开设置页时把当前偏好重写一遍通道文件（自愈：装后首开
    // 即落盘，hook 侧读到的与页面显示一致）。
    LaunchedEffect(Unit) {
        val current = config
        val ok = withContext(Dispatchers.IO) {
            ConfigWriter.writeConfig(context, current)
        }
        if (!ok) syncError = "配置同步失败，请再操作一次"
    }

    // 选目标流程的中间状态
    var pendingPkg by remember { mutableStateOf<String?>(null) }
    var appQuery by remember { mutableStateOf("") }
    var appList by remember { mutableStateOf<List<AppEntry>?>(null) }
    var activityList by remember { mutableStateOf<List<ActivityEntry>?>(null) }

    LaunchedEffect(page) {
        if (page == PAGE_APPS && appList == null) {
            appList = withContext(Dispatchers.IO) { loadApps(context) }
        }
        if (page == PAGE_ACTIVITIES) {
            activityList = null
            val pkg = pendingPkg
            if (pkg != null) {
                activityList = withContext(Dispatchers.IO) { loadActivities(context, pkg) }
            }
        }
    }

    val pageTitle = when (page) {
        PAGE_APPS -> "选择应用"
        PAGE_ACTIVITIES -> "选择启动方式"
        else -> "快服务手势改向"
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = pageTitle,
                color = Color.Transparent,
                navigationIcon = {
                    if (page != PAGE_HOME) {
                        BackNavigationIcon {
                            page = if (page == PAGE_ACTIVITIES) PAGE_APPS else PAGE_HOME
                        }
                    } else {
                        BackNavigationIcon { onExit() }
                    }
                },
            )
        },
    ) { innerPadding ->
        when (page) {
            PAGE_APPS -> AppListPage(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                bottomInset = bottomInset,
                apps = appList,
                query = appQuery,
                onQueryChange = { appQuery = it },
                selectedPkg = config.pkg,
                onPick = { entry ->
                    pendingPkg = entry.pkg
                    page = PAGE_ACTIVITIES
                },
            )
            PAGE_ACTIVITIES -> ActivityListPage(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                bottomInset = bottomInset,
                pkg = pendingPkg ?: "",
                activities = activityList,
                current = config,
                onPickAppMode = {
                    val p = pendingPkg ?: return@ActivityListPage
                    persist(
                        config.copy(
                            mode = GestureConfig.MODE_APP, pkg = p, activity = "",
                        )
                    )
                    page = PAGE_HOME
                },
                onPickActivity = { entry ->
                    val p = pendingPkg ?: return@ActivityListPage
                    persist(
                        config.copy(
                            mode = GestureConfig.MODE_ACTIVITY,
                            pkg = p,
                            activity = entry.name,
                        )
                    )
                    page = PAGE_HOME
                },
            )
            else -> HomePage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
                bottomInset = bottomInset,
                config = config,
                syncError = syncError,
                onEnabledChange = { persist(config.copy(enabled = it)) },
                onPickTarget = { page = PAGE_APPS },
                onClearTarget = {
                    persist(config.copy(pkg = "", activity = ""))
                },
            )
        }
    }
}

@Composable
private fun HomePage(
    modifier: Modifier,
    bottomInset: Dp = 0.dp,
    config: GestureConfig,
    syncError: String?,
    onEnabledChange: (Boolean) -> Unit,
    onPickTarget: () -> Unit,
    onClearTarget: () -> Unit,
) {
    val context = LocalContext.current
    val targetLabel = remember(config.pkg) {
        if (config.pkg.isEmpty()) {
            null
        } else {
            try {
                val ai = context.packageManager.getApplicationInfo(config.pkg, 0)
                context.packageManager.getApplicationLabel(ai).toString()
            } catch (t: Throwable) {
                config.pkg
            }
        }
    }
    val targetIcon = if (config.pkg.isNotEmpty()) {
        rememberAppIcon(config.pkg)
    } else {
        null
    }

    Column(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SmallTitle(text = "改向")
            Card(modifier = Modifier.fillMaxWidth()) {
                SuperSwitch(
                    title = "手势改向",
                    summary = "开：从屏幕底角斜上滑将打开下面选好的目标；关：照常打开荣耀快服务",
                    checked = config.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            Spacer(Modifier.height(16.dp))
            SmallTitle(text = "目标")
            Card(modifier = Modifier.fillMaxWidth()) {
                if (config.isConfigured) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (targetIcon != null) {
                            Image(
                                bitmap = targetIcon,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(targetLabel ?: config.pkg, fontSize = 16.sp)
                            Text(
                                if (config.mode == GestureConfig.MODE_ACTIVITY) {
                                    config.activity
                                } else {
                                    "打开应用（默认入口）"
                                },
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                SuperArrow(
                    title = "选择目标",
                    summary = if (config.isConfigured) "换一个应用或 activity" else "未设置",
                    onClick = onPickTarget,
                )
                if (config.isConfigured) {
                    SuperArrow(
                        title = "清除目标",
                        summary = "清除后即使开着改向，手势也照常打开快服务",
                        onClick = onClearTarget,
                    )
                }
            }

            if (syncError != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    syncError,
                    fontSize = 13.sp,
                    color = Color(0xFFE5484D),
                )
            }

            Spacer(Modifier.height(16.dp))
            SmallTitle(text = "使用前")
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "1. 在荣耀快服务里打开「荣耀快服务快捷手势」（快服务的设置 → 手势设置）。\n" +
                        "2. 在 LSPosed 里给荣耀工具箱勾选「荣耀快服务」作用域；然后回工具箱首页，在「重启作用域」里点「强停快服务」让它生效。\n" +
                        "3. 在这里打开改向开关、选好目标。改目标即时生效，不用重启。",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "版本 ${BuildConfig.VERSION_NAME} · 诊断日志在 Download/quickgesture_probe.log",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(24.dp + bottomInset))
        }
    }
}

@Composable
private fun AppListPage(
    modifier: Modifier,
    bottomInset: Dp = 0.dp,
    apps: List<AppEntry>?,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedPkg: String,
    onPick: (AppEntry) -> Unit,
) {
    Column(modifier = modifier) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            label = "搜索应用",
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (apps == null) {
            Text(
                "正在读取应用列表…",
                fontSize = 14.sp,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        val filtered = apps.filter {
            query.isEmpty() ||
                it.label.contains(query, ignoreCase = true) ||
                it.pkg.contains(query, ignoreCase = true)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset),
        ) {
            items(filtered, key = { it.pkg }) { entry ->
                AppRow(entry = entry, selected = entry.pkg == selectedPkg) {
                    onPick(entry)
                }
            }
        }
    }
}

@Composable
private fun AppRow(entry: AppEntry, selected: Boolean, onClick: () -> Unit) {
    val icon = rememberAppIcon(entry.pkg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.label, fontSize = 16.sp)
            Text(
                entry.pkg,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Text(
            when {
                selected -> "当前目标"
                entry.hasLaunch -> "有启动入口"
                else -> "无启动入口"
            },
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ActivityListPage(
    modifier: Modifier,
    bottomInset: Dp = 0.dp,
    pkg: String,
    activities: List<ActivityEntry>?,
    current: GestureConfig,
    onPickAppMode: () -> Unit,
    onPickActivity: (ActivityEntry) -> Unit,
) {
    val context = LocalContext.current
    val launchComponent = remember(pkg) {
        try {
            context.packageManager.getLaunchIntentForPackage(pkg)?.component
        } catch (t: Throwable) {
            null
        }
    }
    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset),
        ) {
            item(key = "app-mode") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPickAppMode)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("打开应用（默认入口）", fontSize = 16.sp)
                        Text(
                            when {
                                launchComponent == null ->
                                    "该应用没有默认入口，选它可能打不开"
                                current.pkg == pkg &&
                                    current.mode == GestureConfig.MODE_APP ->
                                    "当前选择 · ${launchComponent.className}"
                                else -> launchComponent.className
                            },
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            item(key = "title") {
                SmallTitle(
                    text = "指定 activity",
                    insideMargin = PaddingValues(start = 20.dp, top = 8.dp),
                )
            }
            if (activities == null) {
                item(key = "loading") {
                    Text(
                        "正在读取 activity 列表…",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(activities, key = { it.name }) { entry ->
                    val isCurrent = current.pkg == pkg &&
                        current.mode == GestureConfig.MODE_ACTIVITY &&
                        current.activity == entry.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickActivity(entry) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.name.removePrefix("$pkg."),
                                fontSize = 15.sp,
                            )
                            Text(
                                entry.name,
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Text(
                            when {
                                isCurrent -> "当前目标"
                                entry.exported -> "已导出"
                                else -> "未导出，可能打不开"
                            },
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

/** 已安装应用枚举：有启动入口的排前，其余也可进（二级页里再选 activity）。 */
private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val out = ArrayList<AppEntry>()
    try {
        for (ai in pm.getInstalledApplications(0)) {
            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (t: Throwable) {
                ai.packageName
            }
            val hasLaunch = try {
                pm.getLaunchIntentForPackage(ai.packageName) != null
            } catch (t: Throwable) {
                false
            }
            out.add(AppEntry(ai.packageName, label, hasLaunch))
        }
    } catch (t: Throwable) {
        // 枚举失败返回已得部分
    }
    return out.sortedWith(
        compareByDescending<AppEntry> { it.hasLaunch }
            .thenBy { it.label.lowercase() }
    )
}

/** 某应用声明的全部 activity（名 + exported）。 */
private fun loadActivities(context: Context, pkg: String): List<ActivityEntry> {
    val pm = context.packageManager
    return try {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
        info.activities
            ?.map { ActivityEntry(it.name, it.exported) }
            ?.sortedBy { it.name }
            ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }
}
