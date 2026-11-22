package io.github.yuxiximyxx.honortoolbox.palmpurify.scout

import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import io.github.yuxiximyxx.honortoolbox.palmpurify.ModulePrefs
import io.github.yuxiximyxx.honortoolbox.palmpurify.XposedInterface
import io.github.yuxiximyxx.honortoolbox.palmpurify.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 只观察、只打日志，不改掌盟界面，也不碰账号、战绩、资产、活动接口。
 * 任何异常都吞掉并放行原逻辑。
 */
object ScoutHooks {
    private const val PREFIX = "PalmPurify 侦察"
    private const val HOME = "com.tencent.zone.main.zm.GameHomeActivity"
    private const val TAB_ROW = "tab_title_content"
    private val installed = AtomicBoolean(false)
    private val seen = Collections.synchronizedSet(mutableSetOf<String>())
    private val hookedAdapters = Collections.synchronizedSet(mutableSetOf<String>())
    private val dumpedHomes = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var module: XposedModule? = null

    @Volatile
    private var homeVisible = false

    @Volatile
    private var awaitNavigationUntil = 0L

    fun install(host: XposedModule, appInfo: ApplicationInfo) {
        if (!installed.compareAndSet(false, true)) {
            return
        }
        module = host
        guard("枚举组件") { logManifest(appInfo) }
        guard("弹窗") { hookDialogs() }
        guard("跳转") { hookNavigation() }
        guard("底栏点击") { hookNavigationClicks() }
        guard("页面") { hookActivities() }
        guard("推送自启") { hookStarts() }
        guard("摇一摇") { hookShake() }
        note("侦察钩子已安装，不改变界面")
    }

    private fun hookActivities() {
        val onResume = Activity::class.java.getDeclaredMethod("onResume")
        val onCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
        hook(onResume) { chain ->
            val result = chain.proceed()
            val activity = chain.getThisObject() as? Activity ?: return@hook result
            homeVisible = activity.javaClass.name == HOME
            if (homeVisible && dumpedHomes.add(activity)) {
                activity.window?.decorView?.post {
                    guard("底栏") { dumpTabs(activity) }
                    guard("首页列表") { dumpHomeList(activity) }
                }
            }
            result
        }
        hook(onCreate) { chain ->
            val result = chain.proceed()
            val activity = chain.getThisObject() as? Activity
            val simple = activity?.javaClass?.simpleName.orEmpty()
            if (simple.endsWith("Dialog") || simple.endsWith("Popup")) {
                guard("弹窗页") { logWindow(activity?.javaClass?.name ?: simple, activity?.window?.decorView) }
            }
            result
        }
    }

    private fun dumpTabs(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val row = findByEntry(decor, TAB_ROW)
        if (row !is ViewGroup) {
            note("底栏未找到 $TAB_ROW")
            return
        }
        note("底栏容器 ${describe(row)} 父链=${chain(row, 8)}")
        var index = 0
        for (childIndex in 0 until row.childCount) {
            val child = row.getChildAt(childIndex)
            if (child.visibility != View.VISIBLE) {
                continue
            }
            note("底栏tab 序号=$index ${describe(child)} 文字=${textOf(child, 20)}")
            index += 1
        }
        val plus = findPlus(decor)
        if (plus == null) {
            note("未找到居中的加号候选")
        } else {
            note("中间加号 ${describe(plus)} 父链=${chain(plus, 8)}")
        }
    }

    private fun dumpHomeList(activity: Activity) {
        val content = activity.window?.decorView ?: return
        val recycler = findRecyclers(content).maxByOrNull { it.width * it.height }
        if (recycler == null) {
            note("首页未找到 RecyclerView")
            return
        }
        val adapter = recycler.javaClass.methods.firstOrNull { it.name == "getAdapter" && it.parameterCount == 0 }
            ?.invoke(recycler)
        note("首页列表 ${describe(recycler)} Adapter=${adapter?.javaClass?.name ?: "空"}")
        if (adapter != null) {
            hookAdapter(adapter.javaClass)
        }
    }

