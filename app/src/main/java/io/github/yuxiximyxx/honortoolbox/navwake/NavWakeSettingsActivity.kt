package io.github.yuxiximyxx.honortoolbox.navwake

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yuxiximyxx.honortoolbox.navwake.config.AppTargets
import io.github.yuxiximyxx.honortoolbox.navwake.config.NavConfig
import io.github.yuxiximyxx.honortoolbox.navwake.config.Packages
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 「手势与唤醒 → 长按导航条唤醒」设置页（工具箱统一 MIUIX 呈现）。
 *
 * 设置项与原 NavWake 设置页逐项一致（功能/默认值/落盘语义不变）：
 * 启用重定向（默认开）、单击导航条返回（默认关）、可唤起的应用（多选）、
 * 默认目标（点选/清除）、拦截说明、诊断（探针模式/作用域/日志路径/复制）。
 * 写入路径与原版同构：本地镜像 prefs + 同步落盘 navwake.txt（原版是远程 prefs）。
 */
class NavWakeSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavConfig.bind(getSharedPreferences(NavConfig.PREFS_NAME, MODE_PRIVATE))
        NavConfig.onChanged = { NavWakeStore.syncFromPrefs(this) }
        setContent {
            MiuixTheme {
                NavWakeSettingsScreen(onBack = { finish() })
            }
        }
    }

    @Composable
    private fun NavWakeSettingsScreen(onBack: () -> Unit) {
        var rev by remember { mutableStateOf(0) }
        var picking by remember { mutableStateOf(false) }
        if (picking) {
            AppPickerScreen(
                onDone = { picking = false; rev++ }
            )
            return
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "长按导航条唤醒",
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = MiuixIcons.Useful.Back, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            @Suppress("UNUSED_EXPRESSION") rev // 读一次以便状态变更后重组
            val configured = AppTargets.all(this@NavWakeSettingsActivity)
            val installed = AppTargets.installed(this@NavWakeSettingsActivity)
            val currentDefault = NavConfig.defaultTarget()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .overScrollVertical()
                    .scrollEndHaptic(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    SmallTitle(text = "长按重定向")
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Column {
                            BasicComponent(
                                title = "启用重定向",
                                summary = "长按导航条改为唤起自选应用",
                                endActions = {
                                    Switch(
                                        checked = NavConfig.enabled(),
                                        onCheckedChange = { v ->
                                            NavConfig.setEnabled(v); rev++
                                        }
                                    )
                                }
                            )
                            BasicComponent(
                                title = "关闭后完全恢复原 YOYO 行为，可随时打开",
                                titleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
                item {
                    SmallTitle(text = "单击返回")
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Column {
                            BasicComponent(
                                title = "单击导航条返回",
                                summary = "点一下底部导航条小白条 = 返回一次",
                                endActions = {
                                    Switch(
                                        checked = NavConfig.tapBackEnabled(),
                                        onCheckedChange = { v ->
                                            NavConfig.setTapBackEnabled(v); rev++
                                        }
                                    )
                                }
                            )
                            BasicComponent(
                                title = "和长按唤醒、上滑回桌面/多任务互不影响；改完立即生效，默认关",
                                titleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
                item {
                    SmallTitle(text = "可唤起的应用")
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Column {
                            BasicComponent(
                                title = if (configured.isEmpty()) {
                                    "还没选应用"
                                } else {
                                    "已选 ${configured.size} 个：" +
                                        configured.joinToString("、") { it.displayName } +
                                        if (configured.size > installed.size) "（其中部分未安装）" else ""
                                },
                                titleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            BasicComponent(
                                title = "选择应用",
                                summary = "勾选允许被长按唤起的应用",
                                onClick = { picking = true }
                            )
                        }
                    }
                }
                item {
                    SmallTitle(text = "默认目标")
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Column {
                            BasicComponent(
                                title = if (currentDefault.isEmpty()) {
                                    "当前：每次长按都弹选择框"
                                } else {
                                    "当前：直接打开 ${AppTargets.labelOf(this@NavWakeSettingsActivity, currentDefault)}"
                                },
                                titleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            installed.forEach { target ->
                                val isDefault = target.pkg == currentDefault
                                BasicComponent(
                                    title = target.displayName,
                                    summary = if (isDefault) "当前默认" else "点按设为默认",
                                    onClick = {
                                        NavConfig.setDefaultTarget(target.pkg); rev++
                                    }
                                )
                            }
                            BasicComponent(
                                title = "清除默认（回到每次都弹框）",
                                enabled = currentDefault.isNotEmpty(),
                                onClick = {
                                    NavConfig.clearDefaultTarget(); rev++
                                }
                            )
                        }
                    }
                }
                item {
                    SmallTitle(text = "拦截的是什么")
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        BasicComponent(
                            title = "真机实测：长按导航条时，SystemUI 会调 com.hihonor.magicvoice 的 " +
                                "StartAssistantService 拉起 YOYO 的悬浮窗。本功能只在 SystemUI 进程里拦这一条调用，" +
                                "命中后改为打开你选的应用。任何异常（目标被卸载、选择框起不来、hook 失败）都会放行原行为，" +
                                "长按绝不会变成死键。锁屏状态下不拦截。",
                            titleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
                item {
                    SmallTitle(text = "诊断")
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Column {
                            BasicComponent(
                                title = "探针模式",
                                summary = "阶段 0 日志，平时不用开",
                                endActions = {
                                    Switch(
                                        checked = NavConfig.probeMode(),
                                        onCheckedChange = { v ->
                                            NavConfig.setProbeMode(v); rev++
                                        }
                                    )
                                }
                            )
                            BasicComponent(
                                title = "作用域",
                                summary = Packages.ALL.joinToString("\n"),
                                titleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            BasicComponent(
                                title = "日志：/data/adb/lspd/log/modules_*.log（不是 logcat）",
                                titleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            BasicComponent(
                                title = "复制日志路径",
                                onClick = {
                                    val clipboard =
                                        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("log path", "/data/adb/lspd/log/")
                                    )
                                    Toast.makeText(
                                        this@NavWakeSettingsActivity, "已复制", Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppPickerScreen(onDone: () -> Unit) {
        val apps = remember { AppTargets.launchableApps(this@NavWakeSettingsActivity, packageName) }
        var checked by remember {
            mutableStateOf(AppTargets.selectedPackages().toSet())
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "选择可唤起的应用",
                    navigationIcon = {
                        IconButton(onClick = onDone) {
                            Icon(imageVector = MiuixIcons.Useful.Back, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .overScrollVertical()
                        .scrollEndHaptic()
                ) {
                    items(apps) { app ->
                        val isChecked = app.pkg in checked
                        BasicComponent(
                            title = app.label,
                            summary = app.pkg,
                            endActions = {
                                Checkbox(
                                    state = isChecked,
                                    onStateChange = { v ->
                                        checked = if (v) checked + app.pkg else checked - app.pkg
                                    }
                                )
                            },
                            onClick = {
                                checked = if (isChecked) checked - app.pkg else checked + app.pkg
                            }
                        )
                    }
                }
                Button(
                    modifier = Modifier.padding(16.dp),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = {
                        // 保持列表展示顺序（按应用名排序后的顺序）保存
                        val ordered = apps.filter { it.pkg in checked }.map { it.pkg }
                        AppTargets.saveSelectedPackages(this@NavWakeSettingsActivity, ordered)
                        onDone()
                    }
                ) {
                    Text(text = "保存")
                }
            }
        }
    }
}
