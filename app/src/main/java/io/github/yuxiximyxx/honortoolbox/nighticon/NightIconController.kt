package io.github.yuxiximyxx.honortoolbox.nighticon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuxiximyxx.honortoolbox.R
import java.io.File
import kotlin.concurrent.thread

/**
 * 「桌面 · 图标日夜切换」的状态机与全部非 UI 逻辑。
 *
 * v1.1.0：原 NightIconSettingsActivity 的 Activity 级逻辑整体
 * 搬到这里（宿主 MainActivity 持有一份，选主题文件的
 * startActivityForResult 结果由宿主 onActivityResult 转交
 * [onActivityResult]）。行为与原版逐行一致：本地镜像偏好
 * nighticon_settings、解包到私有目录 slots/、下发走
 * [NightIconStore] 落盘通道；只是执行主体从独立 Activity
 * 换成宿主 Activity（context/线程/Toast 均经宿主）。
 */
class NightIconController(private val activity: ComponentActivity) {

    private val TAG = "NightIcon"
    private val local by lazy {
        activity.getSharedPreferences("nighticon_settings", Context.MODE_PRIVATE)
    }

    /** 临时状态（覆盖默认状态文字），null = 按配置推算 */
    private var transientStatus: String? = null
    var uiState by mutableStateOf(NightIconUiState())
        private set

    init {
        // 原 onCreate：先按镜像偏好点亮总开关，再推算状态行
        uiState = uiState.copy(enabled = local.getBoolean(Keys.ENABLED, false))
        render()
    }

    /** 总开关：改镜像 + 同步进落盘配置（保留版本号/令牌）。 */
    fun onEnabledChange(checked: Boolean) {
        local.edit().putBoolean(Keys.ENABLED, checked).apply()
        uiState = uiState.copy(enabled = checked)
        transientStatus = null
        render()
        thread(name = "nighticon-enabled") {
            val cur = NightIconStore.readState(activity) ?: stateFromMirror()
            NightIconStore.writeConfig(activity, cur.copy(enabled = checked))
        }
    }