    private fun hookAdapter(type: Class<*>) {
        if (!hookedAdapters.add(type.name)) {
            return
        }
        val method = findBind(type) ?: run {
            note("首页列表 ${type.name} 没有可观察的 onBindViewHolder")
            return
        }
        hook(method) { chain ->
            val result = chain.proceed()
            guard("列表项") { logItem(chain.getThisObject(), chain.getArg(0), chain.getArg(1) as? Int) }
            result
        }
        note("已观察首页列表绑定 ${type.name}#${method.name}")
    }

    private fun logItem(adapter: Any?, holder: Any?, position: Int?) {
        if (!homeVisible || adapter == null || holder == null || position == null || position < 0) {
            return
        }
        if (seen.size > 240) {
            return
        }
        val itemView = findItemView(holder) ?: return
        val viewType = try {
            adapter.javaClass.getMethod("getItemViewType", Int::class.javaPrimitiveType)
                .invoke(adapter, position)
        } catch (_: Throwable) {
            "未知"
        }
        val words = textOf(itemView, 20)
        val key = "item|$viewType|${idName(itemView)}|$words"
        if (!seen.add(key)) {
            return
        }
        val item = itemAt(adapter, position)
        val typeField = typeFields(item)
        note(
            "首页item viewType=$viewType 根布局=${idName(itemView)} 文字=$words" +
                if (typeField.isEmpty()) "" else " type字段=$typeField",
        )
    }

    private fun hookDialogs() {
        hookNamed(Dialog::class.java, "show")
        hookIfExists(
            "androidx.fragment.app.DialogFragment",
            listOf("show", "showNow"),
        )
        hookNamed(PopupWindow::class.java, "showAtLocation")
        hookNamed(PopupWindow::class.java, "showAsDropDown")
    }

    private fun hookNamed(type: Class<*>, name: String) {
        for (method in type.declaredMethods) {
            if (method.name != name) {
                continue
            }
            hook(method) { chain ->
                val result = chain.proceed()
                guard("弹窗") { logPopup(chain.getThisObject(), name) }
                result
            }
        }
    }

    private fun hookIfExists(className: String, names: List<String>) {
        val type = try {
            Class.forName(className)
        } catch (_: Throwable) {
            note("未发现 $className")
            return
        }
        for (name in names) {
            hookNamed(type, name)
        }
    }

    private fun logPopup(target: Any?, api: String) {
        if (target == null) {
            return
        }
        val view = when (target) {
            is Dialog -> target.window?.decorView
            is PopupWindow -> target.contentView
            else -> dialogView(target)
        }
        logWindow("${target.javaClass.name}#$api", view)
    }

    private fun logWindow(title: String, root: View?) {
        val words = textOf(root, 40)
        val update = words.contains("更新") || words.contains("新版本") || words.contains("升级")
        val mark = if (update) "疑似更新弹窗 " else ""
        note("${mark}弹窗 类名=$title 文字=$words 调用栈=${stackFrames(8)}")
    }

    private fun hookNavigation() {
        val start = Activity::class.java.declaredMethods.filter { it.name == "startActivity" }
        for (method in start) {
            hook(method) { chain ->
                val result = chain.proceed()
                guard("跳转") { logStart(chain.getArgs().firstOrNull()) }
                result
            }
        }
        val transaction = try {
            Class.forName("androidx.fragment.app.FragmentTransaction")
        } catch (_: Throwable) {
            note("未发现 FragmentTransaction")
            return
        }
        for (method in transaction.methods) {
            if (method.name != "replace" && method.name != "add") {
                continue
            }
            if (method.parameterTypes.none { it.name == "androidx.fragment.app.Fragment" }) {
                continue
            }
            hook(method) { chain ->
                val result = chain.proceed()
                guard("跳转") {
                    val fragment = chain.getArgs().firstOrNull { it is Any && it.javaClass.name.contains("Fragment") }
                    logDestination(fragment?.javaClass?.name)
                }
                result
            }
        }
    }

