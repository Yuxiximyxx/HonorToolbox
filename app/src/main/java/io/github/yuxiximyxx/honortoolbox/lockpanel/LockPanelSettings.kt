package io.github.yuxiximyxx.honortoolbox.lockpanel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.LocalLpBottomInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.Collator
import java.util.Locale

/**
 * 「锁屏快捷面板」设置子页（v1.9.0 合入 LockPanel，作者
 * chaosz）：原 SettingsActivity 的设置项全量搬到这里用
 * MIUIX 重排，只动呈现——13 个配置键的键名、默认值、取值
 * 格式与读写通道逐字沿用移植后的 [Cfg]（镜像偏好
 * lockpanel_settings + [LockPanelConfigProvider] 直供
 * SystemUI 进程，与原模块 prefs+CfgProvider 同构）。
 *
 * 页面结构：主页（总开关 / 面板可用性三档 / 隐藏切换样式 /
 * 5 个槽位行 / 恢复默认）→ 槽位编辑（动作单选列表 + 图标
 * 入口）→ 图标选择（搜索 + 原厂图标目录）、应用选择
 * （搜索 + 已安装应用）、指定 Activity（包名 + 类名输入，
 * 保存口径与原版对话框一致）。
 *
 * 与原版的差异：原独立版配置不迁移——镜像偏好从空开始，
 * 首次打开子页时按原默认值整份播种（[Cfg.resetAll] 的写集
 * 即全默认）；原版的「重启系统界面」按钮不另造，工具箱首
 * 页已有同能力入口，子页底部只留一行说明。
 */

private val LP_DEFAULT_SLOT_NAMES = arrayOf("录音机", "计算器", "手电筒", "时钟", "扫描")

/** 槽位未自定义图标时 hook 侧的回退图标（与 PanelHooks 的 DEFAULT_ICONS 一致，仅用于设置页预览）。 */
private val LP_DEFAULT_ICONS = arrayOf(
    "icsvg_public_recorder",
    "icsvg_hnunlock_calculator",
    "icsvg_public_flashlight_off_filled",
    "icsvg_public_timercircle_filled",
    "icsvg_public_hivision",
)

private const val LP_SEED_FLAG = "lockpanel_seeded"

private sealed interface LpPage {
    data object Home : LpPage
    data class Slot(val index: Int) : LpPage
    data class Icons(val index: Int) : LpPage
    data class Apps(val index: Int) : LpPage
    data class Act(val index: Int) : LpPage
}

private data class LpAppEntry(val label: String, val pkg: String, val cls: String)

