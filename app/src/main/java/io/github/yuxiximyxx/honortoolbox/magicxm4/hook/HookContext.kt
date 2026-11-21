package io.github.yuxiximyxx.honortoolbox.magicxm4.hook

import android.content.SharedPreferences
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import io.github.yuxiximyxx.honortoolbox.magicxm4.config.ConfigManager

/**
 * HookContext — 所有 Hook 的基类（传统 Xposed API 版，v1.5.0 合入荣耀工具箱时移植）。
 *
 * 注：与 magicpods 子包的同名适配层各留一份、互不共用——两边 Log 的
 * 标签与 ConfigManager 绑定各自子包，共用化需改已发布（v1.4.0）的
 * MagicPods 行为，风险大于收益，故隔离复制。
 *
 * 原 MagicXM4 版基于 libxposed 现代 API（module.hook(...).intercept { chain -> }）。
 * 本版提供同名同形的适配层，让各 hook 文件的拦截点代码逐字保留、只换底座：
 * - [HookModule.hook] 返回 [HookBuilder]，intercept 的 lambda 收到 [Chain]；
 * - [Chain.proceed] 对方法走 XposedBridge.invokeOriginalMethod（可带新参数），
 *   对构造器为 no-op（原构造已在 after 阶段执行完，返回值即 thisObject）；
 * - intercept lambda 的返回值语义与 libxposed 对齐：非 null/Unit 的返回值
 *   即方法结果（等价于传统 API 在 before 阶段 param.setResult(...)，原方法
 *   不再执行——若 lambda 内已 proceed 过则原方法已手动执行完毕）；
 *   返回 null/Unit 时，若已 proceed 则保留 proceed 的结果，否则结果为 null
 *   （合成回调/吞掉原方法的那批拦截点正是靠这条语义工作）。
 * - hookBefore/hookAfter/hookConstructorAfter 与原版同语义：
 *   hookBefore 里给 HookParam.result 赋值（含赋 null）即跳过原方法；
 *   hookAfter 里赋值即覆盖原结果；两者异常都不传播、回退原行为。
 */
abstract class HookContext {
    /** 兼容原 libxposed 写法的适配对象：module.hook(member).intercept { } */
    val module: HookModule = HookModule

    lateinit var appClassLoader: ClassLoader
    lateinit var prefs: SharedPreferences
    lateinit var packageName: String

    abstract fun onHook()

    fun fakeDeviceId(): String = ConfigManager.fakeDeviceId()

    fun fakeSupport(): String = ConfigManager.fakeSupport()

    fun refreshConfig() {
        ConfigManager.refreshFromPrefs(prefs)
    }

    fun findClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)

    fun findMethod(className: String, methodName: String, vararg parameterTypes: Class<*>): Method =
        findClass(className).getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }

    fun findConstructor(className: String, vararg parameterTypes: Class<*>): Constructor<*> =
        findClass(className).getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }

    fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method =
        findClass(className).declaredMethods.first { it.name == methodName && it.parameterTypes.size == paramCount }
            .apply { isAccessible = true }

    fun findConstructorByParamCount(className: String, paramCount: Int): Constructor<*> =
        findClass(className).declaredConstructors.first { it.parameterTypes.size == paramCount }.apply { isAccessible = true }

    fun hookAfter(method: Method, block: HookParam.() -> Unit) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val hp = HookParam(param.args.asList(), param.thisObject, param.result)
                try {
                    hp.apply(block)
                    if (hp.hasResult) param.result = hp.result
                } catch (error: Throwable) {
                    Log.e("MagicXM4-Hook", "After hook threw in ${method.name}: ${error.message}")
                    // 不覆盖 param.result：保留原结果
                }
            }
        })
    }

    fun hookBefore(method: Method, block: HookParam.() -> Unit) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val hp = HookParam(param.args.asList(), param.thisObject, null)
                try {
                    hp.apply(block)
                } catch (error: Throwable) {
                    Log.e("MagicXM4-Hook", "Before hook threw in ${method.name}: ${error.message}")
                    return // 异常时不设 result，原方法照常执行
                }
                if (hp.hasResult) param.result = hp.result
            }
        })
    }

    fun hookConstructorAfter(constructor: Constructor<*>, block: HookParam.() -> Unit) {
        XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val hp = HookParam(param.args.asList(), param.thisObject, null)
                try {
                    hp.apply(block)
                } catch (error: Throwable) {
                    Log.e("MagicXM4-Hook", "Constructor hook failed: $constructor", error)
                }
            }
        })
    }
}

/** libxposed 形态的 hook 构建器适配：module.hook(member).intercept { chain -> ... } */
object HookModule {
    fun hook(member: Member): HookBuilder = HookBuilder(member)
}

