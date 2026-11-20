package io.github.yuxiximyxx.honortoolbox.nighticon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.yuxiximyxx.honortoolbox.R
import java.io.File
import kotlin.concurrent.thread
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 状态行圆点的语义：就绪为绿，进行中为主色，其余为灰。 */
enum class StatusTone { Ready, Busy, Idle }

data class NightIconUiState(
    val enabled: Boolean = false,
    val lightName: String = "",
    val darkName: String = "",
    val status: String = "",
    val tone: StatusTone = StatusTone.Idle,
)

/**
 * 「桌面 · 图标日夜切换」设置页（原 NightIcon 设置页的功能项
 * 按工具箱统一 MIUIX 呈现重排，设置项/默认值不变）：
 * 总开关、白天/夜间主题选择、状态、立即应用（「重启桌面」
 * 自工具箱 v1.0.1 起集中到工具箱首页右上角，本页只留提示）。
 * 与原版的差别只在通道：下发由 libxposed 远程偏好/远程文件
 * 改为落盘 /sdcard/Download/HonorToolbox/nighticon/（见
 * [NightIconStore]）；本地展示镜像偏好改名 nighticon_settings
 * （原版叫 settings，与工具箱内其他功能的镜像键冲突）。
 * 原版的「关于」行并入工具箱统一关于页，本页不再单列。
 */
class NightIconSettingsActivity : ComponentActivity() {

    private val TAG = "NightIcon"
    private val local by lazy { getSharedPreferences("nighticon_settings", Context.MODE_PRIVATE) }

