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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
 *
 * v1.6.1：「选择启动方式」页加手动输入（包名 + 活动全名，
 * 保存走同一条 persist 通道；保存前软校验——应用不存在或
 * 类名不在已声明活动里只提示、允许强制保存，类名为空不许
 * 保存）与 activity 搜索框。活动列表一直按 GET_ACTIVITIES
 * 全量枚举，但上百个活动无搜索时翻不到：如荣耀搜索首页
 * HomeActivity 只挂 ACTION_VIEW 深链入口、无 LAUNCHER
 * 入口，小乐真机反馈列表里找不到——手动输入就是给这类
 * 活动留的直达口。
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
    // 通道状态行（v1.6.5）：最近一次保存的本地结果——文件与
    // Settings.Global 两条写通道各自动作如何，页面上直接可
    // 见，下轮排查不用再导日志（hook 侧实际读哪条由其 diag
    // 的 source 字段记，provider 通道不需要工具箱写什么）。
    var channelLine by remember { mutableStateOf<String?>(null) }

    /** 一次写通道的结果统一回填：错误提示 + 通道状态行。 */
    fun applyWriteResult(ok: Boolean) {
        syncError = if (ok) null else "配置同步失败，请再操作一次"
        channelLine = if (ok) {
            "配置已保存 · 文件通道：已写入 · 系统设置项：" +
                if (ConfigWriter.lastGlobalSyncOk == true) "已同步" else "未同步"
        } else {
            "配置未保存 · 文件通道：写入失败"
        }
    }

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
            applyWriteResult(ok)
        }
    }

    // 打开设置页时把当前偏好重写一遍通道文件（自愈：装后首开
    // 即落盘，hook 侧读到的与页面显示一致）。
    LaunchedEffect(Unit) {
        val current = config
        val ok = withContext(Dispatchers.IO) {
            ConfigWriter.writeConfig(context, current)
        }
        applyWriteResult(ok)
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
        containerColor = MiuixTheme.colorScheme.surface,
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
                onSaveManual = { p, a ->
                    persist(
                        config.copy(
                            mode = GestureConfig.MODE_ACTIVITY,
                            pkg = p,
                            activity = a,
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
                channelLine = channelLine,
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
    channelLine: String?,
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

            if (channelLine != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    channelLine,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                "版本 ${BuildConfig.VERSION_NAME} · 诊断日志在 Download/HonorToolbox/quickgesture.log",
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
    onSaveManual: (pkg: String, activity: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launchComponent = remember(pkg) {
        try {
            context.packageManager.getLaunchIntentForPackage(pkg)?.component
        } catch (t: Throwable) {
            null
        }
    }

    var query by remember(pkg) { mutableStateOf("") }

    // 手动输入（v1.6.1）：包名预填当前所选应用、类名预填当前
    // 目标（同包同模式时）；校验只提示不硬拦，警告未消时按钮
    // 变「仍要保存」，再点一次强制落盘。输入一变就清警告。
    var manualPkg by remember(pkg) { mutableStateOf(pkg) }
    var manualActivity by remember(pkg) {
        mutableStateOf(
            if (current.pkg == pkg &&
                current.mode == GestureConfig.MODE_ACTIVITY
            ) {
                current.activity
            } else {
                ""
            }
        )
    }
    var manualError by remember(pkg) { mutableStateOf<String?>(null) }
    var manualWarning by remember(pkg) { mutableStateOf<String?>(null) }

    fun attemptSave(force: Boolean) {
        val p = manualPkg.trim()
        val raw = manualActivity.trim()
        if (p.isEmpty()) {
            manualError = "包名不能为空"
            return
        }
        if (raw.isEmpty()) {
            manualError = "活动类名不能为空"
            return
        }
        manualError = null
        val a = expandActivityName(p, raw)
        scope.launch {
            val warn = withContext(Dispatchers.IO) {
                validateManualTarget(context, p, a)
            }
            if (warn != null && !force) {
                manualWarning = warn
            } else {
                manualWarning = null
                onSaveManual(p, a)
            }
        }
    }

    val filtered = activities?.filter {
        query.isEmpty() || it.name.contains(query, ignoreCase = true)
    }

    Column(modifier = modifier) {
        TextField(
            value = query,
            onValueChange = { query = it },
            label = "搜索 activity",
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
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
            item(key = "manual") {
                Column {
                    SmallTitle(
                        text = "手动输入",
                        insideMargin = PaddingValues(start = 20.dp, top = 8.dp),
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = 16.dp, vertical = 12.dp,
                            ),
                        ) {
                            TextField(
                                value = manualPkg,
                                onValueChange = {
                                    manualPkg = it
                                    manualWarning = null
                                },
                                label = "包名",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            TextField(
                                value = manualActivity,
                                onValueChange = {
                                    manualActivity = it
                                    manualWarning = null
                                },
                                label = "活动类名（全名）",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (manualError != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    manualError!!,
                                    fontSize = 13.sp,
                                    color = Color(0xFFE5484D),
                                )
                            }
                            if (manualWarning != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    manualWarning!!,
                                    fontSize = 13.sp,
                                    color = Color(0xFFE5484D),
                                )
                            }
                            Button(
                                onClick = {
                                    attemptSave(force = manualWarning != null)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                                    .height(48.dp),
                                cornerRadius = 14.dp,
                                minHeight = 48.dp,
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Text(
                                    if (manualWarning != null) "仍要保存" else "保存",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                )
                            }
                            Text(
                                "列表里找不到的活动可直接填全名保存；校验不通过只提示、不拦保存。",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
            item(key = "title") {
                SmallTitle(
                    text = "指定 activity",
                    insideMargin = PaddingValues(start = 20.dp, top = 8.dp),
                )
            }
            if (filtered == null) {
                item(key = "loading") {
                    Text(
                        "正在读取 activity 列表…",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "没有匹配的 activity，可用上方手动输入直接填全名",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(filtered, key = { it.name }) { entry ->
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

/** 手动输入的类名展开：".Foo" 与裸 "Foo" 按包名补全，全名原样。 */
private fun expandActivityName(pkg: String, raw: String): String = when {
    raw.startsWith(".") -> pkg + raw
    raw.contains(".") -> raw
    else -> "$pkg.$raw"
}

/**
 * 手动目标保存前的软校验：有问题返回提示文案、没问题返回
 * null。只提示不拦——清单口径与可启动性并不完全等价
 * （查不到不等于启动不了），最终由用户点「仍要保存」拍板。
 */
private fun validateManualTarget(
    context: Context,
    pkg: String,
    activity: String,
): String? {
    val pm = context.packageManager
    val info = try {
        pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
    } catch (t: Throwable) {
        return "找不到包名为 $pkg 的应用（可能未安装或包名写错），保存后可能打不开"
    }
    val entry = info.activities?.firstOrNull { it.name == activity }
    return when {
        entry == null ->
            "该应用已声明的活动里没有 $activity，保存后可能打不开"
        !entry.exported ->
            "该活动未导出，保存后可能打不开"
        else -> null
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
