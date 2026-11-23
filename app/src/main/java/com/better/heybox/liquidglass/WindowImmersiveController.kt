package com.better.heybox.liquidglass

import android.util.Log
import com.better.heybox.LogRecorder

import android.app.Activity
import android.graphics.Color
import android.graphics.Insets
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import java.util.Collections
import java.util.WeakHashMap

/**
 * 系统导航条沉浸控制器（原 liquidglass 包唯一保留件）：只透
 * 明系统导航条/清零导航条 inset，不碰小黑盒自有底栏。
 * v1.10.0 起液态玻璃底栏整套移除后，本控制器不再读
 * glass_immersive 等已删配置键，沉浸按原缺省（开）常开。
 */
internal object WindowImmersiveController {

    private val STATES: MutableMap<Activity, State> =
        Collections.synchronizedMap(WeakHashMap<Activity, State>())
    private val ACTIVITIES: MutableMap<Activity, Boolean> =
        Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

    @JvmStatic
    fun refresh() {
        val activities: ArrayList<Activity> =
            synchronized(ACTIVITIES) { ArrayList(ACTIVITIES.keys) }
        for (activity in activities) {
            if (activity != null && !activity.isFinishing()
                && !activity.isDestroyed()
            ) {
                apply(activity)
            }
        }
    }

    @JvmStatic
    fun apply(activity: Activity) {
        applyInternal(activity, true)
    }

    /** fork 定制：轻量沉浸——只透明导航条，不动窗口 insets。
     *  全量版会把导航条 inset 清零，个别页面（搜索页）的布局依赖这个
     *  inset 算位置，清零后顶部搜索框会被顶出屏幕；非主界面用轻量版。 */
    @JvmStatic
    fun applyLite(activity: Activity) {
        applyInternal(activity, false)
    }

    private fun applyInternal(activity: Activity, dropInset: Boolean) {
        if (activity == null) {
            return
        }
        ACTIVITIES[activity] = true
        try {
            val window = activity.getWindow()
            if (window == null) {
                return
            }
            val existing = STATES[activity]
            val fresh = existing == null
            val state = existing ?: State(window).also { STATES[activity] = it }
            val decor = window.getDecorView()
            if (decor != null) {
                decor.setSystemUiVisibility(
                    state.systemUiVisibility or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            }
            val content = if (decor == null) {
                null
            } else {
                decor.findViewById<View>(android.R.id.content)
            }
            if (content != null && dropInset) {
                content.setOnApplyWindowInsetsListener(DROP_NAV_INSET)
                if (fresh) {
                    content.requestApplyInsets()
                }
            }
            clearDrawerStatusBarScrim(decor)
            window.setNavigationBarColor(Color.TRANSPARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.setNavigationBarDividerColor(Color.TRANSPARENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(false)
            }
            window.setFlags(
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            )
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        } catch (t: Throwable) {
            logErr(t)
        }
    }

    /** fork 定制：给独立窗口（Dialog 等）做全量沉浸。
     *  查看图片若是全屏 Dialog，它有自己的 Window，Activity 级沉浸够不着，
     *  底部会留黑条；这里直接对 dialog 的 window 做同样的处理。 */
    @JvmStatic
    fun applyToWindow(window: Window?) {
        if (window == null) {
            return
        }
        try {
            val decor = window.getDecorView() ?: return
            decor.setSystemUiVisibility(
                decor.getSystemUiVisibility() or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
            val content = decor.findViewById<View>(android.R.id.content)
            if (content != null) {
                content.setOnApplyWindowInsetsListener(DROP_NAV_INSET)
                content.requestApplyInsets()
            }
            window.setNavigationBarColor(Color.TRANSPARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.setNavigationBarDividerColor(Color.TRANSPARENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(false)
            }
            window.setFlags(
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            )
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        } catch (t: Throwable) {
            logErr(t)
        }
    }

    private fun clearDrawerStatusBarScrim(decor: View?) {
        if (decor == null) {
            return
        }
        try {
            val drawer: View? = decor.findViewById(
                decor.resources.getIdentifier(
                    "drawer_layout", "id", decor.context.packageName
                )
            )
            if (drawer == null) {
                return
            }
            val m = drawer.javaClass
                .getMethod("setStatusBarBackgroundColor", Int::class.javaPrimitiveType)
            m.invoke(drawer, Color.TRANSPARENT)
        } catch (ignored: Throwable) {
        }
    }

    private fun logErr(t: Throwable) {
        Log.e("BetterHeybox", "WindowImmersiveController", t)
        try {
            LogRecorder.record(Log.ERROR, "BetterHeybox", "WindowImmersiveController: $t", t)
        } catch (ignored: Throwable) {
        }
    }

    private val DROP_NAV_INSET = View.OnApplyWindowInsetsListener { v, insets ->
        v.onApplyWindowInsets(withoutNavInset(insets))
    }

    private fun withoutNavInset(insets: WindowInsets): WindowInsets {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return withoutNavInsetApi30(insets)
            }
            return insets.replaceSystemWindowInsets(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), 0
            )
        } catch (t: Throwable) {
            return insets
        }
    }

    private fun withoutNavInsetApi30(insets: WindowInsets): WindowInsets {
        val nav = insets.getInsets(WindowInsets.Type.navigationBars())
        return WindowInsets.Builder(insets)
            .setInsets(
                WindowInsets.Type.navigationBars(),
                Insets.of(nav.left, nav.top, nav.right, 0)
            )
            .build()
    }

    private class State(window: Window) {
        val systemUiVisibility: Int =
            window.getDecorView()?.getSystemUiVisibility() ?: 0
        val navigationBarColor: Int = window.getNavigationBarColor()
        val navigationBarDividerColor: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.getNavigationBarDividerColor()
            } else {
                Color.TRANSPARENT
            }
        val navigationBarContrastEnforced: Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && window.isNavigationBarContrastEnforced()
        private val flags = window.getAttributes().flags
        val drawsSystemBarBackgrounds: Boolean =
            (flags and WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0
        val translucentNavigation: Boolean =
            (flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION) != 0
    }
}
