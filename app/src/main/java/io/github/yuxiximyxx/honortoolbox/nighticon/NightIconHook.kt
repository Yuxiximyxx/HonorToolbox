package io.github.yuxiximyxx.honortoolbox.nighticon

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * 图标日夜切换：荣耀桌面进程（com.hihonor.android.launcher）内
 * 的全部 Hook。由原 NightIcon 模块（libxposed API）等价移植到
 * 传统 Xposed API，业务逻辑（改道判定、软重置、图标刷新反射、
 * 同步落盘）原样保留，只换框架层：
 * - hook 注册：libxposed intercept → XposedBridge.hookMethod +
 *   XC_MethodHook（改道在 before 改 args、翻转检测在 after、
 *   不压暗在 before 置 result）；
 * - 配置通道：libxposed 远程偏好/远程文件 → 落盘
 *   [NightIconConfig]；原「偏好变更监听」改为在改道入口与
 *   配置翻转入口按 config.txt 的 mtime 廉价复查，令牌语义
 *   （apply_token 触发同步、restart_token 触发桌面自杀）不变；
 * - deoptimize：传统 API 无等价能力，未移植——getDarkerBitmap
 *   本体 hook 照装，若它被 ART 内联进调用方，「夜间不压暗」
 *   可能不生效（已知差异，真机验收项）。
 *
 * 只用反编译中确认存在的类/方法：
 * - hihonor.android.hwutil.ZipFileCache.getAndCheckCachedZipFile(String,String) / (String,String,String)
 * - android.content.res.HwResourcesImpl.updateConfiguration(Configuration,int)
 * - android.content.res.HwResourcesImpl.getDarkerBitmap(Bitmap)（private）
 * - HwResourcesImpl 静态字段 needReloadMask/needReloadBorder/needReloadBg/isBackgroundLoaded/sBgList
 * - hihonor.android.hncube.ZipResourcesAdapter.sIsNeedCheckMultiLevel
 * - 桌面：com.hihonor.android.launcher.IconCache.getInstace()/clearCacheAndDb(boolean)、
 *   Launcher.getLauncher()/updateIconCache()、utils.LoadIconUtil.launchUpdateIconTask(List,Runnable)
 * 任一类/方法缺失只打日志，不抛异常。
 */
class NightIconHook private constructor() {

