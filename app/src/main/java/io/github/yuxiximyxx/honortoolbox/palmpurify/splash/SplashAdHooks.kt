package io.github.yuxiximyxx.honortoolbox.palmpurify.splash

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.yuxiximyxx.honortoolbox.palmpurify.ModulePrefs
import io.github.yuxiximyxx.honortoolbox.palmpurify.XposedInterface
import io.github.yuxiximyxx.honortoolbox.palmpurify.XposedModule
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * 只短路开屏广告的展示。
 * 定位失败或钩子出错都放行原来的逻辑，并写明原因。
 * 不碰账号、战绩、资产、活动的接口。
 */
object SplashAdHooks {
    private val installed = AtomicBoolean(false)
    private const val HOOK_LIMIT = 8
    private const val FUSE_MS = 4_000L
    private val endCallbacks = mutableListOf<Method>()
    private val scannedPackages = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var forcePass = false

    @Volatile
    private var foreground = ""

    @Volatile
    private var launcherName: String? = null

    private var fusePosted = false
    private var lastCall: LastCall? = null

    fun install(module: XposedModule, appInfo: ApplicationInfo, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) {
            return
        }
        launcherName = launcherClass(appInfo)
        log(module, Log.INFO, "开始定位开屏广告，启动页=$launcherName")
        watchForeground(module)
        guard(module, "优量汇展示入口") { hookYoulianghui(module, classLoader) }
        guard(module, "穿山甲开屏入口") { hookPangle(module, classLoader) }
        guard(module, "DexKit 特征") { hookByFeatures(module, appInfo, classLoader) }
    }

    private fun hookByFeatures(
        module: XposedModule,
        appInfo: ApplicationInfo,
        classLoader: ClassLoader,
    ) {
        if (!loadDexKit(module, appInfo)) {
            log(module, Log.WARN, "放行：DexKit 不可用，只保留已装上的 SDK 兜底")
            return
        }
        val apkPath = appInfo.sourceDir
        val started = System.currentTimeMillis()
        DexKitBridge.create(apkPath).use { bridge ->
            val markers = listOf("开屏广告", "SplashAD", "splash_ad")
            val methods = linkedSetOf<String>()
            var hooked = 0
            for (marker in markers) {
                if (hooked >= HOOK_LIMIT) {
                    break
                }
                val found = bridge.findMethod {
                    searchPackages("com.tencent")
                    matcher {
                        usingStrings(marker)
                    }
                }
                log(module, Log.INFO, "特征「$marker」命中方法 ${found.size} 个")
                for (data in found) {
                    if (hooked >= HOOK_LIMIT) {
                        break
                    }
                    val method = try {
                        data.getMethodInstance(classLoader)
                    } catch (error: Throwable) {
                        log(module, Log.WARN, "放行：取方法实例失败 ${error.message}")
                        continue
                    }
                    val key = "${method.declaringClass.name}#${method.name}${method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }}"
                    if (!methods.add(key)) {
                        continue
                    }
                    if (!acceptAppMethod(method)) {
                        log(module, Log.INFO, "放行：跳过不适合短路的方法 $key")
                        continue
                    }
                    noteCallbacks(module, bridge, classLoader, method.declaringClass)
                    hookSkip(module, method, "特征「$marker」")
                    hooked += 1
                }
            }
            log(module, Log.INFO, "不结束启动页 $launcherName，也不挂钩它的 onCreate")
            if (hooked == 0) {
                log(module, Log.INFO, "业务层未挂钩任何展示方法，开屏展示保持放行")
            }
        }
        log(module, Log.INFO, "DexKit 结束，用时 ${System.currentTimeMillis() - started}ms")
    }

    private fun noteCallbacks(
        module: XposedModule,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        type: Class<*>,
    ) {
        val packageName = type.name.substringBeforeLast('.', "")
        if (packageName.isEmpty() || !scannedPackages.add(packageName)) {
            return
        }
        logDeclaredMethods(module, type)
        val markers = listOf("onADDismissed", "onNoAD", "无广告", "跳过广告")
        for (marker in markers) {
            val found = try {
                bridge.findMethod {
                    searchPackages(packageName)
                    matcher {
                        usingStrings(marker)
                    }
                }
            } catch (error: Throwable) {
                log(module, Log.WARN, "查找结束回调「$marker」失败：${error.message}")
                continue
            }
            log(module, Log.INFO, "同包结束特征「$marker」命中 ${found.size} 个")
            for (data in found) {
                val method = try {
                    data.getMethodInstance(classLoader)
                } catch (error: Throwable) {
                    log(module, Log.WARN, "结束回调取实例失败：${error.message}")
                    continue
                }
                if (!canTrigger(method)) {
                    log(module, Log.INFO, "不触发 ${signature(method)}，参数或返回类型不适合")
                    continue
                }
                method.isAccessible = true
                endCallbacks += method
                log(module, Log.INFO, "记录结束回调 ${signature(method)}，来自「$marker」")
            }
        }
    }

    private fun logDeclaredMethods(module: XposedModule, type: Class<*>) {
        val names = type.declaredMethods.joinToString { item ->
            "${item.name}/${item.parameterCount}"
        }
        log(module, Log.INFO, "${type.name} 声明方法：$names")
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (!isEndCallbackName(method) || !canTrigger(method)) {
                    continue
                }
                method.isAccessible = true
                endCallbacks += method
                log(module, Log.INFO, "记录结束回调 ${signature(method)}，来自方法名")
            }
            current = current.superclass
        }
    }

    private fun hookYoulianghui(module: XposedModule, classLoader: ClassLoader) {
        val type = Class.forName("com.qq.e.ads.splash.SplashAD", false, classLoader)
        val names = setOf("showAd", "showFullScreenAd", "fetchAndShowIn", "fetchFullScreenAndShowIn")
        var hooked = 0
        for (method in type.declaredMethods) {
            if (method.name !in names) {
                continue
            }
            hookSkip(module, method, "优量汇")
            hooked += 1
        }
        if (hooked == 0) {
            log(module, Log.INFO, "放行：优量汇 SplashAD 没有可短路的展示方法")
        } else {
            log(module, Log.INFO, "已挂钩优量汇展示方法 $hooked 个")
        }
    }

    private fun hookPangle(module: XposedModule, classLoader: ClassLoader) {
        val type = try {
            Class.forName("com.bytedance.sdk.openadsdk.TTAdNative", false, classLoader)
        } catch (_: ClassNotFoundException) {
            log(module, Log.INFO, "放行：未发现穿山甲 TTAdNative")
            return
        }
        var hooked = 0
        for (method in type.methods) {
            if (method.name != "loadSplashAd") {
                continue
            }
            hookSkip(module, method, "穿山甲")
            hooked += 1
        }
        log(
            module,
            Log.INFO,
            if (hooked == 0) "放行：穿山甲没有 loadSplashAd" else "已挂钩穿山甲 loadSplashAd $hooked 个",
        )
    }

    private fun hookSkip(module: XposedModule, method: Method, source: String) {
        val label = "${method.declaringClass.name}#${method.name}"
        val safe = method.returnType == Void.TYPE || method.returnType == java.lang.Boolean.TYPE
        if (!safe) {
            log(module, Log.INFO, "放行：$source $label 返回类型 ${method.returnType.name}，不短路")
            return
        }
        val mode = XposedInterface.ExceptionMode.PROTECTIVE
        module.hook(method).setExceptionMode(mode).intercept { chain ->
            val target = chain.getThisObject()
            val args = chain.getArgs().toTypedArray()
            lastCall = LastCall(method, target, args)
            if (!enabled(module) || forcePass) {
                val reason = if (forcePass) "熔断后放行" else "开关已关"
                log(module, Log.INFO, "放行：$source $label，$reason")
                return@intercept chain.proceed()
            }
            scheduleFuse(module)
            val result = chain.proceed()
            val triggered = triggerEndPath(module, target, method)
            if (method.returnType == java.lang.Boolean.TYPE) {
                log(
                    module,
                    Log.INFO,
                    if (triggered) {
                        "跳过：$source $label 原方法已执行，并已触发结束路径，返回无广告"
                    } else {
                        "放行结果：$source $label 原方法已执行，未找到结束回调，返回值按无广告处理"
                    },
                )
                false
            } else {
                log(
                    module,
                    Log.INFO,
                    if (triggered) {
                        "跳过：$source $label 原方法已执行，并已触发结束路径"
                    } else {
                        "放行结果：$source $label 原方法已执行，未找到结束回调，保留原结果"
                    },
                )
                result
            }
        }
        log(module, Log.INFO, "已挂钩 $source $label")
    }

    private fun triggerEndPath(module: XposedModule, target: Any?, current: Method): Boolean {
        var triggered = false
        if (notifyDismissed(module, target)) {
            triggered = true
        }
        for (method in endCallbacks.toList()) {
            if (method == current) {
                continue
            }
            if (invokeEndCallback(module, method, target)) {
                triggered = true
            }
        }
        return triggered
    }

    private fun invokeEndCallback(module: XposedModule, method: Method, target: Any?): Boolean {
        val receiver = when {
            java.lang.reflect.Modifier.isStatic(method.modifiers) -> null
            target != null && method.declaringClass.isInstance(target) -> target
            else -> findReceiver(target, method.declaringClass)
        }
        if (!java.lang.reflect.Modifier.isStatic(method.modifiers) && receiver == null) {
            return false
        }
        val args = callbackArgs(method) ?: return false
        return try {
            method.invoke(receiver, *args)
            log(module, Log.INFO, "已触发结束路径 ${signature(method)}")
            true
        } catch (error: Throwable) {
            log(module, Log.WARN, "触发结束路径失败 ${signature(method)}：${error.message}")
            false
        }
    }

    private fun callbackArgs(method: Method): Array<Any?>? {
        if (method.parameterCount == 0) {
            return emptyArray()
        }
        val type = method.parameterTypes[0]
        val error = try {
            type.getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java)
                .newInstance(6000, "no ad")
        } catch (_: Throwable) {
            null
        } ?: return null
        return arrayOf(error)
    }

    private fun findReceiver(target: Any?, type: Class<*>): Any? {
        if (target == null) {
            return null
        }
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                field.isAccessible = true
                val value = try {
                    field.get(target)
                } catch (_: Throwable) {
                    null
                }
                if (value != null && type.isInstance(value)) {
                    return value
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun canTrigger(method: Method): Boolean {
        if (method.parameterCount > 1) {
            return false
        }
        val type = method.returnType
        return type == Void.TYPE || type == java.lang.Boolean.TYPE
    }

    private fun isEndCallbackName(method: Method): Boolean {
        val name = method.name.lowercase()
        val keys = listOf("dismiss", "noad", "skip", "onclose", "closead", "gohome", "tohome", "entermain")
        return keys.any { name.contains(it) }
    }

    private fun signature(method: Method): String {
        return "${method.declaringClass.name}#${method.name}/${method.parameterCount}"
    }

    private fun watchForeground(module: XposedModule) {
        val onResume = Activity::class.java.getDeclaredMethod("onResume")
        module.hook(onResume).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            val activity = chain.getThisObject() as? Activity
            if (activity != null) {
                foreground = activity.javaClass.name
            }
            result
        }
    }

    private fun scheduleFuse(module: XposedModule) {
        if (fusePosted) {
            return
        }
        fusePosted = true
        mainHandler.postDelayed({
            val page = foreground
            val launcher = launcherName
            val stuck = page.isNotEmpty() && (page == launcher || page.endsWith(".LauncherActivity"))
            if (!stuck) {
                log(module, Log.INFO, "熔断检查：当前页面 $page，未停在启动页")
                return@postDelayed
            }
            forcePass = true
            log(module, Log.WARN, "熔断：${FUSE_MS}ms 后仍在 $page，后续开屏钩子放行")
            val pending = lastCall
            if (pending == null) {
                return@postDelayed
            }
            try {
                log(module, Log.WARN, "熔断：再执行一次原方法 ${signature(pending.method)}")
                pending.method.invoke(pending.target, *pending.args)
            } catch (error: Throwable) {
                log(module, Log.WARN, "熔断重放失败：${error.message}")
            }
        }, FUSE_MS)
    }

    private class LastCall(
        val method: Method,
        val target: Any?,
        val args: Array<Any?>,
    )

    private fun notifyDismissed(module: XposedModule, target: Any?): Boolean {
        if (target == null) {
            return false
        }
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                field.isAccessible = true
                val listener = try {
                    field.get(target)
                } catch (_: Throwable) {
                    null
                } ?: continue
                val dismissed = listener.javaClass.methods.firstOrNull { item ->
                    item.name == "onADDismissed" && item.parameterCount == 0
                }
                if (dismissed != null) {
                    dismissed.invoke(listener)
                    log(module, Log.INFO, "已回调 onADDismissed ${listener.javaClass.name}")
                    return true
                }
                val failed = listener.javaClass.methods.firstOrNull { item ->
                    item.name == "onNoAD" && item.parameterCount == 1
                }
                if (failed != null) {
                    val errorType = failed.parameterTypes[0]
                    val error = errorType.getDeclaredConstructor(
                        Int::class.javaPrimitiveType,
                        String::class.java,
                    ).newInstance(6000, "no ad")
                    failed.invoke(listener, error)
                    log(module, Log.INFO, "已回调 onNoAD ${listener.javaClass.name}")
                    return true
                }
            }
            current = current.superclass
        }
        return false
    }

    private fun acceptAppMethod(method: Method): Boolean {
        if (method.declaringClass.name.startsWith("com.qq.e.") ||
            method.declaringClass.name.startsWith("com.bytedance.")
        ) {
            return false
        }
        val blob = "${method.declaringClass.name}#${method.name}".lowercase()
        val blocked = listOf("account", "login", "wallet", "战绩", "资产", "账号", "登录")
        if (blocked.any { blob.contains(it) }) {
            return false
        }
        val type = method.returnType
        return type == Void.TYPE || type == java.lang.Boolean.TYPE
    }

    private fun enabled(module: XposedModule): Boolean {
        return try {
            module.getRemotePreferences(ModulePrefs.GROUP)
                .getBoolean(ModulePrefs.KEY_BLOCK_SPLASH_AD, ModulePrefs.DEFAULT_BLOCK_SPLASH_AD)
        } catch (error: Throwable) {
            log(module, Log.WARN, "读取开屏开关失败，按开启处理：${error.message}")
            ModulePrefs.DEFAULT_BLOCK_SPLASH_AD
        }
    }

    private fun loadDexKit(module: XposedModule, appInfo: ApplicationInfo): Boolean {
        return try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val abiDir = when {
                abi.startsWith("arm64") -> "arm64-v8a"
                abi.startsWith("armeabi") -> "armeabi-v7a"
                abi.startsWith("x86_64") -> "x86_64"
                abi.startsWith("x86") -> "x86"
                else -> "arm64-v8a"
            }
            val moduleApk = module.moduleApplicationInfo.sourceDir
            val outDir = File(appInfo.dataDir, "code_cache/palmpurify").apply { mkdirs() }
            val out = File(outDir, "libdexkit.so")
            ZipFile(moduleApk).use { zip ->
                val entry = zip.getEntry("lib/$abiDir/libdexkit.so")
                    ?: error("模块包里没有 lib/$abiDir/libdexkit.so")
                if (!out.exists() || out.length() != entry.size) {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                }
            }
            System.load(out.absolutePath)
            true
        } catch (error: Throwable) {
            log(module, Log.WARN, "加载 DexKit 失败：${error.javaClass.simpleName} ${error.message}")
            false
        }
    }

    private fun launcherClass(appInfo: ApplicationInfo): String? {
        return try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            val context = threadClass.getMethod("getSystemContext").invoke(thread) as Context
            context.packageManager.getLaunchIntentForPackage(appInfo.packageName)?.component?.className
        } catch (_: Throwable) {
            null
        }
    }

    private fun guard(module: XposedModule, step: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            log(module, Log.WARN, "放行：$step 失败 ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun log(module: XposedModule, priority: Int, message: String) {
        try {
            module.log(priority, ModulePrefs.LOG_TAG, message)
        } catch (_: Throwable) {
            Log.println(priority, ModulePrefs.LOG_TAG, message)
        }
    }
}
