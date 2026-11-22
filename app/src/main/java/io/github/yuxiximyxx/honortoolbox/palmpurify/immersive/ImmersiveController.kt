package io.github.yuxiximyxx.honortoolbox.palmpurify.immersive

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import io.github.yuxiximyxx.honortoolbox.palmpurify.ModulePrefs
import io.github.yuxiximyxx.honortoolbox.palmpurify.XposedModule
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 只改窗口样式和已登记页面的内边距。
 * 任何一步失败都吞掉并写日志，让掌盟原来的逻辑继续走。
 */
object ImmersiveController {
    private const val APPEARANCE_MASK =
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    private const val FLAG_MASK =
        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
            WindowManager.LayoutParams.FLAG_FULLSCREEN

    private val topHints = listOf("title", "toolbar", "actionbar", "action_bar", "header", "topbar", "top_bar")
    private val bottomHints = listOf("tab", "bottom", "navigation", "nav")
    private val plusHints = listOf("fab", "plus", "add", "center", "float")
    private const val FLOAT_BAR_COLOR = 0xFFF7F8FA.toInt()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = WeakHashMap<Activity, Session>()
    private var module: XposedModule? = null
    private var listening = false

    fun attach(host: XposedModule) {
        module = host
        if (listening) {
            return
        }
        listening = true
        try {
            host.getRemotePreferences(ModulePrefs.GROUP)
                .registerOnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key == ModulePrefs.KEY_IMMERSIVE) {
                        mainHandler.post { reapplyAll() }
                    }
                }
            log(Log.INFO, "已监听远程偏好组 ${ModulePrefs.GROUP}")
        } catch (error: Throwable) {
            listening = false
            log(Log.WARN, "远程偏好不可用，透明沉浸按默认开启处理", error)
        }
    }

    fun beforeCreate(activity: Activity) {
        if (!enabled()) {
            return
        }
        val window = activity.window ?: return
        ensureSession(activity)
        window.setDecorFitsSystemWindows(false)
    }

    fun afterReady(activity: Activity) {
        if (!enabled()) {
            restore(activity)
            return
        }
        val window = activity.window ?: return
        val session = ensureSession(activity)
        if (!session.capturedAppColors) {
            val now = capture(window)
            session.snapshot = session.snapshot.copy(
                statusColor = now.statusColor,
                navColor = now.navColor,
                statusContrast = now.statusContrast,
                navContrast = now.navContrast,
                appearance = now.appearance,
                flags = now.flags,
            )
            session.capturedAppColors = true
        }
        applyTransparent(window)
        updateAppearance(activity)
        schedulePageInsets(activity)
    }

    fun reapplyAll() {
        val activities = sessions.keys.toList()
        for (activity in activities) {
            if (activity.isDestroyed || activity.isFinishing) {
                continue
            }
            try {
                if (enabled()) {
                    afterReady(activity)
                } else {
                    restore(activity)
                }
            } catch (error: Throwable) {
                log(Log.WARN, "重新套用窗口失败 ${activity.javaClass.name}", error)
            }
        }
    }

    private fun enabled(): Boolean {
        val host = module ?: return ModulePrefs.DEFAULT_IMMERSIVE
        return try {
            host.getRemotePreferences(ModulePrefs.GROUP)
                .getBoolean(ModulePrefs.KEY_IMMERSIVE, ModulePrefs.DEFAULT_IMMERSIVE)
        } catch (error: Throwable) {
            log(Log.WARN, "读取透明沉浸开关失败，按开启处理", error)
            ModulePrefs.DEFAULT_IMMERSIVE
        }
    }

    private fun ensureSession(activity: Activity): Session {
        val existing = sessions[activity]
        if (existing != null) {
            val orientation = activity.resources.configuration.orientation
            if (existing.orientation != orientation) {
                clearPads(existing)
                existing.top = null
                existing.bottom = null
                existing.topSettled = false
                existing.bottomSettled = false
                existing.attempts = 0
                existing.orientation = orientation
                removeListener(activity, existing)
            }
            return existing
        }
        val created = Session(
            snapshot = capture(activity.window),
            orientation = activity.resources.configuration.orientation,
        )
        sessions[activity] = created
        return created
    }

    private fun applyTransparent(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.navigationBarDividerColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    private fun restore(activity: Activity) {
        val session = sessions[activity] ?: return
        val window = activity.window ?: return
        val snapshot = session.snapshot
        window.setDecorFitsSystemWindows(snapshot.decorFits)
        window.statusBarColor = snapshot.statusColor
        window.navigationBarColor = snapshot.navColor
        window.navigationBarDividerColor = snapshot.navDividerColor
        window.isStatusBarContrastEnforced = snapshot.statusContrast
        window.isNavigationBarContrastEnforced = snapshot.navContrast
        window.setFlags(snapshot.flags, FLAG_MASK)
        window.insetsController?.setSystemBarsAppearance(
            snapshot.appearance,
            APPEARANCE_MASK,
        )
        clearPads(session)
        removeListener(activity, session)
        session.capturedAppColors = false
    }

    private fun updateAppearance(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        decor.post {
            if (!enabled() || activity.isDestroyed) {
                return@post
            }
            try {
                applyAppearance(activity)
            } catch (error: Throwable) {
                log(Log.WARN, "调整系统栏图标深浅失败", error)
            }
        }
    }

    private fun applyAppearance(activity: Activity) {
        val window = activity.window ?: return
        val content = window.decorView.findViewById<View>(android.R.id.content) ?: window.decorView
        val statusInset = statusBarHeight(activity)
        val navInset = navigationBarHeight(activity)
        val topColor = colorBehind(content, top = true, inset = statusInset.coerceAtLeast(dp(content, 24)))
            ?: themeBackground(activity)
        val bottomColor = colorBehind(content, top = false, inset = navInset.coerceAtLeast(dp(content, 16)))
            ?: themeBackground(activity)
        val controller = window.insetsController ?: return
        var bits = 0
        if (topColor != null && isLightColor(topColor)) {
            bits = bits or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        }
        if (bottomColor != null && isLightColor(bottomColor)) {
            bits = bits or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        }
        if (topColor == null && bottomColor == null) {
            return
        }
        controller.setSystemBarsAppearance(bits, APPEARANCE_MASK)
    }

    private fun schedulePageInsets(activity: Activity) {
        val spec = PageAdaptations.specFor(activity.javaClass.name) ?: return
        val session = sessions[activity] ?: return
        if (session.topSettled && session.bottomSettled) {
            applySettledPads(activity, session, spec)
            return
        }
        if (session.listener != null) {
            return
        }
        val decor = activity.window?.decorView ?: return
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            session.attempts += 1
            val done = try {
                applyPageInsets(activity, session, spec)
            } catch (error: Throwable) {
                log(Log.WARN, "页面内边距失败 ${spec.activityClass}", error)
                true
            }
            if (done || session.attempts >= 8) {
                removeListener(activity, session)
                if (!done) {
                    logUnresolved(session, spec)
                }
            }
        }
        session.listener = listener
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun applySettledPads(activity: Activity, session: Session, spec: PageAdaptations.Spec) {
        if (spec.padTop) {
            session.top?.let { pad(it, topExtra = statusBarHeight(activity), bottomExtra = 0) }
        }
        if (spec.padBottom) {
            session.bottom?.let { applyFloatingBar(it, navigationBarHeight(activity)) }
        }
    }

    private fun applyPageInsets(activity: Activity, session: Session, spec: PageAdaptations.Spec): Boolean {
        val content = activity.window?.decorView?.findViewById<View>(android.R.id.content) ?: return false
        if (content.width == 0 || content.height == 0) {
            return false
        }
        var settled = true
        if (!spec.padTop || session.topSettled) {
            session.topSettled = true
        } else {
            val inset = statusBarHeight(activity)
            if (inset == 0) {
                settled = false
            } else {
                val bar = findBar(content, top = true, inset = inset)
                when {
                    bar == null -> settled = false
                    coversInset(bar, top = true, inset = inset) -> {
                        session.top = bar
                        pad(bar, topExtra = inset, bottomExtra = 0)
                        session.topSettled = true
                        log(
                            Log.INFO,
                            "已补顶部 ${inset}px ${spec.activityClass} -> ${describe(bar)}",
                        )
                    }
                    session.attempts >= 3 -> {
                        session.topSettled = true
                        log(Log.INFO, "顶部已在状态栏之外，不补 ${spec.activityClass} -> ${describe(bar)}")
                    }
                    else -> settled = false
                }
            }
        }
        if (!spec.padBottom || session.bottomSettled) {
            session.bottomSettled = true
        } else {
            val inset = navigationBarHeight(activity)
            if (inset == 0) {
                settled = false
            } else {
                val bar = findBottomNavRoot(content)
                if (bar == null) {
                    settled = false
                } else {
                    session.bottom = bar
                    applyFloatingBar(bar, inset)
                    session.bottomSettled = true
                    log(Log.INFO, bottomNavLog(bar, inset))
                }
            }
        }
        return settled && session.topSettled && session.bottomSettled
    }

    private fun logUnresolved(session: Session, spec: PageAdaptations.Spec) {
        if (spec.padTop && !session.topSettled) {
            log(Log.WARN, "未找到可补的顶部标题栏，本页不硬垫 ${spec.activityClass}")
            session.topSettled = true
        }
        if (spec.padBottom && !session.bottomSettled) {
            log(Log.WARN, "未找到同时包含 tab_title_content 和中间加号的父容器，不改 tab_title_content")
            session.bottomSettled = true
        }
    }

    private fun findBar(content: View, top: Boolean, inset: Int): View? {
        val contentLoc = IntArray(2)
        content.getLocationInWindow(contentLoc)
        val minHeight = dp(content, 36)
        val maxHeight = dp(content, if (top) 140 else 110)
        var best: View? = null
        var bestScore = Int.MIN_VALUE
        walk(content) { view ->
            if (view === content || view.visibility != View.VISIBLE) {
                return@walk
            }
            if (!looksLikeChrome(view)) {
                return@walk
            }
            if (view.width < content.width * 4 / 5) {
                return@walk
            }
            if (view.height !in minHeight..maxHeight) {
                return@walk
            }
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            val gap = if (top) {
                loc[1] - contentLoc[1]
            } else {
                (contentLoc[1] + content.height) - (loc[1] + view.height)
            }
            if (gap > dp(content, 16) && !coversInset(view, top, inset)) {
                return@walk
            }
            var score = 1000 - abs(gap)
            val name = entryName(view)
            val className = view.javaClass.simpleName.lowercase()
            val hints = if (top) topHints else bottomHints
            if (hints.any { name.contains(it) || className.contains(it) }) {
                score += 800
            }
            if (score > bestScore) {
                bestScore = score
                best = view
            }
        }
        return best
    }

    /**
     * 用资源 id 找底部导航根容器。
     * tab_title_content 只是四个文字槽，中间加号是它的兄弟或上层兄弟。
     * 选中同时包住这行和加号的最近父容器，不改 tab_title_content 自己。
     */
    private fun findBottomNavRoot(content: View): View? {
        val titleRow = findByEntryName(content, "tab_title_content") ?: return null
        var node: View = titleRow
        while (node.parent is ViewGroup) {
            val parent = node.parent as ViewGroup
            if (parent === content || parent.id == android.R.id.content) {
                break
            }
            val plus = findPlus(parent, titleRow)
            if (plus != null) {
                if (parent.height > dp(parent, 280)) {
                    log(Log.WARN, "含加号的父容器过高，不垫 ${describe(parent)} 加号=${describe(plus)}")
                    return null
                }
                return parent
            }
            node = parent
        }
        return null
    }

    private fun findByEntryName(root: View, entry: String): View? {
        var found: View? = null
        walk(root) { view ->
            if (found == null && entryName(view) == entry) {
                found = view
            }
        }
        return found
    }

    private fun findPlus(parent: ViewGroup, titleRow: View): View? {
        var found: View? = null
        walk(parent) { view ->
            if (found != null || view === parent || view === titleRow) {
                return@walk
            }
            if (!looksLikePlus(view, parent)) {
                return@walk
            }
            val outsideRow = !isDescendantOf(titleRow, view)
            val hangsLower = bottomOf(view) > bottomOf(titleRow) - dp(view, 4)
            if (outsideRow || hangsLower) {
                found = view
            }
        }
        return found
    }

    private fun looksLikePlus(view: View, parent: View): Boolean {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) {
            return false
        }
        if (!isRoughlyCentered(view, parent)) {
            return false
        }
        val name = entryName(view)
        val className = view.javaClass.simpleName.lowercase()
        val hinted = plusHints.any { hint -> name.contains(hint) || className.contains(hint) }
        val minSize = dp(view, 36)
        val maxSize = dp(view, 112)
        val square = view.width in minSize..maxSize &&
            view.height in minSize..maxSize &&
            abs(view.width - view.height) <= dp(view, 24)
        return square || (hinted && view.width in minSize..maxSize)
    }

    private fun isRoughlyCentered(view: View, parent: View): Boolean {
        val viewLocation = IntArray(2)
        val parentLocation = IntArray(2)
        view.getLocationInWindow(viewLocation)
        parent.getLocationInWindow(parentLocation)
        val viewCenter = viewLocation[0] + view.width / 2
        val parentCenter = parentLocation[0] + parent.width / 2
        return abs(viewCenter - parentCenter) <= parent.width * 0.18f
    }

    private fun isDescendantOf(ancestor: View, view: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (node === ancestor) {
                return true
            }
            node = node.parent as? View
        }
        return false
    }

    private fun bottomOf(view: View): Int {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return location[1] + view.height
    }

    /**
     * 把底部导航根容器改成悬浮条：左右和底部留白，圆角白底。
     * 只改 margin、背景和海拔，不加高，也不做 translationY。
     */
    private fun applyFloatingBar(view: View, inset: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (params == null) {
            log(Log.WARN, "底部容器没有 margin 参数，不改成悬浮 ${describe(view)}")
            return
        }
        val state = floatStates.getOrPut(view) {
            FloatSnapshot(
                leftMargin = params.leftMargin,
                rightMargin = params.rightMargin,
                bottomMargin = params.bottomMargin,
                background = view.background,
                elevation = view.elevation,
                translationZ = view.translationZ,
                clipToOutline = view.clipToOutline,
                outlineProvider = view.outlineProvider,
                parent = view.parent as? ViewGroup,
                parentClipChildren = (view.parent as? ViewGroup)?.clipChildren ?: true,
            )
        }
        val side = dp(view, 14)
        val bottomGap = inset + dp(view, 8)
        params.leftMargin = state.leftMargin + side
        params.rightMargin = state.rightMargin + side
        params.bottomMargin = state.bottomMargin + bottomGap
        view.layoutParams = params
        state.parent?.clipChildren = false
        val radius = dp(view, 24).toFloat()
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(FLOAT_BAR_COLOR)
        }
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        view.elevation = dp(view, 8).toFloat()
        if (view is ViewGroup) {
            clearSidePlates(view, state)
        }
    }

    private fun clearSidePlates(root: ViewGroup, state: FloatSnapshot) {
        if (state.platesCleared) {
            return
        }
        val titleRow = findByEntryName(root, "tab_title_content") ?: return
        val plus = findPlus(root, titleRow)
        state.platesCleared = true
        walk(root) { child ->
            if (child === root) {
                return@walk
            }
            if (plus != null && (child === plus || isDescendantOf(plus, child))) {
                return@walk
            }
            val background = child.background ?: return@walk
            val widePlate = child.width >= root.width * 0.18f && solidColor(background) != null
            val isTitleRow = entryName(child) == "tab_title_content"
            if (!widePlate && !isTitleRow) {
                return@walk
            }
            state.childBackgrounds += child to background
            child.background = null
        }
    }

    private fun restoreFloating(view: View) {
        val state = floatStates.remove(view) ?: return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null) {
            params.leftMargin = state.leftMargin
            params.rightMargin = state.rightMargin
            params.bottomMargin = state.bottomMargin
            view.layoutParams = params
        }
        view.background = state.background
        view.elevation = state.elevation
        view.translationZ = state.translationZ
        view.clipToOutline = state.clipToOutline
        view.outlineProvider = state.outlineProvider
        state.parent?.clipChildren = state.parentClipChildren
        for ((child, background) in state.childBackgrounds) {
            child.background = background
        }
    }

    private fun bottomNavLog(bar: View, inset: Int): String {
        val group = bar as? ViewGroup
        val childCount = group?.childCount ?: 0
        val titleRow = findByEntryName(bar, "tab_title_content")
        val plus = if (group != null && titleRow != null) findPlus(group, titleRow) else null
        val children = if (group == null) {
            ""
        } else {
            (0 until group.childCount).joinToString(separator = " | ") { index ->
                val child = group.getChildAt(index)
                val id = entryName(child).ifBlank { "无id" }
                "${child.javaClass.simpleName}#$id"
            }
        }
        val side = if (bar.width > 0) dp(bar, 14) else 0
        val bottomGap = inset + if (bar.width > 0) dp(bar, 8) else 0
        return "底部导航根容器 ${describe(bar)} 子View=$childCount 含加号=${plus != null} " +
            "加号=${plus?.let { describe(it) } ?: "无"} 悬浮左右=${side}px 底部空隙=${bottomGap}px 子项=$children"
    }

    private fun coversInset(view: View, top: Boolean, inset: Int): Boolean {
        if (inset <= 0) {
            return false
        }
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val screen = view.rootView.height
        return if (top) {
            loc[1] < inset - dp(view, 4)
        } else {
            screen - (loc[1] + view.height) < inset - dp(view, 4)
        }
    }

    private fun pad(view: View, topExtra: Int, bottomExtra: Int) {
        val state = padStates.getOrPut(view) {
            PadState(
                paddingTop = view.paddingTop,
                paddingBottom = view.paddingBottom,
                height = view.layoutParams?.height ?: 0,
            )
        }
        val params = view.layoutParams
        if (params != null && state.height > 0) {
            params.height = state.height + topExtra + bottomExtra
            view.layoutParams = params
        }
        view.setPadding(
            view.paddingLeft,
            state.paddingTop + topExtra,
            view.paddingRight,
            state.paddingBottom + bottomExtra,
        )
    }

    private fun clearPads(session: Session) {
        session.top?.let { restorePad(it) }
        session.bottom?.let {
            restorePad(it)
            restoreFloating(it)
        }
    }

    private fun restorePad(view: View) {
        val state = padStates.remove(view) ?: return
        view.setPadding(view.paddingLeft, state.paddingTop, view.paddingRight, state.paddingBottom)
        val params = view.layoutParams
        if (params != null && state.height > 0) {
            params.height = state.height
            view.layoutParams = params
        }
        if (state.minHeightChanged) {
            view.minimumHeight = state.minimumHeight
        }
        if (state.clipChanged && view is ViewGroup) {
            view.clipChildren = state.clipChildren
        }
        if (state.backgroundReplaced) {
            view.background = state.background
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                val original = childTranslations.remove(child) ?: continue
                child.translationY = original
            }
        }
    }

    private fun removeListener(activity: Activity, session: Session) {
        val listener = session.listener ?: return
        val observer = activity.window?.decorView?.viewTreeObserver
        if (observer != null && observer.isAlive) {
            observer.removeOnGlobalLayoutListener(listener)
        }
        session.listener = null
    }

    private fun capture(window: Window): WindowSnapshot {
        return WindowSnapshot(
            decorFits = readDecorFits(window),
            statusColor = window.statusBarColor,
            navColor = window.navigationBarColor,
            navDividerColor = window.navigationBarDividerColor,
            statusContrast = window.isStatusBarContrastEnforced,
            navContrast = window.isNavigationBarContrastEnforced,
            appearance = window.insetsController?.systemBarsAppearance ?: 0,
            flags = window.attributes.flags,
        )
    }

    private fun colorBehind(root: View, top: Boolean, inset: Int): Int? {
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val regionTop = if (top) rootLoc[1] else rootLoc[1] + root.height - inset
        val regionBottom = if (top) rootLoc[1] + inset else rootLoc[1] + root.height
        var bestColor: Int? = null
        var bestArea = 0
        walk(root) { view ->
            if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) {
                return@walk
            }
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            val overlapTop = maxOf(loc[1], regionTop)
            val overlapBottom = minOf(loc[1] + view.height, regionBottom)
            val overlap = overlapBottom - overlapTop
            if (overlap <= 0) {
                return@walk
            }
            val color = solidColor(view.background) ?: return@walk
            val area = view.width * overlap
            if (area >= bestArea) {
                bestArea = area
                bestColor = color
            }
        }
        return bestColor
    }

    private fun solidColor(drawable: Drawable?): Int? {
        val color = (drawable as? ColorDrawable)?.color ?: return null
        if (Color.alpha(color) < 200) {
            return null
        }
        return color
    }

    private fun themeBackground(activity: Activity): Int? {
        val attributes = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        return try {
            if (!attributes.hasValue(0)) {
                null
            } else {
                val color = attributes.getColor(0, Color.TRANSPARENT)
                if (Color.alpha(color) < 200) null else color
            }
        } finally {
            attributes.recycle()
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color) / 255.0
        val green = Color.green(color) / 255.0
        val blue = Color.blue(color) / 255.0
        val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        return luminance >= 0.55
    }

    private fun statusBarHeight(activity: Activity): Int {
        val insets = activity.window?.decorView?.rootWindowInsets
        val fromInsets = insets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
        if (fromInsets > 0) {
            return fromInsets
        }
        return systemDimen(activity, "status_bar_height")
    }

    private fun navigationBarHeight(activity: Activity): Int {
        val insets = activity.window?.decorView?.rootWindowInsets
        val navigation = insets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
        val gesture = insets?.getInsets(WindowInsets.Type.mandatorySystemGestures())?.bottom ?: 0
        val fromInsets = maxOf(navigation, gesture)
        if (fromInsets > 0) {
            return fromInsets
        }
        return systemDimen(activity, "navigation_bar_height")
    }

    private fun systemDimen(activity: Activity, name: String): Int {
        val id = activity.resources.getIdentifier(name, "dimen", "android")
        if (id == 0) {
            return 0
        }
        return try {
            activity.resources.getDimensionPixelSize(id)
        } catch (_: Throwable) {
            0
        }
    }

    private fun looksLikeChrome(view: View): Boolean {
        val name = view.javaClass.simpleName
        val blocked = listOf("Recycler", "ListView", "Scroll", "ViewPager", "WebView", "Progress")
        return blocked.none { name.contains(it) }
    }

    private fun entryName(view: View): String {
        if (view.id == View.NO_ID) {
            return ""
        }
        return try {
            view.resources.getResourceEntryName(view.id).lowercase()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun describe(view: View): String {
        val id = view.id
        val idText = if (id == View.NO_ID) {
            "无id"
        } else {
            val name = entryName(view).ifBlank { "无名称" }
            "$name@${Integer.toHexString(id)}"
        }
        return "${view.javaClass.name} id=$idText ${view.width}x${view.height}"
    }

    private fun hexColor(color: Int): String = "#${Integer.toHexString(color)}"

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

    private fun log(priority: Int, message: String, error: Throwable? = null) {
        val host = module
        if (host == null) {
            Log.println(priority, ModulePrefs.LOG_TAG, message)
            return
        }
        if (error == null) {
            host.log(priority, ModulePrefs.LOG_TAG, message)
        } else {
            host.log(priority, ModulePrefs.LOG_TAG, message, error)
        }
    }

    private data class WindowSnapshot(
        val decorFits: Boolean,
        val statusColor: Int,
        val navColor: Int,
        val navDividerColor: Int,
        val statusContrast: Boolean,
        val navContrast: Boolean,
        val appearance: Int,
        val flags: Int,
    )

    private class PadState(
        val paddingTop: Int,
        val paddingBottom: Int,
        val height: Int,
        val measuredHeight: Int = 0,
        val minimumHeight: Int = 0,
        val background: Drawable? = null,
        var minHeightChanged: Boolean = false,
        var backgroundDecided: Boolean = false,
        var backgroundReplaced: Boolean = false,
        var liftScheduled: Boolean = false,
        var liftDelta: Int = 0,
        var clipChildren: Boolean = true,
        var clipChanged: Boolean = false,
    )

    private class Session(
        var snapshot: WindowSnapshot,
        var orientation: Int,
        var capturedAppColors: Boolean = false,
        var top: View? = null,
        var bottom: View? = null,
        var topSettled: Boolean = false,
        var bottomSettled: Boolean = false,
        var attempts: Int = 0,
        var listener: ViewTreeObserver.OnGlobalLayoutListener? = null,
    )

    private val padStates = WeakHashMap<View, PadState>()
    private val childTranslations = WeakHashMap<View, Float>()
    private val floatStates = WeakHashMap<View, FloatSnapshot>()

    private class FloatSnapshot(
        val leftMargin: Int,
        val rightMargin: Int,
        val bottomMargin: Int,
        val background: Drawable?,
        val elevation: Float,
        val translationZ: Float,
        val clipToOutline: Boolean,
        val outlineProvider: ViewOutlineProvider,
        val parent: ViewGroup?,
        val parentClipChildren: Boolean,
        val childBackgrounds: MutableList<Pair<View, Drawable?>> = mutableListOf(),
        var platesCleared: Boolean = false,
    )

    /** 公开 SDK 只有 setter。运行时能读到就记下原值，读不到按「内容让出系统栏」恢复。 */
    private fun readDecorFits(window: Window): Boolean {
        return try {
            val method = Window::class.java.getMethod("decorFitsSystemWindows")
            method.invoke(window) as Boolean
        } catch (_: Throwable) {
            true
        }
    }
}
