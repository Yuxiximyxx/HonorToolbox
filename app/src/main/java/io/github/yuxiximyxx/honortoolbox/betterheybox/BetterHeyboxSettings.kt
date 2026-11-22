package io.github.yuxiximyxx.honortoolbox.betterheybox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.better.heybox.App
import com.better.heybox.GlassProvider
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.LocalLpBottomInset
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.MiuixPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「小黑盒增强」设置子页（v1.7.0 合入，原 BetterHeybox 独立
 * 模块）：原模块的设置入口只在小黑盒软件内（通用设置页顶部
 * 插入的内嵌面板），小乐点名在工具箱里也给一个设置界面。
 * 本页把原内嵌面板的全部持久化设置项按同样的功能分组摆齐，
 * 键名、默认值、生效语义与原模块一致，读写走
 * [HeyboxSettingsStore] 镜像 + [BetterHeyboxConfigProvider]
 * 直供小黑盒进程；软件内面板保留、两处改动互通。
 *
 * 只在软件内面板保留的（依赖小黑盒运行时/文件选择器，工具
 * 箱进程里做不了）：测试 AI 连接、动态推送的立即检查/测试
 * 推送/导入关注与话题搜索、游戏库与消息入口的勾选器、评论
 * 长按面板按钮管理、视频保存位置与配置/日志的导入导出、
 * 各诊断行。本页以说明文字标注去向。
 */
private class EditReq(
    val key: String,
    val title: String,
    val hint: String,
    val multiline: Boolean,
)

private class ChoiceReq(
    val title: String,
    val labels: List<String>,
    val currentIndex: Int,
    val onPick: (Int) -> Unit,
)

