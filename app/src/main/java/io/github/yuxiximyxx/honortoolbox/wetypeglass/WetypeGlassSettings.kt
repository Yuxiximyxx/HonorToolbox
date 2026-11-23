package io.github.yuxiximyxx.honortoolbox.wetypeglass

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superboyyy.mosinputglass.Config
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.LocalLpBottomInset
import io.github.yuxiximyxx.honortoolbox.libraryplus.ui.MiuixPage
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 「输入法玻璃」设置子页（v1.8.0 合入 MOS液态输入，原模块
 * 作者酷安@熠寻）：原 SettingsActivity 在 MagicOS 上起作用
 * 的设置项全量搬到这里（MIUIX 组件重排，分组与原版一致：
 * 主卡片/输入法/风格/常用/高级四组——背景、按键、拼音行、
 * 玻璃质感、场景），键名、默认值、取值范围与原版逐字一致，
 * 读写走 [WgSettingsStore] 镜像 + [WetypeGlassConfigProvider]
 * 直供两个输入法进程。原独立版的远程配置不迁移，新装即默认。
 *
 * 与原版的呈现差异：原版的数值滑杆带文本框直填，这里统一
 * 用 miuix Slider 等效调节（范围/步进与原版 SeekBar 一致）；
 * 底色原版是取色行，这里点开对话框填十六进制色值（原版
 * colorRow 本质也是文本编辑）。设置改动在下次呼出键盘时
 * 生效（hook 端快照节流重拉），或点「重启输入法」立即生效。
 */
private class ColorReq(val key: String, val title: String)