    private fun logStart(arg: Any?) {
        val intent = arg as? Intent ?: return
        if (!armed()) {
            return
        }
        awaitNavigationUntil = 0L
        val target = intent.component?.className ?: intent.action ?: "无组件"
        note("底栏点击跳转 Activity=$target")
    }

    private fun logDestination(name: String?) {
        if (!armed() || name == null) {
            return
        }
        awaitNavigationUntil = 0L
        note("底栏点击跳转 Fragment=$name")
    }

    private fun hookStarts() {
        val context = Class.forName("android.app.ContextImpl")
        for (method in context.declaredMethods) {
            if (method.name != "startService" &&
                method.name != "startForegroundService" &&
                method.name != "bindService"
            ) {
                continue
            }
            hook(method) { chain ->
                val result = chain.proceed()
                guard("拉起") { logLaunch(method.name, chain.getArgs().firstOrNull()) }
                result
            }
        }
        val schedule = JobScheduler::class.java.declaredMethods.firstOrNull {
            it.name == "schedule" && it.parameterTypes.firstOrNull() == JobInfo::class.java
        }
        if (schedule != null) {
            hook(schedule) { chain ->
                val result = chain.proceed()
                guard("任务") {
                    val job = chain.getArg(0) as? JobInfo
                    logOnce("任务", "调用方=${caller()} 目标=${job?.service?.className ?: "未知"}")
                }
                result
            }
        }
        val alarms = setOf(
            "set",
            "setExact",
            "setRepeating",
            "setWindow",
            "setAndAllowWhileIdle",
            "setExactAndAllowWhileIdle",
            "setInexactRepeating",
            "setAlarmClock",
        )
        for (method in AlarmManager::class.java.declaredMethods) {
            if (method.name !in alarms) {
                continue
            }
            hook(method) { chain ->
                val result = chain.proceed()
                guard("闹钟") { logOnce("闹钟", "调用方=${caller()} 接口=${method.name}") }
                result
            }
        }
    }

    private fun logLaunch(api: String, arg: Any?) {
        val intent = arg as? Intent ?: return
        val target = intent.component?.className ?: intent.action ?: "无组件"
        logOnce("拉起", "接口=$api 调用方=${caller()} 目标=$target")
    }

    private fun hookShake() {
        for (method in SensorManager::class.java.declaredMethods) {
            if (method.name != "registerListener") {
                continue
            }
            hook(method) { chain ->
                val result = chain.proceed()
                guard("摇一摇") {
                    val sensor = chain.getArgs().filterIsInstance<Sensor>().firstOrNull()
                    if (sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                        val listener = chain.getArgs().firstOrNull { it !is Sensor && it !is Int && it !is Handler }
                        note("加速度监听 类名=${listener?.javaClass?.name ?: "未知"} 调用栈=${stackFrames(8)}")
                    }
                }
                result
            }
        }
    }