@Composable
internal fun BetterHeyboxScreen(
    onBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { tick++ }
    var editReq by remember { mutableStateOf<EditReq?>(null) }
    var choiceReq by remember { mutableStateOf<ChoiceReq?>(null) }

    CompositionLocalProvider(LocalLpBottomInset provides bottomInset) {
        MiuixPage(
            title = "小黑盒增强",
            onBack = onBack,
            extras = {
                HeyEditDialog(
                    req = editReq,
                    onDismiss = { editReq = null },
                    onSaved = { editReq = null; bump() },
                )
                HeyChoiceDialog(
                    req = choiceReq,
                    onDismiss = { choiceReq = null },
                )
            },
        ) {
            @Suppress("UNUSED_EXPRESSION") tick
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    Text(
                        "给小黑盒去广告与功能增强。设置经直供通道同步给小黑盒进程；" +
                            "小黑盒内的设置面板（通用设置页顶部）也还在，两处改动互通。" +
                            "改动多数即时生效，标注「重启生效」的需重开小黑盒。" +
                            "装好本版后请停用旧的独立版 BetterHeybox，避免双重 hook。",
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }

            group("广告过滤")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("屏蔽开屏广告", null, App.KEY_OPEN_SCREEN, bump)
                    HeySwitch("屏蔽信息流广告", null, App.KEY_FEED_AD, bump)
                    HeySwitch("屏蔽气泡广告", null, App.KEY_BUBBLE_AD, bump)
                    HeySwitch("屏蔽角标广告", null, App.KEY_CORNER_AD, bump)
                    HeySwitch("屏蔽推广贴", "首页推广卡、广告横幅、社区推广贴与广告位", App.KEY_PROMOTE_AD, bump)
                    HeySwitch("诊断：记录首页流条目", "把每条首页流的判定信息写进日志", App.KEY_FLOW_DIAGNOSE, bump)
                }
            }

            group("发帖过滤")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("屏蔽视频帖", "信息流中隐藏视频帖", App.KEY_BLOCK_VIDEO_POST, bump)
                    HeyText("屏蔽低赞帖子", "点赞数低于该值的帖子隐藏，0 为关闭", App.KEY_POST_MIN_LIKE, "点赞数阈值（数字）", false, { editReq = it }, bump)
                    HeyText("屏蔽低评论帖子", "评论数低于该值的帖子隐藏，0 为关闭", App.KEY_POST_MIN_COMMENT, "评论数阈值（数字）", false, { editReq = it }, bump)
                    HeyText("屏蔽低收藏帖子", "收藏数低于该值的帖子隐藏；无收藏数据的列表自动放行", App.KEY_POST_MIN_FAVOUR, "收藏数阈值（数字）", false, { editReq = it }, bump)
                    HeyText("屏蔽低等级发帖", "屏蔽该等级以下的用户帖子，0 为关闭", App.KEY_POST_MIN_LEVEL, "等级阈值（数字）", false, { editReq = it }, bump)
                    HeySwitch("屏蔽无等级用户", "无等级账号一并屏蔽", App.KEY_POST_NO_LEVEL, bump)
                    HeyText("关键词屏蔽", "一行一个，命中标题或正文即屏蔽", App.KEY_POST_KEYWORDS, "关键词（一行一个）", true, { editReq = it }, bump)
                    HeySwitch("AI 标题党识别", "标题会发送给 AI 服务商", App.KEY_POST_AI_ENABLED, bump)
                    HeyChoice(
                        "AI 提供商",
                        "选择后自动填入接口地址与模型",
                        currentLabel = {
                            val id = HeyboxSettingsStore.string(context, App.KEY_AI_PROVIDER)
                            AI_PROVIDER_LABELS.getOrNull(AI_PROVIDER_IDS.indexOf(id)) ?: "未选择"
                        },
                        open = {
                            val id = HeyboxSettingsStore.string(context, App.KEY_AI_PROVIDER)
                            choiceReq = ChoiceReq(
                                title = "AI 提供商",
                                labels = AI_PROVIDER_LABELS,
                                currentIndex = maxOf(AI_PROVIDER_IDS.indexOf(id), 0),
                                onPick = { index ->
                                    HeyboxSettingsStore.putString(context, App.KEY_AI_PROVIDER, AI_PROVIDER_IDS[index])
                                    if (AI_PROVIDER_BASE_URLS[index].isNotEmpty()) {
                                        HeyboxSettingsStore.putString(context, App.KEY_AI_BASE_URL, AI_PROVIDER_BASE_URLS[index])
                                    }
                                    if (AI_PROVIDER_MODELS[index].isNotEmpty()) {
                                        HeyboxSettingsStore.putString(context, App.KEY_AI_MODEL, AI_PROVIDER_MODELS[index])
                                    }
                                    bump()
                                },
                            )
                        },
                    )
                    HeyText("API 地址", "OpenAI 兼容接口地址", App.KEY_AI_BASE_URL, "接口地址", false, { editReq = it }, bump)
                    HeyText("模型", "OpenAI 兼容模型名", App.KEY_AI_MODEL, "模型名", false, { editReq = it }, bump)
                    HeyText("API Token", "本地模型可留空", App.KEY_AI_TOKEN, "Token", false, { editReq = it }, bump)
                    HeyText("判定提示词", "留空用内置提示词", App.KEY_AI_PROMPT, "提示词", true, { editReq = it }, bump)
                    HeyText("输出 Token 上限", "过小会截断结果，范围 100–4000，默认 700", App.KEY_AI_MAX_TOKENS, "Token 上限（数字）", false, { editReq = it }, bump)
                }
            }

            group("首页信息流")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("撤销刷新气泡", "刷新后显示宿主的「撤销」气泡，点击把刷新前的内容插回列表", App.KEY_FEED_REFRESH_UNDO, bump)
                    HeySwitch("刷新保留上次内容", "刷新后上一次内容仍在「上次浏览」分隔线下", App.KEY_FEED_KEEP_PREVIOUS, bump)
                }
            }

            group("评论过滤")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("屏蔽插眼评论", "在数据/列表层直接摘掉带 Cy 标的评论", App.KEY_HOST_HIDE_CY, bump)
                    HeySwitch("插眼保留第一条", "同一评论区里 cy 后面的内容完全一致时只保留第一条", App.KEY_CY_KEEP_FIRST, bump)
                    HeySwitch("评论关键词屏蔽 / 无意义评论", "关键词屏蔽评论", App.KEY_BLOCK_CY_COMMENT, bump)
                    HeyText("评论关键词", "一行一个，命中评论正文即屏蔽", App.KEY_COMMENT_KEYWORDS, "关键词（一行一个）", true, { editReq = it }, bump)
                    HeySwitch("屏蔽游戏名接龙", "屏蔽正文仅为游戏链接的评论", App.KEY_BLOCK_GAME_RELAY, bump)
                    HeySwitch("接龙判定忽略表情", "[cube_xxx] 不计入残留字数", App.KEY_RELAY_IGNORE_EMOJI, bump)
                }
            }

            group("搜索页精简")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("隐藏搜索页横幅", "搜索栏下方横幅推荐", App.KEY_SEARCH_HIDE_BANNER, bump)
                    HeySwitch("隐藏「搜索发现」", "搜索页的搜索发现标题与推荐列表", App.KEY_SEARCH_HIDE_DISCOVER, bump)
                    HeySwitch("隐藏「黑盒热榜」", "搜索页的热榜标签页与热词卡片", App.KEY_SEARCH_HIDE_HOT_RANK, bump)
                }
            }

            group("帖子详情精简")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("屏蔽帖子下无关内容", "相关搜索、文字配图横幅、小程序、合集、活动等", App.KEY_POST_DETAIL_CLEAN, bump)
                    HeySwitch("话题行防误触", "最下方话题行禁用点击跳转", App.KEY_POST_DETAIL_TOPIC_NO_CLICK, bump)
                    HeySwitch("隐藏话题栏", "整行隐藏帖子最下方的 #话题 标签", App.KEY_POST_DETAIL_TOPIC_HIDE, bump)
                    HeyText("额外隐藏的视图名", "一行一个资源名", App.KEY_POST_DETAIL_HIDE_IDS, "资源名（一行一个）", true, { editReq = it }, bump)
                }
            }

            group("游戏库精简")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("隐藏游戏库横幅", "游戏库顶端横幅推荐", App.KEY_GAME_LIB_HIDE_BANNER, bump)
                    HeySwitch("隐藏游戏库小分区", "黑盒商城、小程序等入口卡片", App.KEY_GAME_LIB_HIDE_MENU, bump)
                    HeySwitch("隐藏游戏库推荐分区", "「为你推荐」等分区标题与内容卡", App.KEY_GAME_LIB_HIDE_SECTIONS, bump)
                    Text(
                        "自定义隐藏类型、指定入口卡片与推荐分区的勾选名单来自小黑盒实际页面，在小黑盒内的设置面板里勾选。",
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }

            group("分享净化")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("净化分享链接", null, App.KEY_PURIFY_SHARE_LINK, bump)
                }
            }

            group("收藏管理")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("自动清理失效收藏", "打开收藏列表发现失效内容时自动清理", App.KEY_FAVOUR_AUTO_CLEAN, bump)
                }
            }

            group("液态玻璃")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    val provider = HeyboxSettingsStore.string(context, App.KEY_GLASS_PROVIDER)
                    val hbmodReady = GlassProvider.isHbmodInstalled(context) ||
                        provider == GlassProvider.PROVIDER_HBMOD
                    if (hbmodReady) {
                        HeyChoice(
                            "液态玻璃提供方",
                            "底栏玻璃的实现来源",
                            currentLabel = {
                                if (HeyboxSettingsStore.string(context, App.KEY_GLASS_PROVIDER) ==
                                    GlassProvider.PROVIDER_HBMOD
                                ) "小黑盒液态玻璃模块" else "BetterHeybox"
                            },
                            open = {
                                choiceReq = ChoiceReq(
                                    title = "液态玻璃提供方",
                                    labels = listOf("BetterHeybox", "小黑盒液态玻璃模块"),
                                    currentIndex = if (provider == GlassProvider.PROVIDER_HBMOD) 1 else 0,
                                    onPick = { index ->
                                        HeyboxSettingsStore.putString(
                                            context, App.KEY_GLASS_PROVIDER,
                                            if (index == 1) GlassProvider.PROVIDER_HBMOD
                                            else GlassProvider.PROVIDER_OWN,
                                        )
                                        bump()
                                    },
                                )
                            },
                        )
                    }
                    HeySwitch("液态玻璃底栏", "底栏显示液态玻璃", App.KEY_LIQUID_GLASS, bump)
                    HeySwitch("沉浸式小白条", "底栏延伸至手势区域", App.KEY_GLASS_IMMERSIVE, bump)
                    HeySwitch("自适应反色", "文字图标随背景反色", App.KEY_GLASS_ADAPTIVE, bump)
                    HeySwitch("加长选中 Tab", "隐藏标签后选中项加长，底栏随可见数量收缩", App.KEY_GLASS_FIT_TABS, bump)
                    HeySwitch("搜索按钮进底栏", "把搜索入口放进玻璃底栏", App.KEY_GLASS_SEARCH_IN_BAR, bump)
                    HeyText("暗色模式底色", "例如 #000000", App.KEY_GLASS_DARK_COLOR, "颜色值", false, { editReq = it }, bump)
                    HeyText("暗色模式不透明度", "5–98 的百分比", App.KEY_GLASS_DARK_ALPHA, "百分比（数字）", false, { editReq = it }, bump)
                    HeyText("亮色模式底色", "例如 #FFFFFF", App.KEY_GLASS_LIGHT_COLOR, "颜色值", false, { editReq = it }, bump)
                    HeyText("亮色模式不透明度", "5–98 的百分比", App.KEY_GLASS_LIGHT_ALPHA, "百分比（数字）", false, { editReq = it }, bump)
                    HeyText("玻璃条高度", "0 自动，或 51–99 dp", App.KEY_GLASS_BAR_HEIGHT, "高度（数字）", false, { editReq = it }, bump)
                    HeyText("距屏幕底部", "0–40 dp", App.KEY_GLASS_BAR_OFFSET, "距离（数字）", false, { editReq = it }, bump)
                    HeyText("左右边距", "玻璃条两侧边距 dp", App.KEY_GLASS_SIDE_MARGIN, "边距（数字）", false, { editReq = it }, bump)
                    HeyText("玻璃条宽度模式", "玻璃条宽度模式代码", App.KEY_GLASS_BAR_WIDTH_MODE, "宽度模式", false, { editReq = it }, bump)
                    HeyText("玻璃条宽度百分比", "宽度百分比（数字）", App.KEY_GLASS_BAR_WIDTH_PCT, "百分比（数字）", false, { editReq = it }, bump)
                    HeyText("Tab 宽度百分比", "单个 Tab 宽度百分比（数字）", App.KEY_GLASS_TAB_WIDTH_PCT, "百分比（数字）", false, { editReq = it }, bump)
                    HeyText("玻璃条布局", "布局代码", App.KEY_GLASS_BAR_LAYOUT, "布局", false, { editReq = it }, bump)
                    HeyText("选中 Tab 颜色", "例如 #FFFFFF", App.KEY_GLASS_TAB_SELECT_COLOR, "颜色值", false, { editReq = it }, bump)
                    HeyText("选中 Tab 不透明度", "百分比（数字）", App.KEY_GLASS_TAB_SELECT_ALPHA, "百分比（数字）", false, { editReq = it }, bump)
                    HeyText("Tab 顺序", "底栏 Tab 的顺序代码", App.KEY_GLASS_TAB_ORDER, "顺序", false, { editReq = it }, bump)
                    Text(
                        "玻璃条的拖动调节面板在小黑盒内的设置面板里（「玻璃条宽度」一项）。",
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }

            group("底部导航栏")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("禁止底栏热更新", "保留已有底栏策略和图标；仅支持 1.3.400 (1149) 和 1.3.401 (1150)，重启生效", App.KEY_BLOCK_BOTTOM_TAB_UPDATE, bump)
                    HeySwitch("隐藏「发现」", "重启小黑盒生效", App.KEY_HIDE_TAB_HOME, bump)
                    HeySwitch("隐藏「游戏库」", "重启小黑盒生效", App.KEY_HIDE_TAB_HOT, bump)
                    HeySwitch("隐藏「社区」", "重启小黑盒生效", App.KEY_HIDE_TAB_GAME, bump)
                    HeySwitch("隐藏「加号」", "重启小黑盒生效", App.KEY_HIDE_ADD, bump)
                }
            }

            group("消息红点")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("隐藏消息未读红点", "各页面右上角的小红点（含底栏消息红点）", App.KEY_HIDE_MSG_DOT, bump)
                    HeySwitch("精简消息入口", "隐藏勾选入口的红色数字", App.KEY_HIDE_MSG_BADGE, bump)
                    Text(
                        "「隐藏红数字的入口」「隐藏相关入口」的勾选名单来自消息页实际入口，在小黑盒内的设置面板里勾选。",
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }

            group("实验性功能")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("屏蔽双列信息流", "信息流恢复为单列", App.KEY_SINGLE_COLUMN_FEED, bump)
                }
            }

            group("解除复制")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("解除复制", "恢复系统文本选择", App.KEY_COPY_POST, bump)
                    HeySwitch("自绘制文本选择", "修复选区异常", App.KEY_CUSTOM_TEXT_SELECT, bump)
                    HeySwitch("评论区自由复制", "长按菜单的复制可自由选择", App.KEY_COMMENT_FREE_COPY, bump)
                    HeySwitch("系统分享图片", "图片长按加入系统分享", App.KEY_SYSTEM_SHARE, bump)
                    HeySwitch("大图自动原图", "缩略图小于显示尺寸时换成原图", App.KEY_IMAGE_AUTO_ORIGINAL, bump)
                    HeySwitch("图片分享不落相册", "分享图片时只写缓存，不保存到相册", App.KEY_IMAGE_SHARE_NO_SAVE, bump)
                }
            }

            group("评论交互")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("回复改长按面板", "单击评论不再弹回复框；长按评论在分享面板点「回复」", App.KEY_COMMENT_REPLY_PANEL, bump)
                    HeySwitch("自动展开回复", "点开评论楼中楼时自动展开", App.KEY_COMMENT_AUTO_EXPAND, bump)
                    Text(
                        "长按面板的按钮排序与隐藏在小黑盒内的设置面板里管理（首页「长按面板按钮管理」）。",
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }

            group("网页")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("重定向外部链接", "外部链接用系统浏览器打开", App.KEY_BROWSER_REDIRECT, bump)
                    HeySwitch("包含小黑盒域名", "小黑盒域名也重定向", App.KEY_BROWSER_REDIRECT_KNOWN, bump)
                    HeyText("重定向浏览器", "目标浏览器的包名，留空用系统默认", App.KEY_BROWSER_TARGET, "浏览器包名", false, { editReq = it }, bump)
                    HeyText("强制重定向域名", "一行一个域名或完整链接，总是用外部浏览器打开", App.KEY_BROWSER_REDIRECT_FORCE, "域名（一行一个）", true, { editReq = it }, bump)
                    HeyText("强制内置域名", "一行一个域名或完整链接，总是留在内置浏览器", App.KEY_BROWSER_REDIRECT_BLOCK, "域名（一行一个）", true, { editReq = it }, bump)
                    HeySwitch("网页 DevTools", "开启 Chrome 远程调试", App.KEY_WEBVIEW_DEVTOOLS, bump)
                    HeySwitch("网页日志", "记录打开过的页面与标题", App.KEY_WEB_LOG, bump)
                }
            }

            group("视频下载")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("下载视频", "视频上显示下载入口", App.KEY_VIDEO_DOWNLOAD, bump)
                    HeySwitch("转存 MP4", "合并后转为 MP4", App.KEY_VIDEO_TO_MP4, bump)
                    Text(
                        "保存位置用系统文件夹选择器授予目录权限，在小黑盒内的设置面板里选择（默认相册 Movies/BetterHeybox）。",
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }

            group("截图功能")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("启用截图功能", "贴文与留言截图，可多选", App.KEY_SCREENSHOT_ENABLED, bump)
                    HeySwitch("隐藏用户标签", "截图时隐藏标签", App.KEY_SCREENSHOT_HIDE_TAGS, bump)
                    HeySwitch("隐藏时间戳", "截图时隐藏时间", App.KEY_SCREENSHOT_HIDE_TIMESTAMPS, bump)
                    HeySwitch("隐藏操作按钮", "截图时隐藏按钮", App.KEY_SCREENSHOT_HIDE_ACTIONS, bump)
                    HeySwitch("隐藏无关内容", "相关搜索、小程序、合集、活动", App.KEY_SCREENSHOT_HIDE_IRRELEVANT, bump)
                    HeySwitch("隐藏关闭图标", "截图时隐藏 ✕ / 关闭 / 取消 图标", App.KEY_SCREENSHOT_HIDE_CLOSE, bump)
                    HeySwitch("隐藏关注按钮", "截图时隐藏作者行的「关注」", App.KEY_SCREENSHOT_HIDE_FOLLOW, bump)
                    HeySwitch("隐藏点赞数", "帖子与评论的点赞图标和数字", App.KEY_SCREENSHOT_HIDE_LIKE_COUNT, bump)
                    HeySwitch("隐藏图片数量角标", "图片右上角的数量标识", App.KEY_SCREENSHOT_HIDE_IMAGE_COUNT, bump)
                    HeyText("自定义隐藏内容", "资源名或 text:关键词", App.KEY_SCREENSHOT_HIDE_CUSTOM, "隐藏规则", true, { editReq = it }, bump)
                    HeySwitch("添加水印", "BetterHeybox 水印", App.KEY_SCREENSHOT_ADD_WATERMARK, bump)
                    HeySwitch("截图分享不落相册", "截图选择分享时只写临时缓存，不保存到相册", App.KEY_SCREENSHOT_SHARE_NO_SAVE, bump)
                }
            }

            group("图片缓存")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeyText("图片缓存目录", "绝对路径；留空恢复默认，修改后需重启小黑盒", App.KEY_IMAGE_CACHE_DIR, "绝对路径", false, { editReq = it }, bump)
                }
            }

            group("每日任务")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("自动完成每日分享任务", null, App.KEY_DAILY_TASK_ENABLED, bump)
                    HeySwitch("静默 API 模式（实验性）", "后台直接上报分享任务，完成后弹提示；失败不写打卡记录", App.KEY_DAILY_TASK_SILENT, bump)
                    HeySwitch("完成后返回首页", "完成后自动退回首页", App.KEY_DAILY_TASK_BACK_HOME, bump)
                    HeyText("帖子链接", "任务一：分享帖子", App.KEY_DAILY_TASK_PICTURE, "帖子链接", false, { editReq = it }, bump)
                    HeyText("游戏详情链接", "任务二：分享游戏详情", App.KEY_DAILY_TASK_NORMAL, "游戏详情链接", false, { editReq = it }, bump)
                    HeyText("游戏评价链接", "任务三：分享游戏评价", App.KEY_DAILY_TASK_CHANNEL, "游戏评价链接", false, { editReq = it }, bump)
                    HeyChoice(
                        "分享渠道",
                        "每日任务分享时用的渠道",
                        currentLabel = {
                            when (HeyboxSettingsStore.string(context, App.KEY_SHARE_CHANNEL, "QQ")) {
                                "WECHAT" -> "微信 / 朋友圈"
                                "WEIBO" -> "微博"
                                else -> "QQ / QQ空间"
                            }
                        },
                        open = {
                            val cur = HeyboxSettingsStore.string(context, App.KEY_SHARE_CHANNEL, "QQ")
                            choiceReq = ChoiceReq(
                                title = "分享渠道",
                                labels = listOf("QQ / QQ空间", "微信 / 朋友圈", "微博"),
                                currentIndex = when (cur) { "WECHAT" -> 1; "WEIBO" -> 2; else -> 0 },
                                onPick = { index ->
                                    HeyboxSettingsStore.putString(
                                        context, App.KEY_SHARE_CHANNEL,
                                        listOf("QQ", "WECHAT", "WEIBO")[index],
                                    )
                                    bump()
                                },
                            )
                        },
                    )
                }
            }

            group("我的任务（限时任务）")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("自动完成我的任务", "每天自动处理一条发帖类限时任务", App.KEY_MY_TASK_ENABLED, bump)
                    HeySwitch("全自动发帖", "关闭则用户按「发布」", App.KEY_MY_TASK_AUTO_POST, bump)
                    HeySwitch("只做限时任务", "只处理带倒计时的任务", App.KEY_MY_TASK_URGENT_ONLY, bump)
                    HeySwitch("完成后删帖", "任务进度确认推进后删掉刚发的帖子", App.KEY_MY_TASK_DELETE_AFTER, bump)
                    HeySwitch("自动领奖", "任务变成可领取时打开领奖入口", App.KEY_MY_TASK_CLAIM, bump)
                    HeyText("发帖内容", "默认「扣1」，可改成自己的内容", App.KEY_MY_TASK_TEXT, "发帖内容", false, { editReq = it }, bump)
                    HeyText("匹配关键词", "标题/描述命中任一关键词", App.KEY_MY_TASK_KEYWORDS, "关键词（一行一个）", true, { editReq = it }, bump)
                    HeyText("每天最多", "每天最多自动发几条", App.KEY_MY_TASK_MAX_PER_DAY, "条数（数字）", false, { editReq = it }, bump)
                }
            }

            group("动态推送")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("关注动态提醒", "打开小黑盒时检查新动态", App.KEY_WATCH_ENABLED, bump)
                    HeyText("关注对象", "一行一个 userid 或主页链接（最多 30 个）", App.KEY_WATCH_USERS, "关注对象（一行一个）", true, { editReq = it }, bump)
                    HeyText("关注的话题", "一行一个：话题名或话题id|话题名", App.KEY_WATCH_TOPICS, "话题（一行一个）", true, { editReq = it }, bump)
                    HeyText("监控关键词", "一行一个，命中即提醒；regex: 为正则", App.KEY_WATCH_KEYWORDS, "关键词（一行一个）", true, { editReq = it }, bump)
                    HeySwitch("关键词只匹配标题", "不匹配正文", App.KEY_WATCH_TITLE_ONLY, bump)
                    HeySwitch("话题/关键词拉流", "主动拉取话题/关键词最新帖", App.KEY_WATCH_STREAM_FETCH, bump)
                    HeyChoice(
                        "获取时间窗",
                        "只提醒该时间窗内的帖子",
                        currentLabel = {
                            val cur = HeyboxSettingsStore.string(context, App.KEY_WATCH_WINDOW_MIN)
                                .toIntOrNull() ?: 4320
                            WATCH_WINDOW_LABELS[nearestIndex(WATCH_WINDOW_MINUTES, cur)]
                        },
                        open = {
                            val cur = HeyboxSettingsStore.string(context, App.KEY_WATCH_WINDOW_MIN)
                                .toIntOrNull() ?: 4320
                            choiceReq = ChoiceReq(
                                title = "获取时间窗",
                                labels = WATCH_WINDOW_LABELS,
                                currentIndex = nearestIndex(WATCH_WINDOW_MINUTES, cur),
                                onPick = { index ->
                                    HeyboxSettingsStore.putString(
                                        context, App.KEY_WATCH_WINDOW_MIN,
                                        WATCH_WINDOW_MINUTES[index].toString(),
                                    )
                                    bump()
                                },
                            )
                        },
                    )
                    HeyChoice(
                        "检查间隔",
                        "自动检查新动态的间隔",
                        currentLabel = {
                            val cur = HeyboxSettingsStore.string(context, App.KEY_WATCH_INTERVAL_MIN)
                                .toIntOrNull() ?: 10
                            WATCH_INTERVAL_LABELS[nearestIndex(WATCH_INTERVAL_MINUTES, cur)]
                        },
                        open = {
                            val cur = HeyboxSettingsStore.string(context, App.KEY_WATCH_INTERVAL_MIN)
                                .toIntOrNull() ?: 10
                            choiceReq = ChoiceReq(
                                title = "检查间隔",
                                labels = WATCH_INTERVAL_LABELS,
                                currentIndex = nearestIndex(WATCH_INTERVAL_MINUTES, cur),
                                onPick = { index ->
                                    HeyboxSettingsStore.putString(
                                        context, App.KEY_WATCH_INTERVAL_MIN,
                                        WATCH_INTERVAL_MINUTES[index].toString(),
                                    )
                                    bump()
                                },
                            )
                        },
                    )
                    HeySwitch("应用内横幅", "界面顶部弹出提醒", App.KEY_WATCH_BANNER, bump)
                    HeySwitch("系统通知", "通知栏提醒，可点击跳转", App.KEY_WATCH_NOTIFY, bump)
                    HeySwitch("第三方推送", "转发新动态到外部渠道", App.KEY_WATCH_PUSH_ENABLED, bump)
                    HeyText("钉钉机器人", "webhook 地址或 access_token", App.KEY_WATCH_PUSH_DINGTALK, "钉钉 webhook / token", false, { editReq = it }, bump)
                    HeyText("WxPusher", "appToken|topicId 或 appToken|uid:UID", App.KEY_WATCH_PUSH_WXPUSHER, "WxPusher 配置", false, { editReq = it }, bump)
                    HeyText("AstrBot 机器人", "http://主机:6199|群号|令牌", App.KEY_WATCH_PUSH_ONEBOT, "AstrBot 配置", false, { editReq = it }, bump)
                    HeyText("自定义 webhook", "支持 {title}{link} 等占位符", App.KEY_WATCH_PUSH_CUSTOM, "webhook 配置", true, { editReq = it }, bump)
                    Text(
                        "导入关注列表/话题、搜索话题、推荐关键词与测试推送、立即检查在小黑盒内的设置面板里操作。",
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }

            group("通用")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    HeySwitch("扫一扫快捷方式", "长按小黑盒桌面图标，快速打开扫一扫", App.KEY_SCAN_SHORTCUT, bump)
                    HeySwitch("伪装通知权限", "伪装通知已开启，获得签到加成", App.KEY_FAKE_NOTIFICATION, bump)
                    HeySwitch("伪装使用情况权限", "伪装「使用情况访问」已开启", App.KEY_FAKE_USAGE_ACCESS, bump)
                    HeySwitch("返回空使用情况数据", "游戏时长等统计返回空，并阻止后台上传", App.KEY_FAKE_USAGE_EMPTY_DATA, bump)
                    HeySwitch("屏蔽更新", "屏蔽小黑盒更新入口", App.KEY_BLOCK_UPDATE, bump)
                    HeySwitch("记录日志", null, App.KEY_LOG, bump)
                    HeySwitch("详细日志", "关闭时只记错误日志；开启后记录全部并附带帖子信息", App.KEY_VERBOSE_LOG, bump)
                    Text(
                        "日志查看/清除/导出、配置导入/导出在小黑盒内的设置面板里操作；" +
                            "原「重载模块」在工具箱形态下以强停并重开小黑盒代替。",
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
        }
    }
}

