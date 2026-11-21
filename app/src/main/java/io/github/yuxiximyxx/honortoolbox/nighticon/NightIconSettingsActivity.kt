package io.github.yuxiximyxx.honortoolbox.nighticon

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuxiximyxx.honortoolbox.R
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
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 * 自工具箱 v1.0.1 起集中到工具箱首页（v1.2.0 起是首页「重启作用域」列表），本页只留提示）。
 * 与原版的差别只在通道：下发由 libxposed 远程偏好/远程文件
 * 改为落盘 /sdcard/Download/HonorToolbox/nighticon/（见
 * [NightIconStore]）；本地展示镜像偏好改名 nighticon_settings
 * （原版叫 settings，与工具箱内其他功能的镜像键冲突）。
 * 原版的「关于」行并入工具箱统一关于页，本页不再单列。
 *
 * v1.1.0：原独立 Activity 已并入工具箱单 Activity 宿主；原
 * Activity 级状态机整体搬到 [NightIconController]（宿主持
 * 有一份，选主题文件的结果由宿主 onActivityResult 转交
 * 它），本页只剩纯 UI。v1.2.0 起本页是「系统」页内桌面组
 * 的子页，宿主传 onBack 显示返回箭头回系统页总览。
 */

@Composable
internal fun NightIconScreen(
    state: NightIconUiState,
    onBack: (() -> Unit)?,
    onEnabledChange: (Boolean) -> Unit,
    onPickLight: () -> Unit,
    onPickDark: () -> Unit,
    onApply: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            SmallTopAppBar(
                title = "图标日夜切换",
                color = Color.Transparent,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = null)
                        }
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
                    .padding(bottom = 24.dp + bottomInset),
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
                    text = "需要重启桌面时，回工具箱首页，在「重启作用域」里点「重启桌面」。",
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