@Composable
internal fun WetypeGlassScreen(
    onBack: () -> Unit,
    bottomInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { tick++ }
    var colorReq by remember { mutableStateOf<ColorReq?>(null) }
    var resetAsk by remember { mutableStateOf(false) }
    var testText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        WgSettingsStore.seedIfNeeded(context)
        withContext(Dispatchers.IO) {
            WgSettingsStore.cacheHonorLogo(context)
            WgSettingsStore.cacheWeFont(context)
        }
        bump()
    }

    // 与原 SettingsActivity 同口径的显隐判断（本机为荣耀液态玻璃档）
    val kind = remember { Config.glassKind() }
    val tintOn = kind != Config.GLASS_HYPER
    val honorGlass = kind == Config.GLASS_HONOR
    val isHonor = remember { Config.isHonor() }
    val hasWechat = remember { WgSettingsStore.installed(context, Config.PKG_WECHAT) }
    val hasDoubao = remember { WgSettingsStore.installed(context, Config.PKG_DOUBAO) }
    val hasSearch = remember { WgSettingsStore.installed(context, Config.SEARCH_PKG) }

    CompositionLocalProvider(LocalLpBottomInset provides bottomInset) {
        MiuixPage(
            title = "输入法玻璃",
            onBack = onBack,
            extras = {
                WgColorDialog(
                    req = colorReq,
                    onDismiss = { colorReq = null },
                    onSaved = { colorReq = null; bump() },
                )
                SuperDialog(
                    show = resetAsk,
                    title = "恢复默认设置",
                    onDismissRequest = { resetAsk = false },
                    // 弹窗自身底部 margin 抬过悬浮底栏（只动弹窗、不动页面与沉浸）
                    outsideMargin = DpSize(12.dp, LocalLpBottomInset.current + 12.dp),
                ) {
                    Text(
                        "恢复所有设置为默认值？",
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
                                WgSettingsStore.applyPreset(context, null)
                                resetAsk = false
                                bump()
                                Toast.makeText(context, "已恢复默认设置", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        ) {
            @Suppress("UNUSED_EXPRESSION") tick
            // ---- 主卡片：总开关 + 重启输入法 + 试用输入框 ----
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    WgSwitch(
                        "总开关", "给微信/豆包输入法的键盘换上系统玻璃（MOS液态输入 · 作者 酷安@熠寻）",
                        Config.ENABLE, bump,
                    )
                    SuperArrow(
                        title = "重启输入法",
                        summary = "改动没有生效时点这里（root 强停两个输入法）",
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { restartImesViaRoot() }
                                Toast.makeText(
                                    context,
                                    if (ok) "已重启输入法" else "重启失败：root 不可用，请手动强停输入法",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                    TextField(
                        value = testText,
                        onValueChange = { testText = it },
                        label = "点这里唤出键盘，边调边看",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        "设置经直供通道同步给输入法进程，下次呼出键盘时生效；装好本版后请停用旧的独立版 MOS液态输入，避免双重 hook。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }

            // ---- 输入法 ----
            wgGroup("输入法")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    WgSwitch(
                        "微信输入法", if (hasWechat) null else "未安装",
                        Config.IME_WECHAT, bump, enabled = hasWechat,
                    )
                    WgSwitch(
                        "豆包输入法", if (hasDoubao) null else "未安装",
                        Config.IME_DOUBAO, bump, enabled = hasDoubao,
                    )
                }
            }

            // ---- 风格（预设） ----
            wgGroup("风格")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                        for ((name, preset) in Config.PRESETS) {
                            TextButton(
                                text = name.replace("（默认）", ""),
                                onClick = {
                                    WgSettingsStore.applyPreset(context, preset)
                                    bump()
                                    Toast.makeText(context, "已切换到「$name」", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // ---- 常用 ----
            wgGroup("常用")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    if (tintOn) {
                        WgSlider("背景不透明度", Config.BG_ALPHA, 0f, 1f, 0.01f, "%", tick)
                    }
                    WgSlider("键盘圆角", Config.RADIUS, 0f, 40f, 1f, "dp", tick)
                    WgSlider("按键圆角", Config.KEY_RADIUS, 0f, 16f, 0.5f, "dp", tick)
                    WgSwitch(
                        "iOS 按键配色", "按键同色、无描边无阴影",
                        Config.KEY_STYLE, bump, onValue = "ios", offValue = "wechat",
                    )
                    WgSwitch(
                        "荣耀 logo", "左上角换成荣耀圆环",
                        Config.LOGO, bump, onValue = "honor", offValue = "wechat",
                    )
                    if (isHonor) {
                        WgSwitch(
                            "导航栏语音键", "点一下开始说话，按住说话松开结束",
                            Config.VOICE, bump,
                        )
                        WgSwitch(
                            "荣耀键盘振感", "按键振动换成系统为键盘调的振感",
                            Config.HONOR_HAPTIC, bump,
                        )
                    }
                }
            }

            // ---- 高级 · 背景 ----
            wgGroup("高级 · 背景")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    if (tintOn) {
                        WgSlider("模糊强度", Config.BLUR, 0f, 120f, 1f, "", tick)
                    }
                    if (honorGlass) {
                        WgSlider("饱和度", Config.SAT, 0.5f, 2f, 0.05f, "x", tick)
                    }
                    if (tintOn) {
                        WgColorRow("浅色底色", Config.BG_LIGHT) { colorReq = it }
                        WgColorRow("深色底色", Config.BG_DARK) { colorReq = it }
                    }
                    WgSwitch("去掉输入法自带底板", null, Config.SKIP, bump)
                }
            }

            // ---- 高级 · 按键 ----
            wgGroup("高级 · 按键")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    WgSlider("按键不透明度", Config.KEY_ALPHA, 0.3f, 1f, 0.01f, "%", tick)
                    WgSlider("浅色按键亮度", Config.KEY_LIGHT, 0.2f, 1f, 0.01f, "%", tick)
                    WgSlider("深色按键亮度", Config.KEY_DARK, 0.05f, 0.4f, 0.01f, "%", tick)
                    WgSwitch(
                        "豆包用微信的英文字体", "仅当手机上安装微信输入法时生效",
                        Config.DOUBAO_WE_FONT, bump, enabled = hasWechat && hasDoubao,
                    )
                }
            }

            // ---- 高级 · 拼音行位置 ----
            wgGroup("高级 · 拼音行位置")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    WgSlider("微信下移", Config.COMPOSE_DY, 0f, 12f, 0.5f, "dp", tick)
                    WgSlider("豆包下移", Config.COMPOSE_DY_DOUBAO, -4f, 12f, 0.5f, "dp", tick)
                    WgSlider("候选栏水平偏移", Config.CAND_X, -20f, 40f, 1f, "dp", tick)
                }
            }

            // ---- 高级 · 玻璃质感 ----
            wgGroup("高级 · 玻璃质感")
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    if (honorGlass) {
                        WgSlider("折射", Config.REFRACTION, 0f, 1f, 0.01f, "", tick)
                        WgSlider("厚度", Config.THICKNESS, 0f, 1f, 0.01f, "", tick)
                        WgSlider("深度", Config.DEPTH, 0f, 1f, 0.01f, "", tick)
                        WgSlider("色散", Config.DISPERSION, 0f, 1f, 0.01f, "", tick)
                    }
                    WgSwitch("边缘高光", null, Config.EDGE, bump)
                }
            }

            // ---- 高级 · 场景 ----
            if (hasSearch) {
                wgGroup("高级 · 场景")
                item {
                    Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                        WgSwitch(
                            "桌面搜索里铺满", "和搜索框外圈同色、不加圆角",
                            Config.SEARCH_FILL, bump,
                        )
                    }
                }
            }

            // ---- 恢复默认 ----
            item {
                Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                    SuperArrow(
                        title = "恢复默认设置",
                        onClick = { resetAsk = true },
                    )
                }
            }
        }
    }
}

