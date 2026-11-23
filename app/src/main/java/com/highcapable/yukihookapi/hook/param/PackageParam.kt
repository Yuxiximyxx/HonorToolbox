package com.highcapable.yukihookapi.hook.param

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.highcapable.yukihookapi.hook.core.api.priority.YukiHookPriority
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Member

/**
 * YukiHookAPI 的 PackageParam 在传统 Xposed API 上的等价物
 * （荣耀工具箱合入 BetterHeybox 的适配层）。
 *
 * 只实现原模块实际用到的表面：
 * - [hostClassLoader]：宿主 ClassLoader；
 * - [module]`.appInfo`：模块（即工具箱自身）的 ApplicationInfo，
 *   供原模块读自身版本号做降级下限判定；
 * - [preferences]：原模块拿它读 Yuki 的远程偏好做旧配置迁移。
 *   传统 API 没有远程偏好，本实现返回宿主本地同名
 *   SharedPreferences——迁移逻辑自带「本地已有键不覆盖」
 *   与完成标记，读同一份存储时迁移退化为空操作，不丢配置；
 * - [Member.intercept]：声明式 hook 的落点，见 [HookChain]。
 */
class PackageParam(
    val hostClassLoader: ClassLoader,
    moduleAppInfo: ApplicationInfo,
    private val hostContextProvider: () -> Context?,
) {

    class ModuleInfo(val appInfo: ApplicationInfo)

    val module: ModuleInfo = ModuleInfo(moduleAppInfo)

    fun preferences(name: String): LegacyPrefs = LegacyPrefs(name, hostContextProvider)

    /** 与 Yuki 远程偏好等价的本地偏好包装（方法名保持原框架的内部名，供反射调用）。 */
    class LegacyPrefs(
        private val name: String,
        private val hostContextProvider: () -> Context?,
    ) {
        @Suppress("FunctionName")
        fun `getCurrent$yukihook_core`(): SharedPreferences {
            val ctx = hostContextProvider()
                ?: throw IllegalStateException("宿主 Context 未就绪")
            return ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }

    /**
     * 把 [Member]（方法/构造器）以传统 XC_MethodHook 注册，
     * 拦截体语义见 [HookChain]，异常经 setThrowable 原样透传
     * 给调用方。方法与构造器的落点不同（v1.7.3 热修）：
     * - 方法：拦截体返回值即最终结果，写进 param.result，
     *   框架据此跳过原方法；
     * - 构造器：只执行拦截体、绝不给 param.result 赋值。
     *   对构造器设 result 会让框架跳过原构造体，应用手里
     *   那个已分配实例的字段初始化被整个跳过、成了空壳
     *   （v1.7.2 点帖子黑屏、无崩溃无报错就是死在这里）；
     *   原构造体改由拦截体内的 proceed 在同一实例上执行，
     *   见 [HookChain.invokeOriginal] 的构造器分支。
     */
    fun Member.intercept(priority: YukiHookPriority, block: HookChain.() -> Any?) {
        val member = this
        val callback = object : XC_MethodHook(priority.value) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val chain = HookChain(
                    member,
                    param.thisObject,
                    param.args ?: emptyArray(),
                )
                try {
                    if (member is Constructor<*>) {
                        chain.block()
                    } else {
                        param.result = chain.block()
                    }
                } catch (t: Throwable) {
                    param.throwable = t
                }
            }
        }
        XposedBridge.hookMethod(member, callback)
    }
}