    /** 临时状态（覆盖默认状态文字），null = 按配置推算 */
    private var transientStatus: String? = null
    private var uiState by mutableStateOf(NightIconUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = 0x00000000 }
        uiState = uiState.copy(enabled = local.getBoolean(Keys.ENABLED, false))
        render()
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
            val controller = androidx.compose.runtime.remember(dark) {
                ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
            }
            MiuixTheme(controller = controller) {
                NightIconScreen(
                    state = uiState,
                    onBack = { finish() },
                    onEnabledChange = { checked ->
                        local.edit().putBoolean(Keys.ENABLED, checked).apply()
                        uiState = uiState.copy(enabled = checked)
                        transientStatus = null
                        render()
                        // 总开关同步进落盘配置（保留版本号/令牌）
                        thread(name = "nighticon-enabled") {
                            val cur = NightIconStore.readState(this@NightIconSettingsActivity)
                                ?: stateFromMirror()
                            NightIconStore.writeConfig(
                                this@NightIconSettingsActivity,
                                cur.copy(enabled = checked),
                            )
                        }
                    },
                    onPickLight = { pick(Keys.Slot.LIGHT) },
                    onPickDark = { pick(Keys.Slot.DARK) },
                    onApply = { applyNow() },
                )
            }
        }
    }

    // ---------------- 选择主题 ----------------
    private fun pick(slot: Keys.Slot) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // .hwt/.hnt 没有标准 MIME，放开类型，选完再按扩展名校验
            // 尽量把初始目录定位到 /storage/emulated/0/Honor/Themes
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Honor/Themes"),
            )
        }
        runCatching { startActivityForResult(intent, slot.ordinal) }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }

    @Deprecated("沿用平台 API，避免再包一层 Activity Result 合约")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val slot = Keys.Slot.entries.getOrNull(requestCode) ?: return
        val name = displayName(uri)
        if (!ThemeIconExtractor.isThemeFileName(name)) {
            Toast.makeText(this, R.string.ni_toast_bad_ext, Toast.LENGTH_LONG).show()
            return
        }
        importTheme(slot, uri, name!!)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** 只读打开所选主题，解出 icons / icons_backup 到私有目录；原主题文件不改动 */
    private fun importTheme(slot: Keys.Slot, uri: Uri, name: String) {
        transientStatus = getString(R.string.ni_st_extracting); render()
        thread(name = "nighticon-extract") {
            val outDir = slotDir(slot)
            val staging = File(cacheDir, "staging_${slot.id}")
            val result = runCatching {
                staging.deleteRecursively()
                val input = contentResolver.openInputStream(uri) ?: error("无法打开所选文件")
                ThemeIconExtractor.extract(input, File(cacheDir, "extract"), staging)
            }
            result.onSuccess {
                // 解包成功才替换旧内容
                outDir.deleteRecursively(); outDir.parentFile?.mkdirs()
                if (!staging.renameTo(outDir)) { staging.copyRecursively(outDir, true); staging.deleteRecursively() }
                local.edit()
                    .putString(Keys.displayNameKey(slot), name)
                    .putLong(localVersionKey(slot), System.currentTimeMillis())
                    .putBoolean(Keys.hasBackupKey(slot), it.iconsBackup != null)
                    .apply()
                Log.i(TAG, "${slot.id} 解包完成：$name，png ${it.pngCount} 个，icons_backup=${it.iconsBackup != null}")
            }.onFailure { staging.deleteRecursively() }
            runOnUiThread {
                result.onSuccess { transientStatus = null }.onFailure {
                    if (it is ThemeIconExtractor.NoIconsException) {
                        transientStatus = getString(R.string.ni_st_no_icons)
                        Toast.makeText(this, R.string.ni_toast_no_icons, Toast.LENGTH_LONG).show()
                    } else {
                        transientStatus = null
                        Toast.makeText(this, getString(R.string.ni_toast_extract_fail, it.message), Toast.LENGTH_LONG).show()
                    }
                }
                render()
            }
        }
    }

    // ---------------- 立即应用 ----------------
    /** 把本地解出的 icons 写进落盘目录，再写 config（版本号 + apply 令牌），桌面侧复查到后同步并刷新 */
    private fun applyNow() {
        transientStatus = getString(R.string.ni_st_pushing); render()
        val enabled = uiState.enabled
        thread(name = "nighticon-push") {
            val r = runCatching {
                var lightVersion = 0L
                var darkVersion = 0L
                for (slot in Keys.Slot.entries) {
                    val v = local.getLong(localVersionKey(slot), 0)
                    val icons = File(slotDir(slot), "icons")
                    if (v == 0L || !icons.exists()) continue
                    if (local.getLong(pushedVersionKey(slot), 0) != v) {
                        check(NightIconStore.writeFile(this, Keys.remoteFile(slot, "icons"), icons)) {
                            "${slot.id} icons 下发失败"
                        }
                        val backup = File(slotDir(slot), "icons_backup")
                        if (backup.exists()) {
                            check(NightIconStore.writeFile(this, Keys.remoteFile(slot, "icons_backup"), backup)) {
                                "${slot.id} icons_backup 下发失败"
                            }
                        } else {
                            NightIconStore.deleteFile(this, Keys.remoteFile(slot, "icons_backup"))
                        }
                        local.edit().putLong(pushedVersionKey(slot), v).apply()
                    }
                    if (slot == Keys.Slot.LIGHT) lightVersion = v else darkVersion = v
                }
                val prev = NightIconStore.readState(this)
                val state = NightIconConfig.State(
                    enabled = enabled,
                    lightVersion = lightVersion,
                    darkVersion = darkVersion,
                    lightHasBackup = local.getBoolean(Keys.hasBackupKey(Keys.Slot.LIGHT), false),
                    darkHasBackup = local.getBoolean(Keys.hasBackupKey(Keys.Slot.DARK), false),
                    applyToken = System.currentTimeMillis(),
                    restartToken = prev?.restartToken ?: 0L,
                )
                check(NightIconStore.writeConfig(this, state)) { "配置写入失败" }
            }
            runOnUiThread {
                r.onSuccess { transientStatus = getString(R.string.ni_st_applied) }
                    .onFailure { transientStatus = "下发失败：${it.message}"; Log.e(TAG, "下发失败", it) }
                render()
            }
        }
    }

    // 「重启桌面」按钮自工具箱 v1.0.1 起撤出本页、集中到工具箱
    // 首页右上角的重启菜单（实现搬到 MainActivity，同机制：root
    // 强停优先、无 root 写 restart_token 兜底）。

    /** 落盘配置还不存在时，用本地镜像拼一份（版本号以已下发为准，未下发记 0）。 */
    private fun stateFromMirror(): NightIconConfig.State = NightIconConfig.State(
        enabled = local.getBoolean(Keys.ENABLED, false),
        lightVersion = local.getLong(pushedVersionKey(Keys.Slot.LIGHT), 0),
        darkVersion = local.getLong(pushedVersionKey(Keys.Slot.DARK), 0),
        lightHasBackup = local.getBoolean(Keys.hasBackupKey(Keys.Slot.LIGHT), false),
        darkHasBackup = local.getBoolean(Keys.hasBackupKey(Keys.Slot.DARK), false),
    )

    // ---------------- 渲染 ----------------
    private fun render() {
        val notSelected = getString(R.string.ni_not_selected)
        val light = local.getString(Keys.displayNameKey(Keys.Slot.LIGHT), null) ?: notSelected
        val dark = local.getString(Keys.displayNameKey(Keys.Slot.DARK), null) ?: notSelected
        val both = Keys.Slot.entries.all { local.getLong(localVersionKey(it), 0) != 0L }
        val enabled = uiState.enabled
        val transient = transientStatus
        val (status, tone) = if (transient != null) {
            transient to toneOf(transient)
        } else {
            when {
                !both -> getString(R.string.ni_st_need_pick) to StatusTone.Idle
                !enabled -> getString(R.string.ni_st_disabled) to StatusTone.Idle
                else -> getString(R.string.ni_st_ready) to StatusTone.Ready
            }
        }
        uiState = uiState.copy(lightName = light, darkName = dark, status = status, tone = tone)
    }

    private fun toneOf(text: String): StatusTone = when (text) {
        getString(R.string.ni_st_ready), getString(R.string.ni_st_applied) -> StatusTone.Ready
        getString(R.string.ni_st_extracting), getString(R.string.ni_st_pushing) -> StatusTone.Busy
        else -> StatusTone.Idle
    }

    private fun slotDir(slot: Keys.Slot) = File(filesDir, "slots/${slot.id}")
    private fun localVersionKey(slot: Keys.Slot) = "${slot.id}_local_version"
    private fun pushedVersionKey(slot: Keys.Slot) = "${slot.id}_pushed_version"
}

