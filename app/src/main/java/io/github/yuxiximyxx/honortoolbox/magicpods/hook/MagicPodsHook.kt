package io.github.yuxiximyxx.honortoolbox.magicpods.hook

import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.yuxiximyxx.honortoolbox.magicpods.config.ConfigManager
import io.github.yuxiximyxx.honortoolbox.magicpods.utils.DeviceIdentity

/**
 * MagicPodsHook — MagicPods 合入工具箱后的统一分发（传统 Xposed API）。
 *
 * 取代原 libxposed 的 HookEntry.onPackageReady：由 ToolboxHook 在
 * handleLoadPackage 里按宿主包名调用本分发，宿主与原版一一对应：
 * - com.android.systemui：SystemUIHeadsetIconHook + ControlCenterNoiseHook
 *   （原版双装路径：控制中心类可能经 AAR 被 systemui 进程加载，
 *   类不存在时 ControlCenterNoiseHook 内部 runCatching 安全失败）；
 * - com.android.bluetooth：HeadsetStateDispatcher + BluetoothUpstreamHeadsetHook；
 * - com.hihonor.imedia.sws：MediaSwsBatteryHook（占位，见其文件头）；
 * - com.hihonor.controlcenter：ControlCenterNoiseHook；
 * - com.hihonor.audioaccessorymanager：HonorEarphoneHook。
 * com.android.settings 原版已停用（蓝牙设置页 hook 移除），不分发。
 *
 * 配置通道：原 getRemotePreferences("magicorig_settings") 换 [FilePrefs]
 * 落盘现读（/sdcard/Download/HonorToolbox/magicpods/），键名/默认值不变。
 */
object MagicPodsHook {
    private const val TAG = "MagicPods-HookEntry"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val hooks: List<HookContext> = when (lpparam.packageName) {
            "com.android.systemui" -> listOf(SystemUIHeadsetIconHook, ControlCenterNoiseHook)
            "com.android.bluetooth" -> listOf(HeadsetStateDispatcher, BluetoothUpstreamHeadsetHook)
            "com.hihonor.imedia.sws" -> listOf(MediaSwsBatteryHook)
            "com.hihonor.controlcenter" -> listOf(ControlCenterNoiseHook)
            "com.hihonor.audioaccessorymanager" -> listOf(HonorEarphoneHook)
            else -> return
        }
        // 子线程写 Settings 不能靠 ActivityThread.currentApplication()（ThreadLocal）
        runCatching { DeviceIdentity.warm() }
        for (hook in hooks) {
            try {
                hook.appClassLoader = lpparam.classLoader
                hook.packageName = lpparam.packageName
                hook.prefs = FilePrefs()
                ConfigManager.init(hook.prefs)
                hook.onHook()
                Log.i(TAG, "onHook OK pkg=${lpparam.packageName} hook=${hook.javaClass.simpleName}")
            } catch (error: Throwable) {
                Log.e(TAG, "FAILED init ${lpparam.packageName}/${hook.javaClass.simpleName}", error)
            }
        }
    }
}