class HookBuilder(private val member: Member) {
    fun intercept(block: (Chain) -> Any?) {
        if (member is Constructor<*>) {
            // 构造器无法在 before 阶段 invokeOriginalMethod；两个构造拦截点
            // 都是「观察 + proceed」型，改到 after 阶段执行：原构造已完成，
            // chain.proceed() 返回 thisObject，lambda 返回值忽略。
            XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val chain = Chain(member, param.thisObject, param.args, constructorMode = true)
                    try {
                        block(chain)
                    } catch (error: Throwable) {
                        Log.e("MagicXM4-Hook", "Constructor intercept threw in $member: ${error.message}")
                    }
                }
            })
            return
        }
        XposedBridge.hookMethod(member, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val chain = Chain(member, param.thisObject, param.args, constructorMode = false)
                val ret: Any? = try {
                    block(chain)
                } catch (error: Throwable) {
                    Log.e("MagicXM4-Hook", "Intercept threw in $member: ${error.message}")
                    if (!chain.proceeded) {
                        runCatching { chain.proceed() }
                    }
                    chain.proceededResult
                }
                param.result = when {
                    ret == null || ret === Unit ->
                        if (chain.proceeded) chain.proceededResult else null
                    else -> ret
                }
            }
        })
    }
}

/**
 * libxposed Chain 的传统 API 等价物。
 * args 是宿主参数数组的活视图（写回即改实参，与原版 chain.args[i]=x 语义一致）。
 */
class Chain internal constructor(
    private val member: Member,
    val thisObject: Any?,
    private val backing: Array<Any?>,
    private val constructorMode: Boolean,
) {
    val args: MutableList<Any?> = object : AbstractMutableList<Any?>() {
        override val size: Int get() = backing.size
        override fun get(index: Int): Any? = backing[index]
        override fun set(index: Int, element: Any?): Any? {
            val old = backing[index]
            backing[index] = element
            return old
        }
        override fun add(index: Int, element: Any?) {
            throw UnsupportedOperationException("hook args size is fixed")
        }
        override fun removeAt(index: Int): Any? {
            throw UnsupportedOperationException("hook args size is fixed")
        }
    }

    var proceeded: Boolean = false
        private set
    var proceededResult: Any? = null
        private set

    fun proceed(): Any? = proceed(backing)

    fun proceed(newArgs: Array<Any?>): Any? {
        proceeded = true
        if (constructorMode) {
            proceededResult = thisObject
            return thisObject
        }
        val result = XposedBridge.invokeOriginalMethod(member, thisObject, newArgs)
        proceededResult = result
        return result
    }
}

/** Convenience: get a declared method from a Class, making it accessible. */
fun Class<*>.method(name: String, vararg pt: Class<*>): Method =
    getDeclaredMethod(name, *pt).apply { isAccessible = true }

/**
 * 日志：原版走 libxposed module.log（进 LSPosed 日志）。传统 API 等价通道
 * 是 XposedBridge.log；同时打 android.util.Log 保持原标签习惯。
 */
object Log {
    fun v(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        emit(android.util.Log.VERBOSE, tag, message, null)
    }

    fun i(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.INFO, tag, message, null)
    }

    fun d(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        emit(android.util.Log.DEBUG, tag, message, null)
    }

    fun d(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        emit(android.util.Log.DEBUG, tag, message, throwable)
    }

    fun w(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.WARN, tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.ERROR, tag, message, null)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        emit(android.util.Log.ERROR, tag, message, throwable)
    }

    private fun emit(priority: Int, tag: String, message: String, throwable: Throwable?) {
        runCatching {
            if (throwable != null) XposedBridge.log("$tag: $message\n${android.util.Log.getStackTraceString(throwable)}")
            else XposedBridge.log("$tag: $message")
        }
        runCatching {
            if (throwable != null) android.util.Log.println(priority, tag, "$message\n${android.util.Log.getStackTraceString(throwable)}")
            else android.util.Log.println(priority, tag, message)
        }
    }
}

class HookParam internal constructor(
    val args: List<Any?>,
    val instance: Any?,
    initialResult: Any?,
) {
    var hasResult = false
        private set
    var result: Any? = initialResult
        set(value) {
            hasResult = true
            field = value
        }
}

fun getObjectField(instance: Any?, fieldName: String): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
        }
        cls = cls.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
    if (instance == null) return
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            cls.getDeclaredField(fieldName).apply { isAccessible = true }.set(instance, value)
            return
        }
        cls = cls.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
            it.isAccessible = true
            return it.invoke(instance, *args)
        }
        cls = cls.superclass
    }
    throw NoSuchMethodException(methodName)
}
