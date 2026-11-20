package io.github.yuxiximyxx.honortoolbox.shadeclock

import android.os.Handler
import android.os.Looper
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

/**
 * 隐藏运营商文字（v2.0.8，小乐点名）：锁屏、控制中心、通知中心
 * 左上角的运营商文字（中国联通等两行）一并隐藏，设置页开关
 * 「隐藏运营商文字」默认开，经 stamp 第 8 行传值、免强停切换。
 *
 * 落点（反编译逐环核实）：三处共用同一个运营商容器视图
 * com.android.systemui.operatorname.HwOperatorNameParentView
 * （LinearLayout，内含 mOperatorName1 / mOperatorName2 /
 * mOperatorNameVSim 三个运营商名 TextView 与卫星名——卫星名
 * 不是运营商文字、不动）。其布局 hw_operator_name_parent 被
 * 锁屏状态栏 HwKeyguardStatusBarView 与下拉面板刘海状态栏
 * HwNotchStatusBar 各自的 HwStatusbarContents include——前者
 * 是锁屏那处，后者是下拉面板那处（通知中心与控制中心共用
 * 同一个面板状态栏，即三处齐了）。
 *
 * 可见性统一入口是该类的 setOperatorVisibility(boolean)：
 * 方法内按状态栏类型分支，日志原文 "show keyguard & notch
 * operator"——Keyguard 与 panel-header 两类实例会被类型逻辑
 * 强制显示（传入的 boolean 对这两类不生效），所以不能靠改
 * 参数隐藏；改在 after 里把容器与三个运营商名 TextView 压
 * GONE（与该方法自己对这四个视图的 setVisibility 调用同面，
 * 同调用栈内完成、不闪）。另 hook onAttachedToWindow 登记
 * 实例，本对象自带 1 秒循环兜底：开关开时对登记实例补压
 * （防其它路径直接改可见性），开关由开转关时用实例自己的
 * setOperatorVisibility(其记录的系统意图
 * mIsOperatorLastSettingsVisible) 恢复，恢复结果由系统方法
 * 内部的宽度/类型逻辑决定，我们不硬写 VISIBLE。
 *
 * 同构建里 AOSP 的 ShadeCarrier / ShadeCarrierGroup 经查无
 * 任何布局声明、无代码实例化（死代码），不挂钩。
 * 全路径 try-catch，只写日志，绝不让 SystemUI 崩。
 */
object CarrierHider {

    private const val OPERATOR_CLASS_NAME =
        "com.android.systemui.operatorname.HwOperatorNameParentView"

    private lateinit var logFn: (String) -> Unit

    /** 已 attach 的运营商容器实例（弱引用集合，实例随窗口生灭自动回收）。 */
    private val instances: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap())

    /** 被我们压过 GONE 的实例（开关关时只恢复这些）。 */
    private val hiddenByUs: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap())

    private val handler = Handler(Looper.getMainLooper())
    private var loopStarted = false

    /** 上一轮循环看到的开关值；null=尚未知（首轮只记录不算切换）。 */
    @Volatile
    private var lastEnabled: Boolean? = null

    // stamp 读取缓存：按文件 mtime 决定是否重读（1 秒循环里只 stat）
    private var stampCacheMtime = -1L
    private var stampCache: StampStore.Stamp? = null

    /** 当前开关：stamp 缺失/无第 8 行时按默认开（与设置页默认一致）。 */
    private fun hideEnabled(): Boolean {
        val mtime = StampStore.stampMtime()
        if (mtime != stampCacheMtime) {
            stampCacheMtime = mtime
            stampCache = StampStore.readStamp()
        }
        return stampCache?.hideCarrier ?: true
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, log: (String) -> Unit) {
        logFn = log
        try {
            val cls = XposedHelpers.findClass(OPERATOR_CLASS_NAME, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                cls, "setOperatorVisibility",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            track(view)
                            if (hideEnabled()) applyHidden(view)
                        } catch (t: Throwable) {
                            logFn(
                                "carrier hide (setOperatorVisibility) failed: " +
                                    "${t.javaClass.name}: ${t.message}"
                            )
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                cls, "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            track(view)
                            if (hideEnabled()) applyHidden(view)
                        } catch (t: Throwable) {
                            logFn(
                                "carrier hide (attach) failed: " +
                                    "${t.javaClass.name}: ${t.message}"
                            )
                        }
                    }
                }
            )
            log("carrier hide hooks installed (HwOperatorNameParentView.setOperatorVisibility/onAttachedToWindow)")
        } catch (t: Throwable) {
            log(
                "carrier hide hook install failed, feature inactive: " +
                    "${t.javaClass.name}: ${t.message}"
            )
            return
        }
        startLoop()
    }

    private fun track(view: View) {
        synchronized(instances) { instances.add(view) }
    }

    /** 把运营商容器与三个运营商名 TextView 压 GONE（卫星名不动）。 */
    private fun applyHidden(view: View) {
        if (view.visibility != View.GONE) {
            view.visibility = View.GONE
        }
        for (field in arrayOf("mOperatorName1", "mOperatorName2", "mOperatorNameVSim")) {
            try {
                val tv = XposedHelpers.getObjectField(view, field) as? View
                if (tv != null && tv.visibility != View.GONE) {
                    tv.visibility = View.GONE
                }
            } catch (t: Throwable) {
                // 单个字段读不到不影响其余
            }
        }
        synchronized(hiddenByUs) { hiddenByUs.add(view) }
    }

    /**
     * 恢复：用实例自己的 setOperatorVisibility 重跑系统逻辑，
     * 参数取它记录的系统意图 mIsOperatorLastSettingsVisible
     * （读不到时传 true）；最终可见性由方法内部的类型/宽度
     * 逻辑决定，与系统自身行为一致。
     */
    private fun restore(view: View) {
        try {
            val intent = try {
                XposedHelpers.getBooleanField(view, "mIsOperatorLastSettingsVisible")
            } catch (t: Throwable) {
                true
            }
            XposedHelpers.callMethod(view, "setOperatorVisibility", intent)
        } catch (t: Throwable) {
            logFn(
                "carrier restore failed: ${t.javaClass.name}: ${t.message}"
            )
        }
        synchronized(hiddenByUs) { hiddenByUs.remove(view) }
    }

    /**
     * 1 秒循环：开关开时对全部登记实例补压（防 setOperatorVisibility
     * 之外的路径把可见性改回去）；开关由开转关时逐个恢复。
     * 只在状态真不对时才写视图，稳态零写入。
     */
    private fun startLoop() {
        if (loopStarted) return
        loopStarted = true
        val runnable = object : Runnable {
            override fun run() {
                try {
                    val enabled = hideEnabled()
                    val prev = lastEnabled
                    val snapshot = synchronized(instances) { instances.toList() }
                    if (enabled) {
                        for (v in snapshot) {
                            if (v.visibility != View.GONE) applyHidden(v)
                        }
                    } else if (prev == true) {
                        val toRestore = synchronized(hiddenByUs) { hiddenByUs.toList() }
                        for (v in toRestore) restore(v)
                        if (toRestore.isNotEmpty()) {
                            logFn("carrier text restored: switch off, ${toRestore.size} instance(s)")
                        }
                    }
                    if (prev != null && prev != enabled && enabled) {
                        logFn("carrier text hidden: switch on, ${snapshot.size} instance(s) tracked")
                    }
                    lastEnabled = enabled
                } catch (t: Throwable) {
                    logFn("carrier hide loop failed: ${t.javaClass.name}: ${t.message}")
                }
                handler.postDelayed(this, 1000L)
            }
        }
        handler.postDelayed(runnable, 1000L)
    }
}