    companion object {
        const val TAG = "NightIcon"
        private const val LAUNCHER_PACKAGE = "com.hihonor.android.launcher"
        private const val ZIP_FILE_CACHE = "hihonor.android.hwutil.ZipFileCache"
        private const val HW_RES_IMPL = "android.content.res.HwResourcesImpl"
        private const val ZIP_RES_ADAPTER = "hihonor.android.hncube.ZipResourcesAdapter"
        private const val L_ICON_CACHE = "com.hihonor.android.launcher.IconCache"
        private const val L_LAUNCHER = "com.hihonor.android.launcher.Launcher"
        private const val L_LOAD_ICON_UTIL = "com.hihonor.android.launcher.utils.LoadIconUtil"

        /** 统一入口分发：只在桌面主进程安装（桌面子进程不处理图标）。 */
        fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
            if (lpparam.packageName != LAUNCHER_PACKAGE) return
            if (lpparam.processName != LAUNCHER_PACKAGE) {
                XposedBridge.log("[$TAG] 跳过非桌面主进程：${lpparam.processName}")
                return
            }
            try {
                NightIconHook().installInLauncher(lpparam)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] 桌面 Hook 初始化失败: $t")
            }
        }
    }

    // ---------------- 状态 ----------------
    @Volatile private var enabled = false
    /** null = 尚未确定（首次使用时从全局配置读） */
    @Volatile private var night: Boolean? = null
    /** 已落地并校验过的槽位目录；null = 未就绪，此时不改道（走系统原主题） */
    private val readyDir = arrayOfNulls<String>(Keys.Slot.entries.size)

    private lateinit var baseDir: String
    private var hostClassLoader: ClassLoader? = null
    @Volatile private var config: NightIconConfig.State = NightIconConfig.State()
    @Volatile private var configMtime: Long = 0L
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "NightIcon-sync") }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val darkerCalls = AtomicInteger()
    private val darkerSkipped = AtomicInteger()
    @Volatile private var lastSwapLog: String? = null
    @Volatile private var lastRestartToken: Long = 0L
    @Volatile private var lastApplyToken: Long = 0L
    @Volatile private var lastUiModeCheck: String? = null

    // ---------------- 入口 ----------------
    private fun installInLauncher(lpparam: XC_LoadPackage.LoadPackageParam) {
        baseDir = lpparam.appInfo.dataDir + "/files/nighticon"
        hostClassLoader = lpparam.classLoader
        i("开始安装 Hook：baseDir=$baseDir")

        // 初始配置：读一次并记下令牌基线（不触发令牌动作）
        val initial = NightIconConfig.readState()
        if (initial != null) {
            config = initial
            configMtime = NightIconConfig.configMtime()
            enabled = initial.enabled
            lastRestartToken = initial.restartToken
            lastApplyToken = initial.applyToken
        }
        i("总开关=$enabled")

        hookZipFileCache()
        hookUpdateConfiguration()
        hookGetDarkerBitmap()
        i("传统 API 无 deoptimize：getDarkerBitmap 调用方去内联未做，夜间不压暗以真机为准")

        // 首次同步放后台：几十 MB 拷贝不能卡桌面启动；同步完成前不改道
        worker.execute { syncAndApply(refreshLauncher = false) }
    }

    /**
     * 配置复查（替代原远程偏好变更监听）：config.txt 的 mtime
     * 变了才重读。restart_token 变化（非 0 且与上次不同）→ 桌面
     * 进程自杀；apply_token 或总开关变化 → 重新同步并刷新图标。
     */
    private fun refreshConfig() {
        val mtime = NightIconConfig.configMtime()
        if (mtime == 0L || mtime == configMtime) return
        val next = NightIconConfig.readState() ?: return
        configMtime = mtime
        val prev = config
        config = next
        enabled = next.enabled
        if (next.restartToken != 0L && next.restartToken != lastRestartToken) {
            lastRestartToken = next.restartToken
            w("收到「重启桌面」指令，桌面进程自杀（系统会自动拉起桌面）")
            Process.killProcess(Process.myPid())
            return
        }
        lastRestartToken = next.restartToken
        if (next.applyToken != lastApplyToken || next.enabled != prev.enabled) {
            lastApplyToken = next.applyToken
            worker.execute { syncAndApply(refreshLauncher = true) }
        }
    }

    // ---------------- Hook 1：ZipFileCache 改道 ----------------
    private fun hookZipFileCache() {
        val cls = findClass(ZIP_FILE_CACHE) ?: return
        val targets = cls.declaredMethods.filter {
            it.name == "getAndCheckCachedZipFile" && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.all { p -> p == String::class.java } &&
                (it.parameterTypes.size == 2 || it.parameterTypes.size == 3)
        }
        if (targets.isEmpty()) { w("未找到 $ZIP_FILE_CACHE.getAndCheckCachedZipFile，跳过改道"); return }
        for (m in targets) {
            runCatching {
                m.isAccessible = true
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        refreshConfig()
                        val path = param.args[0] as String?
                        val zip = param.args[1] as String?
                        val target = redirectTarget(path, zip) ?: return
                        logSwap(param.args.size, path, zip, target)
                        param.args[0] = target
                    }
                })
                i("已 Hook ZipFileCache.getAndCheckCachedZipFile(${m.parameterTypes.size} 参)")
            }.onFailure { e("Hook ZipFileCache(${m.parameterTypes.size} 参) 失败", it) }
        }
    }

    private fun redirectTarget(path: String?, zip: String?): String? {
        if (!enabled || !HookLogic.shouldRedirect(path, zip)) return null
        return readyDir[currentSlot().ordinal]
    }

    private fun logSwap(argc: Int, from: String?, zip: String?, to: String) {
        val msg = "路径替换[${argc}参] $from/$zip → $to/$zip"
        // 每次都写 logcat（调试 tag NightIcon）；LSPosed 日志只在内容变化时写，避免刷屏
        Log.d(TAG, msg)
        if (msg != lastSwapLog) { lastSwapLog = msg; XposedBridge.log("[$TAG] $msg") }
    }

    // ---------------- Hook 2：深浅翻转 → 软重置 ----------------
    private fun hookUpdateConfiguration() {
        val cls = findClass(HW_RES_IMPL) ?: return
        val m = findMethod(cls, "updateConfiguration", Configuration::class.java, Int::class.javaPrimitiveType!!) ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    refreshConfig()
                    val cfg = param.args[0] as? Configuration ?: return
                    onConfig(cfg, param.thisObject)
                }
            })
            i("已 Hook HwResourcesImpl.updateConfiguration")
        }.onFailure { e("Hook updateConfiguration 失败", it) }
    }

    private fun onConfig(cfg: Configuration, impl: Any?) {
        val argNight = HookLogic.isNight(cfg.uiMode)
        // 对比 mResourcesImpl 的 uiMode（ResourcesImpl 可能被强制改回白天）
        val implMode = runCatching {
            val f = impl!!.javaClass.getDeclaredField("mResourcesImpl").apply { isAccessible = true }
            val ri = f.get(impl)!!
            (ri.javaClass.getMethod("getConfiguration").invoke(ri) as Configuration).uiMode
        }.getOrNull()
        val check = "入参 uiMode=0x${Integer.toHexString(cfg.uiMode)}，mResourcesImpl uiMode=" +
            (implMode?.let { "0x" + Integer.toHexString(it) } ?: "读取失败") +
            (if (implMode == null) "" else if (HookLogic.isNight(implMode) == argNight) "（一致）" else "（不一致！）")
        if (check != lastUiModeCheck) { lastUiModeCheck = check; i(check) }

        val old = night
        if (old == argNight) return
        synchronized(this) {
            if (night == argNight) return
            night = argNight
        }
        i("深浅翻转：${old?.let { if (it) "夜间" else "白天" } ?: "未知"} → ${if (argNight) "夜间" else "白天"}，" +
            "本轮 getDarkerBitmap 调用 ${darkerCalls.getAndSet(0)} 次（跳过 ${darkerSkipped.getAndSet(0)} 次）")
        softReset("深浅翻转")
        // 桌面自己会在 uiMode 变化时重建 IconCache 并重载全部图标（反编译已确认），这里不额外刷新
    }

    // ---------------- Hook 3：夜间套不再压暗 ----------------
    private fun hookGetDarkerBitmap() {
        val cls = findClass(HW_RES_IMPL) ?: return
        val m = findMethod(cls, "getDarkerBitmap", Bitmap::class.java) ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val n = darkerCalls.incrementAndGet()
                    val skip = enabled && currentSlot() == Keys.Slot.DARK && readyDir[Keys.Slot.DARK.ordinal] != null
                    if (n <= 3 || n % 200 == 0) Log.d(TAG, "getDarkerBitmap 被调用（第 $n 次），${if (skip) "夜间套生效，原样返回" else "走原逻辑"}")
                    if (skip) {
                        darkerSkipped.incrementAndGet()
                        param.result = param.args[0]
                    }
                }
            })
            i("已 Hook HwResourcesImpl.getDarkerBitmap")
        }.onFailure { e("Hook getDarkerBitmap 失败", it) }
    }

    // ---------------- 软重置 ----------------
    /** 只把静态底板缓存标脏，不 recycle、不调用 clearHwThemeZipsAndSomeIcons */
    private fun softReset(reason: String) {
        val done = mutableListOf<String>()
        findClass(HW_RES_IMPL)?.let { c ->
            for (f in listOf("needReloadMask", "needReloadBorder", "needReloadBg")) {
                if (setStatic(c, f, true)) done += f
            }
            if (setStatic(c, "isBackgroundLoaded", false)) done += "isBackgroundLoaded"
            if (setStatic(c, "sBgList", null)) done += "sBgList"
        }
        findClass(ZIP_RES_ADAPTER)?.let { c ->
            if (setStatic(c, "sIsNeedCheckMultiLevel", true)) done += "sIsNeedCheckMultiLevel"
        }
        i("软重置（$reason）：$done")
    }

    // ---------------- 文件同步 ----------------
    /** 把设置侧落盘的图标 zip 拷到桌面私有目录并校验；全部完成后才启用槽位 */
    private fun syncAndApply(refreshLauncher: Boolean) {
        val state = config
        enabled = state.enabled
        for (slot in Keys.Slot.entries) {
            readyDir[slot.ordinal] = runCatching { syncSlot(state, slot) }
                .onFailure { e("同步 ${slot.id} 失败，该槽位不改道", it) }.getOrNull()
        }
        i("同步完成：总开关=$enabled 白天=${readyDir[0] ?: "未就绪"} 夜间=${readyDir[1] ?: "未就绪"}")
        softReset("同步/立即应用")
        if (refreshLauncher) refreshLauncherIcons()
    }

    private fun syncSlot(state: NightIconConfig.State, slot: Keys.Slot): String? {
        val version = state.version(slot)
        val slotRoot = File(baseDir, slot.id)
        if (version == 0L) { slotRoot.deleteRecursively(); return null }
        val dir = File(HookLogic.slotDir(baseDir, slot.id, version))
        val marker = File(dir, ".ok")
        if (!marker.exists()) {
            dir.deleteRecursively(); dir.mkdirs()
            copyLanded(Keys.remoteFile(slot, "icons"), File(dir, "icons"))
            if (state.hasBackup(slot)) {
                runCatching { copyLanded(Keys.remoteFile(slot, "icons_backup"), File(dir, "icons_backup")) }
                    .onFailure { File(dir, "icons_backup").delete(); w("icons_backup 拷贝失败，忽略：${it.message}") }
            }
            marker.writeText(version.toString())
            i("${slot.id} v$version 已落地：${File(dir, "icons").length()} 字节")
        }
        // 清理旧版本目录（已被 ZipFileCache 打开的旧文件删掉后句柄仍有效）
        slotRoot.listFiles()?.filter { it.name != dir.name }?.forEach { it.deleteRecursively() }
        return dir.path
    }

    private fun copyLanded(name: String, out: File) {
        val src = NightIconConfig.resolveFile(name) ?: error("落盘文件不存在：$name")
        val tmp = File(out.path + ".tmp")
        src.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        // 校验：必须能当 zip 打开且有条目，否则不启用（ZipFileCache 连 null 也会永久缓存）
        val n = ZipFile(tmp).use { it.size() }
        require(n > 0) { "$name 是空 zip" }
        if (!tmp.renameTo(out)) error("重命名失败：$tmp")
    }

    // ---------------- 立即应用时让桌面重载图标 ----------------
    private fun refreshLauncherIcons() {
        val cl = hostClassLoader ?: return
        // 清 IconCache 内存与 app_icons.db（同一 uiMode 下 DB 条目仍"新鲜"，不清会继续用旧图）
        runCatching {
            val c = Class.forName(L_ICON_CACHE, false, cl)
            val ic = c.getMethod("getInstace").invoke(null)
            if (ic != null) c.getMethod("clearCacheAndDb", Boolean::class.javaPrimitiveType).invoke(ic, true)
            i("IconCache.clearCacheAndDb(true) 完成${if (ic == null) "（实例为空，跳过）" else ""}")
        }.onFailure { w("IconCache.clearCacheAndDb 调用失败：$it") }
        mainHandler.post {
            runCatching {
                val c = Class.forName(L_LAUNCHER, false, cl)
                val l = c.getMethod("getLauncher").invoke(null)
                if (l != null) c.getMethod("updateIconCache").invoke(l)
                i("Launcher.updateIconCache ${if (l == null) "跳过（无实例）" else "完成"}")
            }.onFailure { w("Launcher.updateIconCache 调用失败：$it") }
            runCatching {
                Class.forName(L_LOAD_ICON_UTIL, false, cl)
                    .getMethod("launchUpdateIconTask", List::class.java, Runnable::class.java)
                    .invoke(null, null, null)
                i("LoadIconUtil.launchUpdateIconTask 已触发")
            }.onFailure { w("LoadIconUtil.launchUpdateIconTask 调用失败（可用「重启桌面」兜底）：$it") }
        }
    }

    // ---------------- 工具 ----------------
    private fun currentSlot(): Keys.Slot {
        val n = night ?: initialNight().also { night = it; i("初始深浅：${if (it) "夜间" else "白天"}") }
        return if (n) Keys.Slot.DARK else Keys.Slot.LIGHT
    }

    /** 初值取 ActivityThread 全局配置，失败退回 Resources.getSystem() */
    private fun initialNight(): Boolean {
        val mode = runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val t = at.getMethod("currentActivityThread").invoke(null)!!
            (at.getMethod("getConfiguration").invoke(t) as Configuration).uiMode
        }.getOrElse { Resources.getSystem().configuration.uiMode }
        return HookLogic.isNight(mode)
    }

    private fun findClass(name: String): Class<*>? =
        runCatching { XposedHelpers.findClass(name, hostClassLoader) }
            .onFailure { w("类不存在：$name（相关功能跳过）") }.getOrNull()

    private fun findMethod(c: Class<*>, name: String, vararg types: Class<*>): Method? =
        runCatching { c.getDeclaredMethod(name, *types).apply { isAccessible = true } }
            .onFailure { w("方法不存在：${c.name}.$name（相关功能跳过）") }.getOrNull()

    private fun setStatic(c: Class<*>, field: String, value: Any?): Boolean = runCatching {
        c.getDeclaredField(field).apply { isAccessible = true }.set(null, value); true
    }.getOrElse { w("写静态字段失败：${c.simpleName}.$field：$it"); false }

    private fun i(msg: String) { Log.i(TAG, msg); XposedBridge.log("[$TAG] $msg") }
    private fun w(msg: String) { Log.w(TAG, msg); XposedBridge.log("[$TAG] $msg") }
    private fun e(msg: String, t: Throwable) { Log.e(TAG, msg, t); XposedBridge.log("[$TAG] $msg: $t") }
}
