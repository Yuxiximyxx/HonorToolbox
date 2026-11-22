package io.github.yuxiximyxx.honortoolbox.palmpurify

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.LocalLpBottomInset
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.MiuixPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「掌盟净化」设置子页（原 PalmPurify 独立 App 的 InfoActivity
 * 设置项逐项搬入，改 Miuix 页）：9 个开关与独立版一一对应，
 * 键名/默认值不变，读写走 [PalmPrefs] 落盘现读通道。
 *
 * 与独立版的差异只有宿主层：主题由工具箱宿主提供、返回落到
 * 工具箱「应用」页、配置通道由 RemotePreferences 换成落盘。
 * 打开子页时先跑一次旧配置迁移（见 [PalmMigration]）。
 *
 * v1.6.0 追加（小乐点名，单独标注）：「底栏显示」一组——
 * 原单一 slim_tabbar 改为总闸 + 逐项自定义，逐项名单来自
 * hook 在掌盟里实际命中的底栏项标签（[PalmPrefs.tabLabels]），
 * 没发现记录时先列缺省的「首页/我的」两项并注明其余项要
 * 打开一次掌盟后才出现。
 *
 * v1.6.1：名单改按 [PalmPrefs.tabEntries] 全量列（稳定身份
 * 制，纯图标项也记得到，不再只有带文字的三项）；逐项开关
 * 读值走 [PalmPrefs.tabShow] 旧键兼容读、写只写新键。
 */
@Composable
internal fun PalmPurifyScreen(
    onBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    var tick by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { tick++ }

    LaunchedEffect(Unit) {
        val imported = withContext(Dispatchers.IO) { PalmMigration.importIfNeeded() }
        if (imported) bump()
    }

    CompositionLocalProvider(LocalLpBottomInset provides bottomInset) {
        MiuixPage(title = "掌盟净化", onBack = onBack) {
            @Suppress("UNUSED_EXPRESSION") tick
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    Text(
                        "给掌上英雄联盟去广告与界面精简，只改展示层。开关经落盘通道同步给掌盟进程，下次打开掌盟时生效。",
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
            item { SmallTitle("界面") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    PalmSwitch(
                        title = "透明沉浸",
                        summary = "掌盟窗口透明沉浸，页面内容透到系统栏下",
                        key = ModulePrefs.KEY_IMMERSIVE,
                        default = ModulePrefs.DEFAULT_IMMERSIVE,
                        bump = bump,
                    )
                }
            }
            item { SmallTitle("开屏") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    PalmSwitch(
                        title = "屏蔽开屏广告",
                        summary = "冷启动时短路开屏广告的展示",
                        key = ModulePrefs.KEY_BLOCK_SPLASH_AD,
                        default = ModulePrefs.DEFAULT_BLOCK_SPLASH_AD,
                        bump = bump,
                    )
                }
            }
            item { SmallTitle("底栏显示") }
            item {
                val gate = PalmPrefs.bool(ModulePrefs.KEY_SLIM_TABBAR, ModulePrefs.DEFAULT_SLIM_TABBAR)
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    PalmSwitch(
                        title = "底栏精简",
                        summary = "总开关：关掉后底栏恢复原样，下面的逐项开关不生效",
                        key = ModulePrefs.KEY_SLIM_TABBAR,
                        default = ModulePrefs.DEFAULT_SLIM_TABBAR,
                        bump = bump,
                    )
                    val entries = PalmPrefs.tabEntries()
                    if (entries.isEmpty()) {
                        PalmSwitch(
                            title = "首页",
                            summary = "底栏功能项（逐项名单待打开一次掌盟后补齐）",
                            key = PalmPrefs.tabShowKey("首页"),
                            default = true,
                            enabled = gate,
                            bump = bump,
                        )
                        PalmSwitch(
                            title = "我的",
                            summary = "底栏功能项（逐项名单待打开一次掌盟后补齐）",
                            key = PalmPrefs.tabShowKey("我的"),
                            default = true,
                            enabled = gate,
                            bump = bump,
                        )
                    } else {
                        for (entry in entries) {
                            PalmTabSwitch(
                                entry = entry,
                                enabled = gate,
                                bump = bump,
                            )
                        }
                    }
                    PalmSwitch(
                        title = "隐藏中间加号",
                        summary = "藏掉底栏中间的加号按钮",
                        key = ModulePrefs.KEY_HIDE_CENTER_BTN,
                        default = ModulePrefs.DEFAULT_HIDE_CENTER_BTN,
                        bump = bump,
                    )
                }
            }
            item { SmallTitle("首页") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    PalmSwitch(
                        title = "首页运营模块精简",
                        summary = "藏掉首页的广告/运营模块，战绩、资产、活动入口保留",
                        key = ModulePrefs.KEY_SLIM_HOME_MODULES,
                        default = ModulePrefs.DEFAULT_SLIM_HOME_MODULES,
                        bump = bump,
                    )
                }
            }
            item { SmallTitle("弹窗") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    PalmSwitch(
                        title = "拦截推广/会员弹窗",
                        summary = "不显示推广、开通会员一类的弹窗",
                        key = ModulePrefs.KEY_BLOCK_PROMO_DIALOG,
                        default = ModulePrefs.DEFAULT_BLOCK_PROMO_DIALOG,
                        bump = bump,
                    )
                    PalmSwitch(
                        title = "关闭更新提醒",
                        summary = "不显示版本更新提醒弹窗",
                        key = ModulePrefs.KEY_BLOCK_UPDATE_DIALOG,
                        default = ModulePrefs.DEFAULT_BLOCK_UPDATE_DIALOG,
                        bump = bump,
                    )
                }
            }
            item { SmallTitle("其他") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    PalmSwitch(
                        title = "屏蔽摇一摇跳转",
                        summary = "不注册营销向的摇一摇跳转",
                        key = ModulePrefs.KEY_BLOCK_SHAKE,
                        default = ModulePrefs.DEFAULT_BLOCK_SHAKE,
                        bump = bump,
                    )
                    PalmSwitch(
                        title = "调试日志",
                        summary = "把侦察日志写进 LSPosed 日志，排查问题时打开",
                        key = ModulePrefs.KEY_DEBUG_LOG,
                        default = ModulePrefs.DEFAULT_DEBUG_LOG,
                        bump = bump,
                    )
                }
            }
        }
    }
}