    private fun logManifest(appInfo: ApplicationInfo) {
        val context = systemContext() ?: return
        val info = context.packageManager.getPackageInfo(
            appInfo.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS).toLong(),
            ),
        )
        info.services?.forEach { service ->
            note("清单Service ${service.name} 进程=${service.processName} 提示=${vendorHint(service.name)}")
        }
        info.receivers?.forEach { receiver ->
            note("清单Receiver ${receiver.name} 提示=${vendorHint(receiver.name)}")
        }
    }

    private fun findPlus(root: View): View? {
        val screenWidth = root.width
        val screenHeight = root.height
        if (screenWidth == 0 || screenHeight == 0) {
            return null
        }
        var best: View? = null
        var bestDistance = Int.MAX_VALUE
        walk(root) { view ->
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) {
                return@walk
            }
            val location = IntArray(2)
            view.getLocationInWindow(location)
            if (location[1] < screenHeight * 0.7) {
                return@walk
            }
            val min = dp(view, 36)
            val max = dp(view, 120)
            if (view.width !in min..max || view.height !in min..max) {
                return@walk
            }
            if (kotlin.math.abs(view.width - view.height) > dp(view, 24)) {
                return@walk
            }
            val center = location[0] + view.width / 2
            val distance = kotlin.math.abs(center - screenWidth / 2)
            if (distance > screenWidth * 0.18) {
                return@walk
            }
            if (isUnder(view, TAB_ROW)) {
                return@walk
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = view
            }
        }
        return best
    }

    private fun findBind(type: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null && current.name != "androidx.recyclerview.widget.RecyclerView\$Adapter") {
            val found = current.declaredMethods.firstOrNull { method ->
                method.name == "onBindViewHolder" && method.parameterCount >= 2
            }
            if (found != null) {
                return found
            }
            current = current.superclass
        }
        return null
    }

    private fun findItemView(holder: Any): View? {
        var current: Class<*>? = holder.javaClass
        while (current != null) {
            val field = current.declaredFields.firstOrNull { it.name == "itemView" }
            if (field != null) {
                field.isAccessible = true
                return field.get(holder) as? View
            }
            current = current.superclass
        }
        return null
    }

    private fun itemAt(adapter: Any, position: Int): Any? {
        var current: Class<*>? = adapter.javaClass
        while (current != null) {
            val method = current.declaredMethods.firstOrNull {
                it.name == "getItem" && it.parameterCount == 1
            }
            if (method != null) {
                method.isAccessible = true
                return try {
                    method.invoke(adapter, position)
                } catch (_: Throwable) {
                    null
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun typeFields(item: Any?): String {
        if (item == null) {
            return ""
        }
        val hits = mutableListOf<String>()
        for (field in item.javaClass.declaredFields) {
            val name = field.name.lowercase()
            if (!name.contains("type") && !name.contains("module") && name != "style" && !name.contains("card")) {
                continue
            }
            field.isAccessible = true
            val value = try {
                field.get(item)
            } catch (_: Throwable) {
                continue
            }
            if (value == null || value is Number || value is String || value is Enum<*>) {
                hits += "${field.name}=$value"
            }
        }
        return hits.joinToString(",")
    }

    private fun findRecyclers(root: View): List<View> {
        val result = mutableListOf<View>()
        walk(root) { view ->
            if (view.javaClass.name == "androidx.recyclerview.widget.RecyclerView") {
                result += view
            }
        }
        return result
    }

    private fun findByEntry(root: View, entry: String): View? {
        var found: View? = null
        walk(root) { view ->
            if (found == null && idName(view).startsWith("$entry@")) {
                found = view
            }
        }
        return found
    }

    private fun isUnder(view: View, entry: String): Boolean {
        var current: View? = view
        while (current != null) {
            if (idName(current).startsWith("$entry@")) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun textOf(root: View?, limit: Int): String {
        if (root == null) {
            return ""
        }
        val buffer = StringBuilder()
        walk(root) { view ->
            if (view is TextView && buffer.length < limit) {
                val value = view.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    if (buffer.isNotEmpty()) {
                        buffer.append(' ')
                    }
                    buffer.append(value)
                }
            }
        }
        return buffer.toString().replace('\n', ' ').take(limit)
    }

    private fun dialogView(target: Any): View? {
        val dialog = try {
            target.javaClass.methods.firstOrNull { it.name == "getDialog" && it.parameterCount == 0 }
                ?.invoke(target)
        } catch (_: Throwable) {
            null
        }
        return (dialog as? Dialog)?.window?.decorView
    }

    private fun idName(view: View): String {
        if (view.id == View.NO_ID) {
            return "无id"
        }
        val name = try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            "无名称"
        }
        return "$name@${Integer.toHexString(view.id)}"
    }

    private fun describe(view: View): String {
        return "${view.javaClass.name} id=${idName(view)} ${view.width}x${view.height} 可见=${view.visibility == View.VISIBLE}"
    }

    private fun chain(view: View, limit: Int): String {
        val parts = mutableListOf<String>()
        var current: View? = view
        var count = 0
        while (current != null && count < limit) {
            parts += "${current.javaClass.simpleName}#${idName(current)}"
            current = current.parent as? View
            count += 1
        }
        return parts.joinToString(" <- ")
    }

    private fun vendorHint(name: String): String {
        val lower = name.lowercase()
        val hints = listOf("tpns", "xg", "xinge", "bugly", "huawei", "honor", "xiaomi", "oppo", "vivo", "meizu", "push")
        return hints.filter { lower.contains(it) }.joinToString(",").ifEmpty { "无" }
    }

    private fun caller(): String {
        return Thread.currentThread().stackTrace
            .map { it.className }
            .firstOrNull { it.startsWith("com.tencent.") && !it.startsWith("io.github.yuxiximyxx") }
            ?: "未知"
    }

    private fun stackFrames(limit: Int): String {
        return Thread.currentThread().stackTrace
            .filter { frame ->
                val name = frame.className
                !name.startsWith("io.github.yuxiximyxx") &&
                    !name.startsWith("java.lang.") &&
                    !name.contains("libxposed") &&
                    !name.contains("lsposed") &&
                    !name.contains("Xposed")
            }
            .take(limit)
            .joinToString(" <- ") { "${it.className}.${it.methodName}" }
    }

    private fun armed(): Boolean = System.currentTimeMillis() <= awaitNavigationUntil

    private fun armFromClick(view: View) {
        if (!isUnder(view, TAB_ROW) && !looksLikePlusClick(view)) {
            return
        }
        awaitNavigationUntil = System.currentTimeMillis() + 800L
        note("底栏点击 ${describe(view)}")
    }

    private fun looksLikePlusClick(view: View): Boolean {
        val root = view.rootView
        if (root.width == 0 || root.height == 0 || view.width == 0 || view.height == 0) {
            return false
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        if (location[1] < root.height * 0.7) {
            return false
        }
        val min = dp(view, 36)
        val max = dp(view, 120)
        if (view.width !in min..max || view.height !in min..max) {
            return false
        }
        val center = location[0] + view.width / 2
        return kotlin.math.abs(center - root.width / 2) <= root.width * 0.18
    }

    private fun isDescendant(child: View, ancestor: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === ancestor) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun hookNavigationClicks() {
        val click = View::class.java.getDeclaredMethod("performClick")
        hook(click) { chain ->
            val result = chain.proceed()
            val view = chain.getThisObject() as? View
            if (view != null) {
                guard("底栏点击") { armFromClick(view) }
            }
            result
        }
    }

    private fun logOnce(kind: String, body: String) {
        if (seen.size > 400) {
            return
        }
        if (seen.add("$kind|$body")) {
            note("$kind $body")
        }
    }

    private fun systemContext(): Context? {
        return try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            threadClass.getMethod("getSystemContext").invoke(thread) as Context
        } catch (_: Throwable) {
            null
        }
    }

    private fun walk(root: View, visitor: (View) -> Unit) {
        visitor(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                walk(root.getChildAt(index), visitor)
            }
        }
    }

    private fun dp(view: View, value: Int): Int {
        return (value * view.resources.displayMetrics.density).toInt()
    }

    private fun hook(method: Method, block: (XposedInterface.Chain) -> Any?) {
        val host = module ?: return
        method.isAccessible = true
        host.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            try {
                block(chain)
            } catch (error: Throwable) {
                note("放行 ${method.declaringClass.simpleName}#${method.name} ${error.javaClass.simpleName}")
                throw error
            }
        }
    }

    private fun guard(step: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            note("放行 $step ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun debugEnabled(host: XposedModule?): Boolean {
        if (host == null) {
            return false
        }
        return try {
            host.getRemotePreferences(ModulePrefs.GROUP)
                .getBoolean(ModulePrefs.KEY_DEBUG_LOG, ModulePrefs.DEFAULT_DEBUG_LOG)
        } catch (_: Throwable) {
            false
        }
    }

    private fun note(message: String) {
        val host = module
        if (!debugEnabled(host)) {
            return
        }
        if (host == null) {
            Log.i(ModulePrefs.LOG_TAG, "$PREFIX $message")
            return
        }
        try {
            host.log(Log.INFO, ModulePrefs.LOG_TAG, "$PREFIX $message")
        } catch (_: Throwable) {
            Log.i(ModulePrefs.LOG_TAG, "$PREFIX $message")
        }
    }

}
