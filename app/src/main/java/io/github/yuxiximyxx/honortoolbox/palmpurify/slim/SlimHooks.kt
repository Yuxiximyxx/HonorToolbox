package io.github.yuxiximyxx.honortoolbox.palmpurify.slim

import android.app.Activity
import android.app.Dialog
import android.hardware.Sensor
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import io.github.yuxiximyxx.honortoolbox.palmpurify.ModulePrefs
import io.github.yuxiximyxx.honortoolbox.palmpurify.PalmPrefs
import io.github.yuxiximyxx.honortoolbox.palmpurify.XposedInterface
import io.github.yuxiximyxx.honortoolbox.palmpurify.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 只改展示：藏入口、不显示部分弹窗、不注册营销向摇一摇。
 * 失败就放行。不碰账号、战绩、资产、活动接口。
 */
object SlimHooks {
    private const val HOME = "com.tencent.zone.main.zm.GameHomeActivity"
    private const val TAB_ROW = "tab_title_content"
    private const val CENTER = "bottom_title_center_btn"
    private val installed = AtomicBoolean(false)
    private val hookedAdapters = Collections.synchronizedSet(mutableSetOf<String>())
    private val tabStates = WeakHashMap<View, TabState>()
    private val itemStates = WeakHashMap<View, ItemState>()
    private val centerStates = WeakHashMap<View, Int>()
    private val loggedModules = Collections.synchronizedSet(mutableSetOf<String>())
    private var module: XposedModule? = null
    private var shakeLogged = false

    fun install(host: XposedModule) {
        if (!installed.compareAndSet(false, true)) {
            return
        }
        module = host
        guard("首页布局") { hookHome() }
        guard("首页列表") { hookFutureBinds() }
        guard("弹窗") { hookDialogs() }
        guard("摇一摇") { hookShake() }
        note("界面精简钩子已安装")
    }

    private fun hookHome() {
        val onResume = Activity::class.java.getDeclaredMethod("onResume")
        hook(onResume) { chain ->
            val result = chain.proceed()
            val activity = chain.getThisObject() as? Activity
            if (activity != null && activity.javaClass.name == HOME) {
                activity.window?.decorView?.post { guard("套用首页") { applyHome(activity) } }
            }
            result
        }
    }

    private fun applyHome(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        applyTabs(decor)
        applyCenter(decor)
        watchMainList(decor)
    }

