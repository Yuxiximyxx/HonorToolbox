package io.github.yuxiximyxx.honortoolbox.libraryplus.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

val BackIcon: ImageVector by lazy {
    ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(15f, 5f)
            lineTo(8f, 12f)
            lineTo(15f, 19f)
        }
    }.build()
}

/** 工具箱悬浮底栏的底部留白（合入后由 LibraryPlusScreen 提供，独立运行时为 0）。 */
val LocalLpBottomInset = compositionLocalOf { 0.dp }

/** MIUIX 风格页面：大标题 TopAppBar + 可折叠滚动。 */
@Composable
fun MiuixPage(
    title: String,
    onBack: (() -> Unit)?,
    extras: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scroll = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                scrollBehavior = scroll,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.padding(start = 16.dp)) {
                            Icon(BackIcon, contentDescription = "返回", tint = MiuixTheme.colorScheme.onBackground)
                        }
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scroll.nestedScrollConnection),
            contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding() + 16.dp + LocalLpBottomInset.current),
        ) {
            content()
            item { Spacer(Modifier.height(8.dp)) }
        }
        // SuperDialog 必须放在 Scaffold 内容槽内，才能写到 MiuixPopupHost 的 LocalDialogStates
        extras()
    }
}
