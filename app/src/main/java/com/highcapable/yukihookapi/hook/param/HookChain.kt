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
 * invokeOriginalMethod 对 Constructor 成员即直通原构造、不经
 * 分发，返回值即新实例，thisObject 传 null。
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
                is Constructor<*> -> XposedBridge.invokeOriginalMethod(m, null, useArgs)
                else -> throw IllegalStateException("不支持的 Member 类型: $m")
            }
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }
}