@Composable
internal fun LockPanelScreen(onBack: () -> Unit, bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    var page by remember { mutableStateOf<LpPage>(LpPage.Home) }
    var tick by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { tick++ }
    var resetAsk by remember { mutableStateOf(false) }
    var appList by remember { mutableStateOf<List<LpAppEntry>?>(null) }

    // 首次打开播种：镜像里还没有播种标记时，把 13 个键按原
    // 默认值整份写入（resetAll 的写集即全默认 + 总开关关），
    // 之后 provider 供出的就是页面显示的这份，不再是缺省回落。
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(Cfg.PREFS_FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(LP_SEED_FLAG, false)) {
            withContext(Dispatchers.IO) { Cfg.resetAll(context) }
            prefs.edit().putBoolean(LP_SEED_FLAG, true).apply()
            bump()
        }
    }
    LaunchedEffect(page) {
        if (page is LpPage.Apps && appList == null) {
            appList = withContext(Dispatchers.IO) { loadLpApps(context) }
        }
    }

    BackHandler(enabled = page != LpPage.Home) {
        page = when (val p = page) {
            is LpPage.Slot -> LpPage.Home
            is LpPage.Icons -> LpPage.Slot(p.index)
            is LpPage.Apps -> LpPage.Slot(p.index)
            is LpPage.Act -> LpPage.Slot(p.index)
            LpPage.Home -> LpPage.Home
        }
    }
    BackHandler(enabled = page == LpPage.Home) { onBack() }

    val title = when (val p = page) {
        LpPage.Home -> "锁屏快捷面板"
        is LpPage.Slot -> "槽位 ${p.index + 1}"
        is LpPage.Icons -> "选择图标"
        is LpPage.Apps -> "选择应用"
        is LpPage.Act -> "指定 Activity"
    }

    CompositionLocalProvider(LocalLpBottomInset provides bottomInset) {
        Scaffold(
            containerColor = MiuixTheme.colorScheme.surface,
            topBar = {
                SmallTopAppBar(
                    title = title,
                    color = Color.Transparent,
                    navigationIcon = {
                        IconButton(onClick = {
                            if (page == LpPage.Home) {
                                onBack()
                            } else {
                                page = when (val p = page) {
                                    is LpPage.Slot -> LpPage.Home
                                    is LpPage.Icons -> LpPage.Slot(p.index)
                                    is LpPage.Apps -> LpPage.Slot(p.index)
                                    is LpPage.Act -> LpPage.Slot(p.index)
                                    LpPage.Home -> LpPage.Home
                                }
                            }
                        }) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = null)
                        }
                    },
                )
            },
        ) { innerPadding ->
            @Suppress("UNUSED_EXPRESSION") tick
            when (val p = page) {
                LpPage.Home -> LpHomePage(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    bottomInset = bottomInset,
                    bump = bump,
                    onOpenSlot = { page = LpPage.Slot(it) },
                    onResetAsk = { resetAsk = true },
                )
                is LpPage.Slot -> LpSlotPage(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    bottomInset = bottomInset,
                    slot = p.index,
                    bump = bump,
                    onOpenIcons = { page = LpPage.Icons(p.index) },
                    onOpenApps = { page = LpPage.Apps(p.index) },
                    onOpenAct = { page = LpPage.Act(p.index) },
                )
                is LpPage.Icons -> LpIconPage(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    bottomInset = bottomInset,
                    slot = p.index,
                    onPicked = { page = LpPage.Slot(p.index); bump() },
                )
                is LpPage.Apps -> LpAppPage(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    bottomInset = bottomInset,
                    slot = p.index,
                    apps = appList,
                    onPicked = { page = LpPage.Slot(p.index); bump() },
                )
                is LpPage.Act -> LpActPage(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    bottomInset = bottomInset,
                    slot = p.index,
                    onSaved = { page = LpPage.Slot(p.index); bump() },
                )
            }
            SuperDialog(
                show = resetAsk,
                title = "恢复默认",
                onDismissRequest = { resetAsk = false },
                // 弹窗自身底部 margin 抬过悬浮底栏（只动弹窗、不动页面与沉浸）
                outsideMargin = DpSize(12.dp, LocalLpBottomInset.current + 12.dp),
            ) {
                Text(
                    "全部恢复默认并关闭总开关？面板可用性回到跟随系统，5 个槽位的动作与图标都恢复原厂。",
                    modifier = Modifier.padding(bottom = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "取消",
                        onClick = { resetAsk = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = "恢复",
                        onClick = {
                            Cfg.resetAll(context)
                            resetAsk = false
                            bump()
                            Toast.makeText(
                                context,
                                "已恢复默认（关闭总开关、面板可用性跟随系统），立即回到原厂行为",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LpHomePage(
    modifier: Modifier,
    bottomInset: Dp,
    bump: () -> Unit,
    onOpenSlot: (Int) -> Unit,
    onResetAsk: () -> Unit,
) {
    val context = LocalContext.current
    val master = Cfg.read(context, Cfg.K_MASTER)
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                SuperSwitch(
                    title = "总开关",
                    summary = "开启后快捷面板由工具箱接管，关掉即完全交还给系统",
                    checked = master,
                    onCheckedChange = { Cfg.write(context, Cfg.K_MASTER, it); bump() },
                )
            }

            Column(modifier = Modifier.alpha(if (master) 1f else 0.4f)) {
                SmallTitle(
                    text = "面板可用性",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Text(
                    "控制面板能不能被划出",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    val mode = Cfg.readStr(context, Cfg.K_PANEL, Cfg.MODE_FOLLOW)
                    LpOptionRow(
                        title = "跟随系统",
                        summary = "原厂默认是什么样就是什么样",
                        selected = mode == Cfg.MODE_FOLLOW,
                        enabled = master,
                        onClick = { Cfg.writeStr(context, Cfg.K_PANEL, Cfg.MODE_FOLLOW); bump() },
                    )
                    LpOptionRow(
                        title = "强制允许",
                        summary = "能划出来，荣耀 Magic7 系列或更早机型默认设置",
                        selected = mode == Cfg.MODE_ON,
                        enabled = master,
                        onClick = { Cfg.writeStr(context, Cfg.K_PANEL, Cfg.MODE_ON); bump() },
                    )
                    LpOptionRow(
                        title = "强制禁止",
                        summary = "划不出来，荣耀 Magic8 系列或更新机型默认设置",
                        selected = mode == Cfg.MODE_OFF,
                        enabled = master,
                        onClick = { Cfg.writeStr(context, Cfg.K_PANEL, Cfg.MODE_OFF); bump() },
                    )
                }

                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    SuperSwitch(
                        title = "隐藏「换壁纸 / 开启杂志锁屏」文字",
                        summary = "锁屏上划后只保留图标",
                        checked = Cfg.read(context, Cfg.K_HIDE_STYLE),
                        onCheckedChange = { Cfg.write(context, Cfg.K_HIDE_STYLE, it); bump() },
                        enabled = master,
                    )
                }

                SmallTitle(
                    text = "快捷槽位",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Text(
                    "可以分别自定义每个槽位的点击动作与显示图标，二者相互独立配置。默认均为系统原厂行为。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    for (i in 0 until 5) {
                        LpSlotRow(
                            slot = i,
                            enabled = master,
                            onClick = { onOpenSlot(i) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                SuperArrow(
                    title = "全部恢复默认并关闭总开关",
                    onClick = onResetAsk,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "修改即时生效，不需要重启，回锁屏划一下就能看到效果。如需重启系统界面，用工具箱首页的「重启系统界面」。\n" +
                    "原独立版 LockPanel 的配置不迁移，这里是按原默认值重新播种的一份；装好本版后请停用独立版，避免双重 hook。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(24.dp + bottomInset))
        }
    }
}

@Composable
private fun LpSlotRow(slot: Int, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val action = Cfg.readStr(context, Cfg.ACTION_KEYS[slot], "")
    val iconName = Cfg.readStr(context, Cfg.ICON_KEYS[slot], "")
    val preview = rememberLpIconBitmap(
        if (iconName.isEmpty()) LP_DEFAULT_ICONS[slot] else iconName,
    )
    val iconLabel = if (iconName.isEmpty()) {
        "原厂 · " + LP_DEFAULT_SLOT_NAMES[slot]
    } else {
        val item = Icons.byName(iconName)
        if (item != null) item.label + " · " + iconName else iconName
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("槽位 ${slot + 1} · ${LP_DEFAULT_SLOT_NAMES[slot]}", fontSize = 15.sp)
            Text(
                "动作：" + Cfg.actionLabel(action, LP_DEFAULT_SLOT_NAMES[slot]),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                "图标：$iconLabel",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Text(
            "›",
            fontSize = 16.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun LpSlotPage(
    modifier: Modifier,
    bottomInset: Dp,
    slot: Int,
    bump: () -> Unit,
    onOpenIcons: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenAct: () -> Unit,
) {
    val context = LocalContext.current
    val current = Cfg.readStr(context, Cfg.ACTION_KEYS[slot], "")
    val iconName = Cfg.readStr(context, Cfg.ICON_KEYS[slot], "")
    val iconPreview = rememberLpIconBitmap(
        if (iconName.isEmpty()) LP_DEFAULT_ICONS[slot] else iconName,
    )
    fun setAction(value: String) {
        Cfg.writeStr(context, Cfg.ACTION_KEYS[slot], value)
        bump()
    }
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SmallTitle(text = "图标", insideMargin = PaddingValues(start = 4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenIcons)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        if (iconPreview != null) {
                            Image(
                                bitmap = iconPreview,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("槽位图标", fontSize = 15.sp)
                        Text(
                            if (iconName.isEmpty()) {
                                "原厂 · " + LP_DEFAULT_SLOT_NAMES[slot]
                            } else {
                                val item = Icons.byName(iconName)
                                if (item != null) item.label + " · " + iconName else iconName
                            },
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Text(
                        "›",
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            SmallTitle(
                text = "动作",
                insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                LpOptionRow(
                    title = "默认（原厂：${LP_DEFAULT_SLOT_NAMES[slot]}）",
                    summary = "恢复该槽位原本的锁屏快捷动作",
                    selected = current.isEmpty(),
                    onClick = { setAction("") },
                )
            }
            SmallTitle(
                text = "免解锁的动作",
                insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                LpOptionRow(
                    title = "手电筒",
                    summary = "原地开关手电筒",
                    selected = current == Cfg.ACT_FLASHLIGHT,
                    onClick = { setAction(Cfg.ACT_FLASHLIGHT) },
                )
                LpOptionRow(
                    title = "相机",
                    summary = "免解锁直接打开相机，效果和右下角那个按钮相同",
                    selected = current == Cfg.ACT_CAMERA,
                    onClick = { setAction(Cfg.ACT_CAMERA) },
                )
                LpOptionRow(
                    title = "录音机",
                    summary = "免解锁直接打开录音机",
                    selected = current == Cfg.ACT_RECORDER,
                    onClick = { setAction(Cfg.ACT_RECORDER) },
                )
                LpOptionRow(
                    title = "计算器",
                    summary = "免解锁直接打开计算器",
                    selected = current == Cfg.ACT_CALCULATOR,
                    onClick = { setAction(Cfg.ACT_CALCULATOR) },
                )
                LpOptionRow(
                    title = "时钟",
                    summary = "免解锁直接打开时钟",
                    selected = current == Cfg.ACT_CLOCK,
                    onClick = { setAction(Cfg.ACT_CLOCK) },
                )
                LpOptionRow(
                    title = "扫描",
                    summary = "免解锁直接打开智能识物 / 扫一扫",
                    selected = current == Cfg.ACT_SCANNER,
                    onClick = { setAction(Cfg.ACT_SCANNER) },
                )
            }
            SmallTitle(
                text = "需要解锁的动作，若已解锁则直达",
                insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                val isApp = current.startsWith(Cfg.PREFIX_APP)
                val isAct = current.startsWith(Cfg.PREFIX_ACT)
                LpOptionRow(
                    title = "启动应用…",
                    summary = if (isApp) {
                        "当前：" + Cfg.actionLabel(current, LP_DEFAULT_SLOT_NAMES[slot]) + "（点击锁屏弹出解锁认证）"
                    } else {
                        "从已安装应用中选择，锁屏点击时弹出密码或指纹认证"
                    },
                    selected = isApp,
                    onClick = onOpenApps,
                )
                LpOptionRow(
                    title = "指定 Activity…",
                    summary = if (isAct) {
                        "当前：" + Cfg.actionLabel(current, LP_DEFAULT_SLOT_NAMES[slot]) + "（点击锁屏弹出解锁认证）"
                    } else {
                        "手动输入包名与类名，锁屏点击时弹出密码或指纹认证"
                    },
                    selected = isAct,
                    onClick = onOpenAct,
                )
            }
            Spacer(Modifier.height(24.dp + bottomInset))
        }
    }
}

@Composable
private fun LpIconPage(
    modifier: Modifier,
    bottomInset: Dp,
    slot: Int,
    onPicked: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val current = Cfg.readStr(context, Cfg.ICON_KEYS[slot], "")
    val all = remember { Icons.all() }
    val filtered = all.filter {
        query.isEmpty() ||
            it.label.contains(query, ignoreCase = true) ||
            it.name.contains(query, ignoreCase = true) ||
            it.group.contains(query, ignoreCase = true)
    }
    Column(modifier = modifier) {
        TextField(
            value = query,
            onValueChange = { query = it },
            label = "搜索图标名或分类",
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
            item(key = "factory") {
                LpIconRow(
                    name = null,
                    title = "默认（原厂图标）",
                    summary = "恢复这个槽位本来的样子",
                    selected = current.isEmpty(),
                    onClick = {
                        Cfg.writeStr(context, Cfg.ICON_KEYS[slot], "")
                        onPicked()
                    },
                )
            }
            items(filtered, key = { it.name }) { item ->
                LpIconRow(
                    name = item.name,
                    title = item.label,
                    summary = item.group + " · " + item.name,
                    selected = current == item.name,
                    onClick = {
                        Cfg.writeStr(context, Cfg.ICON_KEYS[slot], item.name)
                        onPicked()
                    },
                )
            }
        }
    }
}

@Composable
private fun LpIconRow(
    name: String?,
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val preview = rememberLpIconBitmap(name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(
                summary,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (selected) {
            Text(
                "✓",
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LpAppPage(
    modifier: Modifier,
    bottomInset: Dp,
    slot: Int,
    apps: List<LpAppEntry>?,
    onPicked: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    Column(modifier = modifier) {
        TextField(
            value = query,
            onValueChange = { query = it },
            label = if (apps == null) "正在加载应用列表…" else "搜索应用名或包名",
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
            items(filtered, key = { it.pkg + "/" + it.cls }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            Cfg.writeStr(
                                context,
                                Cfg.ACTION_KEYS[slot],
                                Cfg.PREFIX_APP + entry.pkg + "/" + entry.cls + "\t" + entry.label,
                            )
                            onPicked()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.label, fontSize = 15.sp)
                        Text(
                            entry.pkg,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LpActPage(
    modifier: Modifier,
    bottomInset: Dp,
    slot: Int,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    // 预填解析与原版指定 Activity 对话框一致：剥 act:/app:
    // 前缀与 \t 后的标签，再按第一个 / 拆包名与类名。
    val initial = remember {
        val cur = Cfg.readStr(context, Cfg.ACTION_KEYS[slot], "")
        var pkg = ""
        var cls = ""
        if (cur.startsWith(Cfg.PREFIX_ACT) || cur.startsWith(Cfg.PREFIX_APP)) {
            var rest = cur.substring(Cfg.PREFIX_ACT.length)
            val tab = rest.indexOf('\t')
            if (tab >= 0) rest = rest.substring(0, tab)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                pkg = rest.substring(0, slash)
                cls = rest.substring(slash + 1)
            } else {
                pkg = rest
            }
        }
        pkg to cls
    }
    var pkgText by remember { mutableStateOf(initial.first) }
    var clsText by remember { mutableStateOf(initial.second) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TextField(
                        value = pkgText,
                        onValueChange = { pkgText = it; error = null },
                        label = "包名（必填），如 com.tencent.mm",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = clsText,
                        onValueChange = { clsText = it; error = null },
                        label = "Activity 类名（可留空，或完整类名 / .简写）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error!!,
                            fontSize = 13.sp,
                            color = Color(0xFFE5484D),
                        )
                    }
                    TextButton(
                        text = "保存",
                        onClick = {
                            val pkg = pkgText.trim()
                            var cls = clsText.trim()
                            if (pkg.isEmpty()) {
                                error = "包名不能为空"
                            } else {
                                if (cls.startsWith(".")) cls = pkg + cls
                                Cfg.writeStr(
                                    context,
                                    Cfg.ACTION_KEYS[slot],
                                    Cfg.PREFIX_ACT + pkg + if (cls.isEmpty()) "" else "/" + cls,
                                )
                                onSaved()
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp + bottomInset))
        }
    }
}

/** 单选行（面板模式与动作列表共用）：标题 + 说明 + 选中勾。 */
@Composable
private fun LpOptionRow(
    title: String,
    summary: String?,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            )
            if (!summary.isNullOrEmpty()) {
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (selected) {
            Text(
                "✓",
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** 图标预览（IO 线程从 androidhnext 资源包取 Drawable 转 Bitmap，取不到返回 null）。 */
@Composable
private fun rememberLpIconBitmap(name: String?): ImageBitmap? {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) {
            if (name.isNullOrEmpty()) {
                null
            } else {
                try {
                    val d = Icons.drawable(context, name) ?: return@withContext null
                    val sizePx = 120
                    val bm = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bm)
                    d.setBounds(0, 0, sizePx, sizePx)
                    d.draw(canvas)
                    bm.asImageBitmap()
                } catch (t: Throwable) {
                    null
                }
            }
        }
    }
    return bmp
}

/** 已安装应用枚举（与原版同口径：MAIN+LAUNCHER 入口逐条，中文排序按标签再包名）。 */
private fun loadLpApps(context: Context): List<LpAppEntry> {
    val pm = context.packageManager
    val out = ArrayList<LpAppEntry>()
    try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
        for (ri in list) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            val cls = ai.name ?: ""
            val label = ri.loadLabel(pm)?.toString()?.takeIf { it.isNotEmpty() } ?: pkg
            out.add(LpAppEntry(label, pkg, cls))
        }
        val collator = Collator.getInstance(Locale.CHINA)
        out.sortWith { a, b ->
            val c = collator.compare(a.label, b.label)
            if (c != 0) c else a.pkg.compareTo(b.pkg)
        }
    } catch (t: Throwable) {
        // 枚举失败返回已得部分
    }
    return out
}
