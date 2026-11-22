package io.github.yuxiximyxx.honortoolbox.libraryplus

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.CustomFoldersScreen
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.FolderDetailScreen
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.HiddenAppsScreen
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.HomeScreen
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.HonorSearchHomeScreen
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.LocalLpBottomInset
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.MappingScreen
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.RenameScreen
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.SearchCleanScreen
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.SearchEngineScreen
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.SearchScopeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「资源库增强」设置子页（原 LibraryPlus 独立 App 的 MainActivity
 * 导航原样搬入：home / hidden / rename / folders / folder:<id> /
 * mapping，配置读写仍走 [ConfigStore]，功能、默认值与落盘语义
 * 不变）。
 *
 * v1.6.6 起荣耀搜索拆为平级入口 [HonorSearchScreen]（根页
 * search_home / search_clean / search_scope / search_engine），
 * 与本页共用同一份 [LpConfig] 与同一套宿主状态，仅呈现拆分：
 * 搜索三子页的返回落点改为搜索主页，其余行为零改动。
 *
 * 与独立 App 的差异只有宿主层：主题由工具箱宿主提供（原 App 用
 * 跟随系统的 ThemeController）、返回落到工具箱「系统」页总览、
 * 离开子页时 flush 一次去抖广播（原 App 在 onPause 做同一件事）。
 * 打开子页时先跑一次旧配置迁移（见 [LpMigration]），导入成功后
 * 重读配置刷新界面；读不到旧配置就用现有配置，不阻塞不报错。
 */
@Composable
internal fun LibraryPlusScreen(
    onBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) = LibraryPlusHost(rootPage = "home", onBack = onBack, bottomInset = bottomInset)

/** 「荣耀搜索」设置子页（v1.6.6 与资源库增强平级，见上）。 */
@Composable
internal fun HonorSearchScreen(
    onBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) = LibraryPlusHost(rootPage = "search_home", onBack = onBack, bottomInset = bottomInset)

@Composable
private fun LibraryPlusHost(
    rootPage: String,
    onBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(ConfigStore.load(ctx)) }
    var page by rememberSaveable { mutableStateOf(rootPage) }
    val update: ((LpConfig) -> LpConfig) -> Unit = { f ->
        cfg = ConfigStore.save(ctx, f(cfg))
    }

    LaunchedEffect(Unit) {
        val imported = withContext(Dispatchers.IO) { LpMigration.importIfNewer(ctx) }
        if (imported) {
            cfg = ConfigStore.load(ctx)
        }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { ConfigStore.flush(ctx) } }
    }

    CompositionLocalProvider(LocalLpBottomInset provides bottomInset) {
        BackHandler(enabled = page != rootPage) {
            page = if (page.startsWith("folder:")) "folders" else rootPage
        }
        val backRoot = { page = rootPage }
        when {
            page.startsWith("folder:") -> {
                val id = page.removePrefix("folder:").toIntOrNull() ?: -1
                FolderDetailScreen(id, cfg, update) { page = "folders" }
            }
            page == "folders" -> CustomFoldersScreen(
                cfg, update, open = { page = it }, onBack = backRoot,
            )
            else -> when (page) {
                "hidden" -> HiddenAppsScreen(cfg, update, backRoot)
                "rename" -> RenameScreen(cfg, update, backRoot)
                "mapping" -> MappingScreen(cfg, update, backRoot)
                "search_clean" -> SearchCleanScreen(cfg, update, backRoot)
                "search_scope" -> SearchScopeScreen(cfg, update, backRoot)
                "search_engine" -> SearchEngineScreen(cfg, update, backRoot)
                "search_home" -> HonorSearchHomeScreen(
                    cfg,
                    open = { page = it },
                    onBack = onBack,
                )
                else -> HomeScreen(
                    cfg,
                    ConfigStore.xsharedAvailable,
                    update,
                    open = { page = it },
                    onBack = onBack,
                )
            }
        }
    }
}
