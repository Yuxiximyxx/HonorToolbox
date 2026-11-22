package com.highcapable.yukihookapi.hook.param

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
                is Method -> {
                    m.isAccessible = true
                    m.invoke(instanceOrNull, *useArgs)
                }
                is Constructor<*> -> {
                    m.isAccessible = true
                    m.newInstance(*useArgs)
                }
                else -> throw IllegalStateException("不支持的 Member 类型: $m")
            }
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }
}
