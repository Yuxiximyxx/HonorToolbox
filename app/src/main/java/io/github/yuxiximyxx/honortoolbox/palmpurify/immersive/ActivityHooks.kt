package io.github.yuxiximyxx.honortoolbox.palmpurify.immersive

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.yuxiximyxx.honortoolbox.palmpurify.XposedInterface
import io.github.yuxiximyxx.honortoolbox.palmpurify.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/** 只挂钩框架里的 Activity 生命周期，用来改窗口，不挂钩掌盟自己的类。 */
object ActivityHooks {
    private val installed = AtomicBoolean(false)

    fun install(module: XposedModule) {
        if (!installed.compareAndSet(false, true)) {
            return
        }
        ImmersiveController.attach(module)
        try {
            val activity = Activity::class.java
            val onCreate = activity.getDeclaredMethod("onCreate", Bundle::class.java)
            val onResume = activity.getDeclaredMethod("onResume")
            val onFocus = activity.getDeclaredMethod(
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
            )
            val mode = XposedInterface.ExceptionMode.PROTECTIVE
            module.hook(onCreate).setExceptionMode(mode).intercept { chain ->
                val current = chain.getThisObject() as? Activity
                guard { if (current != null) ImmersiveController.beforeCreate(current) }
                val result = chain.proceed()
                guard { if (current != null) ImmersiveController.afterReady(current) }
                result
            }
            module.hook(onResume).setExceptionMode(mode).intercept { chain ->
                val result = chain.proceed()
                val current = chain.getThisObject() as? Activity
                guard { if (current != null) ImmersiveController.afterReady(current) }
                result
            }
            module.hook(onFocus).setExceptionMode(mode).intercept { chain ->
                val result = chain.proceed()
                val current = chain.getThisObject() as? Activity
                val hasFocus = chain.getArg(0) as? Boolean ?: false
                if (hasFocus) {
                    guard { if (current != null) ImmersiveController.afterReady(current) }
                }
                result
            }
            module.log(Log.INFO, "PalmPurify", "已在主进程安装窗口钩子")
        } catch (error: Throwable) {
            installed.set(false)
            module.log(Log.ERROR, "PalmPurify", "安装窗口钩子失败，掌盟按原逻辑继续", error)
        }
    }

    private fun guard(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            Log.e("PalmPurify", "窗口钩子内部失败", error)
        }
    }
}