private fun LazyListScope.wgGroup(title: String) {
    item { SmallTitle(title) }
}

@Composable
private fun WgSwitch(
    title: String,
    summary: String?,
    key: String,
    bump: () -> Unit,
    enabled: Boolean = true,
    onValue: String = "1",
    offValue: String = "0",
) {
    val context = LocalContext.current
    SuperSwitch(
        title = title,
        summary = summary,
        checked = WgSettingsStore.get(context, key) == onValue,
        onCheckedChange = { value ->
            WgSettingsStore.put(context, key, if (value) onValue else offValue)
            bump()
        },
        enabled = enabled,
    )
}

/** 数值格式化：与原版存字符串的口径一致（整数不带小数点，其余最多两位小数去尾零）。 */
private fun fmtValue(v: Float): String {
    if (v == v.toLong().toFloat()) return v.toLong().toString()
    return String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}

@Composable
private fun WgSlider(
    title: String,
    key: String,
    min: Float,
    max: Float,
    step: Float,
    unit: String,
    tick: Int,
) {
    val context = LocalContext.current
    val stored = WgSettingsStore.get(context, key).toFloatOrNull() ?: 0f
    var local by remember(key, tick) { mutableFloatStateOf(stored.coerceIn(min, max)) }
    val display = when (unit) {
        "%" -> "${(local * 100).roundToInt()}%"
        "x" -> "${fmtValue(local)}x"
        "dp" -> "${fmtValue(local)}dp"
        else -> fmtValue(local)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                display,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Slider(
            value = local,
            onValueChange = {
                local = it
                WgSettingsStore.put(context, key, fmtValue(it))
            },
            valueRange = min..max,
            steps = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WgColorRow(
    title: String,
    key: String,
    onEdit: (ColorReq) -> Unit,
) {
    val context = LocalContext.current
    SuperArrow(
        title = title,
        summary = WgSettingsStore.get(context, key),
        onClick = { onEdit(ColorReq(key, title)) },
    )
}

@Composable
private fun WgColorDialog(
    req: ColorReq?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember(req) {
        mutableStateOf(req?.let { WgSettingsStore.get(context, it.key) } ?: "")
    }
    var error by remember(req) { mutableStateOf(false) }
    SuperDialog(
        show = req != null,
        title = req?.title ?: "",
        onDismissRequest = onDismiss,
        // 弹窗自身底部 margin 抬过悬浮底栏（只动弹窗、不动页面与沉浸）
        outsideMargin = DpSize(12.dp, LocalLpBottomInset.current + 12.dp),
    ) {
        if (req != null) {
            TextField(
                value = text,
                onValueChange = { text = it; error = false },
                label = if (error) "格式不对，应为 #RRGGBB" else "十六进制色值，如 #CDCFD8",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "保存",
                    onClick = {
                        val v = text.trim()
                        if (v.matches(Regex("^#[0-9A-Fa-f]{6}$")) ||
                            v.matches(Regex("^#[0-9A-Fa-f]{8}$"))
                        ) {
                            WgSettingsStore.put(context, req.key, v.uppercase())
                            onSaved()
                        } else {
                            error = true
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 「重启输入法」：root 强停两个输入法包（原版是写远程配置让输入法进程自杀，合入后等效实现）。 */
private fun restartImesViaRoot(): Boolean = runCatching {
    val p = ProcessBuilder(
        "su", "-c",
        "am force-stop ${Config.PKG_WECHAT}; am force-stop ${Config.PKG_DOUBAO}",
    ).redirectErrorStream(true).start()
    p.waitFor() == 0
}.getOrDefault(false)