    // ---------------- 选择主题 ----------------
    fun pick(slot: Keys.Slot) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // .hwt/.hnt 没有标准 MIME，放开类型，选完再按扩展名校验
            // 尽量把初始目录定位到 /storage/emulated/0/Honor/Themes
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Honor/Themes",
                ),
            )
        }
        runCatching { activity.startActivityForResult(intent, slot.ordinal) }
            .onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
    }

    /** 宿主 onActivityResult 转交（原 Activity 的同名回调，逻辑不变）。 */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val slot = Keys.Slot.entries.getOrNull(requestCode) ?: return
        val name = displayName(uri)
        if (!ThemeIconExtractor.isThemeFileName(name)) {
            Toast.makeText(activity, R.string.ni_toast_bad_ext, Toast.LENGTH_LONG).show()
            return
        }
        importTheme(slot, uri, name!!)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** 只读打开所选主题，解出 icons / icons_backup 到私有目录；原主题文件不改动 */
    private fun importTheme(slot: Keys.Slot, uri: Uri, name: String) {
        transientStatus = activity.getString(R.string.ni_st_extracting); render()
        thread(name = "nighticon-extract") {
            val outDir = slotDir(slot)
            val staging = File(activity.cacheDir, "staging_${slot.id}")
            val result = runCatching {
                staging.deleteRecursively()
                val input = activity.contentResolver.openInputStream(uri)
                    ?: error("无法打开所选文件")
                ThemeIconExtractor.extract(input, File(activity.cacheDir, "extract"), staging)
            }
            result.onSuccess {
                // 解包成功才替换旧内容
                outDir.deleteRecursively(); outDir.parentFile?.mkdirs()
                if (!staging.renameTo(outDir)) {
                    staging.copyRecursively(outDir, true); staging.deleteRecursively()
                }
                local.edit()
                    .putString(Keys.displayNameKey(slot), name)
                    .putLong(localVersionKey(slot), System.currentTimeMillis())
                    .putBoolean(Keys.hasBackupKey(slot), it.iconsBackup != null)
                    .apply()
                Log.i(
                    TAG,
                    "${slot.id} 解包完成：$name，png ${it.pngCount} 个，icons_backup=${it.iconsBackup != null}",
                )
            }.onFailure { staging.deleteRecursively() }
            activity.runOnUiThread {
                result.onSuccess { transientStatus = null }.onFailure {
                    if (it is ThemeIconExtractor.NoIconsException) {
                        transientStatus = activity.getString(R.string.ni_st_no_icons)
                        Toast.makeText(
                            activity, R.string.ni_toast_no_icons, Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        transientStatus = null
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.ni_toast_extract_fail, it.message),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                render()
            }
        }
    }

    // ---------------- 立即应用 ----------------
    /** 把本地解出的 icons 写进落盘目录，再写 config（版本号 + apply 令牌），桌面侧复查到后同步并刷新 */
    fun applyNow() {
        transientStatus = activity.getString(R.string.ni_st_pushing); render()
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
                        check(
                            NightIconStore.writeFile(
                                activity, Keys.remoteFile(slot, "icons"), icons,
                            )
                        ) {
                            "${slot.id} icons 下发失败"
                        }
                        val backup = File(slotDir(slot), "icons_backup")
                        if (backup.exists()) {
                            check(
                                NightIconStore.writeFile(
                                    activity, Keys.remoteFile(slot, "icons_backup"), backup,
                                )
                            ) {
                                "${slot.id} icons_backup 下发失败"
                            }
                        } else {
                            NightIconStore.deleteFile(
                                activity, Keys.remoteFile(slot, "icons_backup"),
                            )
                        }
                        local.edit().putLong(pushedVersionKey(slot), v).apply()
                    }
                    if (slot == Keys.Slot.LIGHT) lightVersion = v else darkVersion = v
                }
                val prev = NightIconStore.readState(activity)
                val state = NightIconConfig.State(
                    enabled = enabled,
                    lightVersion = lightVersion,
                    darkVersion = darkVersion,
                    lightHasBackup = local.getBoolean(Keys.hasBackupKey(Keys.Slot.LIGHT), false),
                    darkHasBackup = local.getBoolean(Keys.hasBackupKey(Keys.Slot.DARK), false),
                    applyToken = System.currentTimeMillis(),
                    restartToken = prev?.restartToken ?: 0L,
                )
                check(NightIconStore.writeConfig(activity, state)) { "配置写入失败" }
            }
            activity.runOnUiThread {
                r.onSuccess {
                    transientStatus = activity.getString(R.string.ni_st_applied)
                }.onFailure {
                    transientStatus = "下发失败：${it.message}"
                    Log.e(TAG, "下发失败", it)
                }
                render()
            }
        }
    }

    // 「重启桌面」按钮自工具箱 v1.0.1 起撤出本页、集中到工具箱
    // 首页「重启作用域」列表（实现在 MainActivity，同机制：root
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
        val notSelected = activity.getString(R.string.ni_not_selected)
        val light = local.getString(Keys.displayNameKey(Keys.Slot.LIGHT), null) ?: notSelected
        val dark = local.getString(Keys.displayNameKey(Keys.Slot.DARK), null) ?: notSelected
        val both = Keys.Slot.entries.all { local.getLong(localVersionKey(it), 0) != 0L }
        val enabled = uiState.enabled
        val transient = transientStatus
        val (status, tone) = if (transient != null) {
            transient to toneOf(transient)
        } else {
            when {
                !both -> activity.getString(R.string.ni_st_need_pick) to StatusTone.Idle
                !enabled -> activity.getString(R.string.ni_st_disabled) to StatusTone.Idle
                else -> activity.getString(R.string.ni_st_ready) to StatusTone.Ready
            }
        }
        uiState = uiState.copy(lightName = light, darkName = dark, status = status, tone = tone)
    }

    private fun toneOf(text: String): StatusTone = when (text) {
        activity.getString(R.string.ni_st_ready),
        activity.getString(R.string.ni_st_applied) -> StatusTone.Ready
        activity.getString(R.string.ni_st_extracting),
        activity.getString(R.string.ni_st_pushing) -> StatusTone.Busy
        else -> StatusTone.Idle
    }

    private fun slotDir(slot: Keys.Slot) = File(activity.filesDir, "slots/${slot.id}")
    private fun localVersionKey(slot: Keys.Slot) = "${slot.id}_local_version"
    private fun pushedVersionKey(slot: Keys.Slot) = "${slot.id}_pushed_version"
}