/**
 * 一个底栏逐项开关行（v1.6.1）：读走 [PalmPrefs.tabShow]
 * 旧键兼容读（v1.6.0 已调的值不失效），写只写新键
 * `tab_show_<身份>`，写过一次即完成该项的键迁移。
 */
@Composable
private fun PalmTabSwitch(
    entry: PalmPrefs.TabEntry,
    enabled: Boolean = true,
    bump: () -> Unit,
) {
    SuperSwitch(
        title = entry.title,
        summary = "在底栏显示这一项；藏掉后其余项自动等距铺满",
        checked = PalmPrefs.tabShow(
            entry.identity,
            entry.legacyLabel,
            PalmPrefs.defaultTabShow(entry),
        ),
        enabled = enabled,
        onCheckedChange = { value ->
            PalmPrefs.putBool(PalmPrefs.tabShowKey(entry.identity), value)
            bump()
        },
    )
}

/** 一个 PalmPurify 开关行：读走 [PalmPrefs] 现读，写即落盘并触发重组刷新。 */
@Composable
private fun PalmSwitch(
    title: String,
    summary: String,
    key: String,
    default: Boolean,
    enabled: Boolean = true,
    bump: () -> Unit,
) {
    SuperSwitch(
        title = title,
        summary = summary,
        checked = PalmPrefs.bool(key, default),
        enabled = enabled,
        onCheckedChange = { value ->
            PalmPrefs.putBool(key, value)
            bump()
        },
    )
}