    /**
     * 底栏逐项自定义（v1.6.0 合入时按小乐追加需求改，原独立版
     * 是单一精简：固定只留「首页/我的」）：
     * - slim_tabbar 仍是这一组的总闸：关 = 整组不生效、逐项
     *   全部恢复原样；
     * - 总闸开时逐项按 [PalmPrefs] 的 `tab_show_<标签>` 显隐，
     *   缺省与原精简结果一致（首页/我的显示、其余隐藏），
     *   用户在设置页逐项勾选留哪些、藏哪些；
     * - 均匀分布沿用宿主自己的机制：底栏行是 LinearLayout，
     *   显示项一律 width=0、weight=1 等权瓜分整行宽度，隐藏
     *   项 GONE 且 weight=0 不占位——藏掉任意几项，剩下的
     *   自动等距铺满（只剩一项时它占满整行，不崩）；
     * - 每次套用把命中的底栏项标签按顺序记进落盘通道，
     *   供设置页逐项列名（见 [PalmPrefs.saveTabLabels]）。
     */
    private fun applyTabs(decor: View) {
        val row = findByEntry(decor, TAB_ROW) as? ViewGroup ?: return
        val slim = enabled(ModulePrefs.KEY_SLIM_TABBAR, ModulePrefs.DEFAULT_SLIM_TABBAR)
        val labels = (0 until row.childCount).map { labelOf(row.getChildAt(it)) }
        PalmPrefs.saveTabLabels(labels.filter { it.isNotEmpty() })
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            val label = labelOf(child)
            val params = child.layoutParams as? LinearLayout.LayoutParams
            val saved = tabStates.getOrPut(child) {
                TabState(
                    visibility = child.visibility,
                    width = params?.width ?: child.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    weight = params?.weight ?: 0f,
                )
            }
            if (!slim || label.isEmpty()) {
                child.visibility = saved.visibility
                params?.width = saved.width
                params?.weight = saved.weight
                if (params != null) {
                    child.layoutParams = params
                }
                continue
            }
            val show = PalmPrefs.bool(
                PalmPrefs.tabShowKey(label),
                PalmPrefs.defaultTabShow(label),
            )
            if (show) {
                child.visibility = View.VISIBLE
                params?.width = 0
                params?.weight = 1f
            } else {
                child.visibility = View.GONE
                params?.width = 0
                params?.weight = 0f
            }
            if (params != null) {
                child.layoutParams = params
            }
        }
    }

    private fun applyCenter(decor: View) {
        val button = findByEntry(decor, CENTER) ?: return
        val hide = enabled(ModulePrefs.KEY_HIDE_CENTER_BTN, ModulePrefs.DEFAULT_HIDE_CENTER_BTN)
        val original = centerStates.getOrPut(button) { button.visibility }
        button.visibility = if (hide) View.GONE else original
    }

    private fun watchMainList(decor: View) {
        val recycler = findRecyclers(decor)
            .filter { idName(it).substringBefore('@') != "right_icon_container" }
            .filter { it.width > decor.width / 2 && it.height > dp(decor, 240) }
            .maxByOrNull { it.width * it.height }
        if (recycler == null) {
            noteOnce("首页主列表未找到，运营模块精简放行")
            return
        }
        val adapter = try {
            recycler.javaClass.methods.firstOrNull { it.name == "getAdapter" && it.parameterCount == 0 }
                ?.invoke(recycler)
        } catch (_: Throwable) {
            null
        }
        if (adapter == null) {
            noteOnce("首页主列表 ${describe(recycler)} 还没有 Adapter")
            return
        }
        hookAdapter(adapter.javaClass)
    }

    private fun hookFutureBinds() {
        // 适配器类要等列表创建后才能挂钩，由 watchMainList 完成。
    }

    private fun hookAdapter(type: Class<*>) {
        if (!hookedAdapters.add(type.name)) {
            return
        }
        val method = findBind(type) ?: run {
            note("首页列表 ${type.name} 没有 onBindViewHolder，精简放行")
            return
        }
        hook(method) { chain ->
            val adapter = chain.getThisObject()
            val holder = chain.getArg(0)
            val position = chain.getArg(1) as? Int
            val before = try {
                decide(adapter, holder, position, "")
            } catch (error: Throwable) {
                note("首页模块判断失败，保留：${error.message}")
                Decision("未知", "", "保留")
            }
            val result = chain.proceed()
            val title = textOf(holder?.let { findItemView(it) }, 40)
            val decision = if (before.action == "保留" && title.isNotEmpty()) {
                decide(adapter, holder, position, title)
            } else {
                before
            }
            val slim = enabled(ModulePrefs.KEY_SLIM_HOME_MODULES, ModulePrefs.DEFAULT_SLIM_HOME_MODULES)
            if (slim && decision.action == "过滤") {
                collapse(holder)
            } else {
                restoreItem(holder)
            }
            noteOnce("首页模块 类型=${decision.type} 标题=${decision.title.ifEmpty { title }} 结果=${decision.action}")
            result
        }
        note("已观察首页列表 ${type.name}")
    }

    private fun decide(adapter: Any?, holder: Any?, position: Int?, visibleTitle: String): Decision {
        val item = if (adapter != null && position != null) itemAt(adapter, position) else null
        val type = typeFields(item).ifEmpty { "无" }
        val title = visibleTitle.ifEmpty {
            when {
                holder != null -> textOf(findItemView(holder), 40)
                item is CharSequence -> item.toString().take(40)
                else -> item?.toString()?.take(40).orEmpty()
            }
        }
        val className = item?.javaClass?.name.orEmpty()
        val blob = "$type $title $className"
        if (blob.contains("战绩") || blob.contains("资产") || blob.contains("活动")) {
            return Decision(type, title, "保留")
        }
        val promo = listOf("广告", "横幅", "banner", "Banner", "运营", "开通会员", "会员专享", "限时优惠", "礼包")
        if (promo.any { blob.contains(it) }) {
            return Decision(type, title, "过滤")
        }
        return Decision(type, title, "保留")
    }

    private fun hookDialogs() {
        hookAll(Dialog::class.java, "show") { chain, _ ->
            val dialog = chain.getThisObject() as? Dialog
            if (dialog != null && shouldBlockDialog(dialog.javaClass.name, stack(), textOf(dialog.window?.decorView, 80))) {
                note("不显示弹窗 ${dialog.javaClass.name}")
                null
            } else {
                chain.proceed()
            }
        }
        hookAll(PopupWindow::class.java, "showAtLocation") { chain, _ -> popup(chain) }
        hookAll(PopupWindow::class.java, "showAsDropDown") { chain, _ -> popup(chain) }
        val onCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
        hook(onCreate) { chain ->
            val activity = chain.getThisObject() as? Activity
            val result = chain.proceed()
            val simple = activity?.javaClass?.simpleName.orEmpty()
            if (activity != null && (simple.endsWith("Dialog") || simple.endsWith("Popup"))) {
                val text = textOf(activity.window?.decorView, 80)
                if (shouldBlockDialog(activity.javaClass.name, stack(), text)) {
                    guard("关闭弹窗页") { activity.finish() }
                    note("不显示弹窗页 ${activity.javaClass.name}")
                }
            }
            result
        }
    }

    private fun popup(chain: XposedInterface.Chain): Any? {
        val window = chain.getThisObject() as? PopupWindow
        val text = textOf(window?.contentView, 80)
        val name = window?.javaClass?.name ?: "PopupWindow"
        if (shouldBlockDialog(name, stack(), text)) {
            note("不显示浮层 $name")
            return null
        }
        return chain.proceed()
    }

    private fun shouldBlockDialog(className: String, stackTrace: String, text: String): Boolean {
        val blob = "$className $stackTrace"
        if (blob.contains("ams.splash") || blob.contains("AMSSplash")) {
            return false
        }
        if (isUpdatePrompt(text) && enabled(ModulePrefs.KEY_BLOCK_UPDATE_DIALOG, ModulePrefs.DEFAULT_BLOCK_UPDATE_DIALOG)) {
            note("关闭更新提醒 $className 文字=$text")
            return true
        }
        if (whitelisted(blob)) {
            return false
        }
        val promo = listOf("推荐", "开通", "会员", "VIP", "vip", "限时", "优惠", "礼包")
        val promoClass = listOf("advert", "adverts", "member")
        val hitText = promo.any { text.contains(it) }
        val hitClass = promoClass.any { className.lowercase().contains(it) }
        if ((hitText || hitClass) && enabled(ModulePrefs.KEY_BLOCK_PROMO_DIALOG, ModulePrefs.DEFAULT_BLOCK_PROMO_DIALOG)) {
            note("拦截推广弹窗 $className 文字=$text")
            return true
        }
        if (hitText || hitClass) {
            return false
        }
        noteOnce("弹窗放行 $className 文字=$text")
        return false
    }

    private fun whitelisted(blob: String): Boolean {
        val allow = listOf(
            "MyLiteFragment",
            "UserLiteFragment",
            "game_role",
            "Permission",
            "permission",
            "Login",
            "login",
            "android.app.AlertDialog",
        )
        return allow.any { blob.contains(it) }
    }

    private fun isUpdatePrompt(text: String): Boolean {
        if (text.contains("战绩") || text.contains("资产") || text.contains("活动")) {
            return false
        }
        val hasWord = text.contains("更新") || text.contains("新版本") || text.contains("升级")
        if (!hasWord) {
            return false
        }
        return text.contains("新版本") || text.contains("升级") || text.contains("版本") || text.contains("立即更新")
    }

    private fun hookShake() {
        val type = try {
            Class.forName("android.hardware.SystemSensorManager")
        } catch (_: Throwable) {
            note("未发现摇一摇")
            return
        }
        val methods = type.declaredMethods.filter { method ->
            method.name == "registerListenerImpl" && !Modifier.isAbstract(method.modifiers)
        }
        if (methods.isEmpty()) {
            note("未发现摇一摇")
            return
        }
        for (method in methods) {
            hook(method) { chain ->
                val sensor = chain.getArgs().filterIsInstance<Sensor>().firstOrNull()
                val listener = chain.getArgs().firstOrNull { it != null && it !is Sensor && it !is Int }
                val marketing = listener != null && isMarketing(listener.javaClass.name)
                if (sensor?.type == Sensor.TYPE_ACCELEROMETER && marketing &&
                    enabled(ModulePrefs.KEY_BLOCK_SHAKE, ModulePrefs.DEFAULT_BLOCK_SHAKE)
                ) {
                    note("已拦截摇一摇 ${listener?.javaClass?.name}")
                    if (method.returnType == java.lang.Boolean.TYPE) false else null
                } else {
                    if (sensor?.type == Sensor.TYPE_ACCELEROMETER && !shakeLogged) {
                        shakeLogged = true
                        note("加速度监听放行 ${listener?.javaClass?.name ?: "未知"}")
                    }
                    chain.proceed()
                }
            }
        }
    }

    private fun isMarketing(className: String): Boolean {
        val lower = className.lowercase()
        val keys = listOf("advert", "adverts", "shake", "promo", "marketing", "operate", "ams")
        return keys.any { lower.contains(it) }
    }

    private fun collapse(holder: Any?) {
        val view = holder?.let { findItemView(it) } ?: return
        val params = view.layoutParams ?: return
        itemStates.getOrPut(view) { ItemState(params.height, view.visibility) }
        params.height = 0
        view.layoutParams = params
        view.visibility = View.GONE
    }

    private fun restoreItem(holder: Any?) {
        val view = holder?.let { findItemView(it) } ?: return
        val saved = itemStates[view] ?: return
        val params = view.layoutParams ?: return
        params.height = saved.height
        view.layoutParams = params
        view.visibility = saved.visibility
    }

    private fun labelOf(view: View): String {
        // 逐项开关的身份键：优先可见文字（与原精简的命中口径
        // 一致），文字为空时退到 contentDescription；两处都
        // trim，避免同一项在设置页和 hook 侧对不上。
        val text = textOf(view, 40).trim()
        if (text.isNotEmpty()) {
            return text
        }
        return view.contentDescription?.toString()?.trim().orEmpty()
    }

    private fun findBind(type: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null && !current.name.endsWith("RecyclerView\$Adapter")) {
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
            val method = current.declaredMethods.firstOrNull { it.name == "getItem" && it.parameterCount == 1 }
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
            if (found == null && idName(view).substringBefore('@') == entry) {
                found = view
            }
        }
        return found
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

    private fun describe(view: View): String = "${view.javaClass.simpleName}#${idName(view)}"

    private fun stack(): String {
        return Thread.currentThread().stackTrace.joinToString(" ") { it.className }
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

    private fun enabled(key: String, defaultValue: Boolean): Boolean {
        val host = module ?: return defaultValue
        return try {
            host.getRemotePreferences(ModulePrefs.GROUP).getBoolean(key, defaultValue)
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun hookAll(
        type: Class<*>,
        name: String,
        block: (XposedInterface.Chain, Method) -> Any?,
    ) {
        for (method in type.declaredMethods) {
            if (method.name == name) {
                hook(method) { chain -> block(chain, method) }
            }
        }
    }

    private fun hook(method: Method, block: (XposedInterface.Chain) -> Any?) {
        val host = module ?: return
        try {
            method.isAccessible = true
            host.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                try {
                    block(chain)
                } catch (error: Throwable) {
                    note("放行 ${method.declaringClass.simpleName}#${method.name} ${error.javaClass.simpleName}")
                    throw error
                }
            }
        } catch (error: Throwable) {
            note("放行 挂钩 ${method.name} 失败 ${error.message}")
        }
    }

    private fun guard(step: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            note("放行 $step ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun note(message: String) {
        val host = module ?: return
        try {
            host.log(Log.INFO, ModulePrefs.LOG_TAG, "PalmPurify 精简 $message")
        } catch (_: Throwable) {
            Log.i(ModulePrefs.LOG_TAG, "PalmPurify 精简 $message")
        }
    }

    private fun noteOnce(message: String) {
        if (loggedModules.add(message)) {
            note(message)
        }
    }

    private class TabState(val visibility: Int, val width: Int, val weight: Float)
    private class ItemState(val height: Int, val visibility: Int)
    private class Decision(val type: String, val title: String, val action: String)
}