/** 分组小标题（与掌盟子页同款式）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.group(title: String) {
    item { SmallTitle(title) }
}

@Composable
private fun HeySwitch(
    title: String,
    summary: String?,
    key: String,
    bump: () -> Unit,
) {
    val context = LocalContext.current
    SuperSwitch(
        title = title,
        summary = summary,
        checked = HeyboxSettingsStore.bool(context, key),
        onCheckedChange = { value ->
            HeyboxSettingsStore.putBool(context, key, value)
            bump()
        },
    )
}

@Composable
private fun HeyText(
    title: String,
    summary: String,
    key: String,
    hint: String,
    multiline: Boolean,
    openEdit: (EditReq) -> Unit,
    bump: () -> Unit,
) {
    val context = LocalContext.current
    val value = HeyboxSettingsStore.string(context, key)
    SuperArrow(
        title = title,
        summary = if (value.isBlank()) summary else value.replace('\n', ' '),
        onClick = { openEdit(EditReq(key, title, hint, multiline)) },
    )
}

@Composable
private fun HeyChoice(
    title: String,
    summary: String,
    currentLabel: () -> String,
    open: () -> Unit,
) {
    SuperArrow(
        title = title,
        summary = summary + "（当前：" + currentLabel() + "）",
        onClick = open,
    )
}

@Composable
private fun HeyEditDialog(
    req: EditReq?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember(req) {
        mutableStateOf(req?.let { HeyboxSettingsStore.string(context, it.key) } ?: "")
    }
    SuperDialog(
        show = req != null,
        title = req?.title ?: "",
        onDismissRequest = onDismiss,
    ) {
        if (req != null) {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = req.hint,
                useLabelAsPlaceholder = true,
                singleLine = !req.multiline,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "保存",
                    onClick = {
                        HeyboxSettingsStore.putString(context, req.key, text)
                        onSaved()
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HeyChoiceDialog(
    req: ChoiceReq?,
    onDismiss: () -> Unit,
) {
    SuperDialog(
        show = req != null,
        title = req?.title ?: "",
        onDismissRequest = onDismiss,
    ) {
        if (req != null) {
            Column(Modifier.fillMaxWidth()) {
                req.labels.forEachIndexed { index, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                req.onPick(index)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            color = if (index == req.currentIndex) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurface
                            },
                        )
                        if (index == req.currentIndex) {
                            Text(
                                "当前",
                                color = MiuixTheme.colorScheme.primary,
                                style = MiuixTheme.textStyles.body2,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun nearestIndex(values: List<Int>, cur: Int): Int {
    var best = 0
    for (i in 1 until values.size) {
        if (kotlin.math.abs(values[i] - cur) < kotlin.math.abs(values[best] - cur)) {
            best = i
        }
    }
    return best
}

/** AI 提供商预设（与原模块 AIClickbaitChecker 的三张表一致）。 */
private val AI_PROVIDER_IDS = listOf(
    "deepseek", "kimi", "qwen", "zhipu", "openai", "openrouter", "local", "custom",
)
private val AI_PROVIDER_LABELS = listOf(
    "DeepSeek", "Kimi（月之暗面）", "通义千问", "智谱 GLM",
    "OpenAI", "OpenRouter", "本地模型（Ollama 等）", "自定义",
)
private val AI_PROVIDER_BASE_URLS = listOf(
    "https://api.deepseek.com/v1",
    "https://api.moonshot.cn/v1",
    "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "https://open.bigmodel.cn/api/paas/v4",
    "https://api.openai.com/v1",
    "https://openrouter.ai/api/v1",
    "http://127.0.0.1:11434/v1",
    "",
)
private val AI_PROVIDER_MODELS = listOf(
    "deepseek-chat", "moonshot-v1-8k", "qwen-turbo", "glm-4-flash",
    "gpt-4o-mini", "openrouter/auto", "qwen2.5:3b", "",
)

/** 动态推送时间窗/间隔（与原模块设置面板的选项表一致）。 */
private val WATCH_WINDOW_LABELS = listOf(
    "30 分钟", "1 小时", "3 小时", "6 小时", "12 小时", "1 天", "3 天", "7 天", "30 天",
)
private val WATCH_WINDOW_MINUTES = listOf(30, 60, 180, 360, 720, 1440, 4320, 10080, 43200)
private val WATCH_INTERVAL_LABELS = listOf(
    "5 分钟", "10 分钟", "15 分钟", "30 分钟", "1 小时", "3 小时", "6 小时", "12 小时",
)
private val WATCH_INTERVAL_MINUTES = listOf(5, 10, 15, 30, 60, 180, 360, 720)
