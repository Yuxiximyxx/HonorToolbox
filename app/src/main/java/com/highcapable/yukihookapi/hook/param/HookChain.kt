package com.highcapable.yukihookapi.hook.param

import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * YukiHookAPI 的 HookChain 在传统 Xposed API 上的等价物
 * （荣耀工具箱合入 BetterHeybox 的适配层）。
 *
 * 语义与原框架一致：拦截体里调用 [proceed] 才执行原方法
 * （可带新参数），拦截体的返回值即为本次调用的最终结果；
 * 原方法抛出的异常经 [proceed] 原样透传给调用方。
 *
 * 注意（v1.7.1 热修）：proceed 必须走 [XposedBridge.invokeOriginalMethod]
 * 的原方法直通通道，绝不能用普通反射 Method.invoke——被 hook 的
 * 方法经反射调用会再次进入 hook 分发、再次执行拦截体，拦截体里
 * 再 proceed 又反射调用，无限递归栈溢出、宿主进程当场死（v1.7.0
 * 小黑盒启动即崩就是死在这里，第一个中招的是 Application.onCreate
 * 的 hook）。
 *
 * 注意（v1.7.2 热修）：构造器分支同理——被 hook 的构造器经反射
 * Constructor.newInstance 同样会再次进入 hook 分发，并非「不经
 * 分发」。v1.7.1 当时把构造器分支留给反射，结果 HttpBridge 抓
 * HTTP 的构造器 hook 在点开帖子加载数据、目标类被实例化时引爆
 * 同样的无限递归（后台线程栈溢出、帖子数据回不来、点帖子黑屏）。
 * 故构造器分支也改走 invokeOriginalMethod 直通原构造。
 *
 * 注意（v1.7.3 热修）：构造器的语义与方法分开，切勿按方法口径
 * 改回。方法 proceed 是「造出结果」：invokeOriginalMethod 传
 * 实例、拿返回值，before 里再把拦截体返回值写进 param.result
 * 作最终结果。构造器 proceed 是「在同一实例上执行原构造体」：
 * 必须把 before 回调的 param.thisObject（正在构造的那个实例）
 * 传给 invokeOriginalMethod，让原构造体就在这个实例上跑；
 * 返回值无语义、直接丢弃。同时 before 里绝不能给构造器设
 * param.result——那会让框架跳过原构造体。v1.7.2 两处都做错了
 * （传 null + 设 result）：proceed 就算造出新实例也是另一个对
 * 象，应用手里那个实例成了未初始化的空壳，HttpBridge 的
 * OkHttp 载体类构造器中招后帖子数据请求静默失败、页面永远
 * 加载不出来（黑屏、无崩溃无报错，真机 A/B 证实独立版正常、
 * 是适配层语义差异而非版本漂移）。
 */
class HookChain internal constructor(
    private val member: Member,
    val instanceOrNull: Any?,
    argsArray: Array<Any?>,
) {

    /** 当前参数列表（可变）：在 proceed 之前修改会影响实际调用。 */
    val args: MutableList<Any?> = argsArray.toMutableList()

    fun arg(index: Int): Any? = args.getOrNull(index)

    fun proceed(): Any? = invokeOriginal(args.toTypedArray())

    fun proceed(newArgs: Array<Any?>): Any? {
        args.clear()
        args.addAll(newArgs)
        return invokeOriginal(newArgs)
    }

    fun proceed(newArgs: MutableList<Any?>): Any? = proceed(newArgs.toTypedArray())

    private fun invokeOriginal(useArgs: Array<Any?>): Any? {
        try {
            return when (val m = member) {
                is Method -> XposedBridge.invokeOriginalMethod(m, instanceOrNull, useArgs)
                is Constructor<*> -> {
                    // 在正在构造的同一实例（before 回调的
                    // param.thisObject）上执行原构造体；
                    // 返回值无语义，丢弃（见类头 v1.7.3 注）。
                    XposedBridge.invokeOriginalMethod(m, instanceOrNull, useArgs)
                    null
                }
                else -> throw IllegalStateException("不支持的 Member 类型: $m")
            }
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }
}
