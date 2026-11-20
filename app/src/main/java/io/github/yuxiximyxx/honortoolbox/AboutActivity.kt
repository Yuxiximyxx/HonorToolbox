package io.github.yuxiximyxx.honortoolbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页：版本与来源致谢。荣耀工具箱由小乐自己的几个单功能
 * 模块合并而来，来源模块名只在这里出现。
 *
 * v1.1.0：原独立 Activity 已并入工具箱单 Activity 宿主，本页
 * 是从首页底部入口进的栏外页（不进底栏），内容不变。
 */
@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = "关于",
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
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text("荣耀工具箱", fontSize = 17.sp)
                        Text(
                            "版本 ${BuildConfig.VERSION_NAME} · 为荣耀 MagicOS 做的功能合集，设置按使用场景分组，功能之间互不影响、可以单独开关。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                SmallTitle(
                    text = "来源",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "本应用由以下自有模块合并而来（原仓库保持不变）：\n" +
                            "· HonorShadeClock —— 通知中心主题\n" +
                            "· QuickGesture —— 快服务手势改向\n" +
                            "· NavWake —— 长按导航条唤醒改向\n" +
                            "· NightIcon —— 图标日夜切换\n" +
                            "· MagicPods / MagicXM4 / LibraryPlus —— 耳机设备中心与资源库增强，下一阶段合入",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