@Composable
private fun NightIconScreen(
    state: NightIconUiState,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPickLight: () -> Unit,
    onPickDark: () -> Unit,
    onApply: () -> Unit,
) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = "图标日夜切换",
                color = Color.Transparent,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = null)
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    SuperSwitch(
                        title = stringResource(R.string.ni_switch_enabled),
                        summary = stringResource(R.string.ni_switch_summary),
                        checked = state.enabled,
                        onCheckedChange = onEnabledChange,
                    )
                }

                SmallTitle(
                    text = stringResource(R.string.ni_section_theme),
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    ThemePickRow(
                        title = stringResource(R.string.ni_label_light),
                        current = stringResource(R.string.ni_current_fmt, state.lightName),
                        onClick = onPickLight,
                    )
                    ThemePickRow(
                        title = stringResource(R.string.ni_label_dark),
                        current = stringResource(R.string.ni_current_fmt, state.darkName),
                        onClick = onPickDark,
                    )
                }

                SmallTitle(
                    text = stringResource(R.string.ni_section_status),
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    val dotColor = when (state.tone) {
                        StatusTone.Ready -> Color(0xFF34C759)
                        StatusTone.Busy -> MiuixTheme.colorScheme.primary
                        StatusTone.Idle -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                    BasicComponent(
                        title = stringResource(R.string.ni_label_status),
                        endActions = {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(dotColor, CircleShape),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = state.status,
                                modifier = Modifier.widthIn(max = 210.dp),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.End,
                            )
                        },
                    )
                }

                Button(
                    onClick = onApply,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                        .height(52.dp),
                    cornerRadius = 16.dp,
                    minHeight = 52.dp,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        text = stringResource(R.string.ni_btn_apply),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    text = "需要重启桌面时，回工具箱首页、点右上角的重启按钮，选「重启桌面」。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ThemePickRow(
    title: String,
    current: String,
    onClick: () -> Unit,
) {
    SuperArrow(
        title = title,
        summary = current,
        onClick = onClick,
        endActions = {
            Text(
                text = stringResource(R.string.ni_pick_action),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        },
    )
}
