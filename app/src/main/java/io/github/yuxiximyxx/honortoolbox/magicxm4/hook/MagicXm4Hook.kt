package io.github.yuxiximyxx.honortoolbox.magicxm4.hook

import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.yuxiximyxx.honortoolbox.magicxm4.config.ConfigManager

/**
 * MagicXm4Hook — MagicXM4 合入工具箱后的统一分发（传统 Xposed API）。
 *
 * 取代原 libxposed 的 HookEntry.onPackageReady：由 ToolboxHook 在
 * handleLoadPackage 里按宿主包名调用本分发，宿主与原版一一对应：
 * - com.android.systemui：SystemUIHeadsetIconHook + ControlCenterNoiseHook
 *   （原版双装路径：控制中心类可能经 AAR 被 systemui 进程加载，
 *   类不存在时 ControlCenterNoiseHook 内部 runCatching 安全失败）；
 * - com.android.bluetooth：HeadsetStateDispatcher + BluetoothUpstreamHeadsetHook；
 * - com.hihonor.imedia.sws：MediaSwsBatteryHook；
 * - com.hihonor.controlcenter：ControlCenterNoiseHook；
 * - com.hihonor.audioaccessorymanager：HonorEarphoneHook。
 * com.android.settings 原版已停用（蓝牙设置页 hook 移除），不分发；
 * SettingsHeadsetHook 文件随源码搬入但不加载（与原版一致）。
 *
 * ── 双耳机分发合并（v1.5.0 核心）──────────────────────────────
 * com.android.bluetooth 进程内，MagicPods（Free4）与 MagicXM4（XM4）
 * 的 HeadsetStateDispatcher 合成一个分发面：两者同钩
 * A2dpService.handleConnectionStateChanged（传统 API 下多 hook 串行
 * 触发）、同注册 A2DP 广播 receiver、同做回补枚举，但各自入口先过
 * 本方设备谓词、非本方设备立即返回，分流判据互斥：
 * - Free4：PodProfiles.matchName（蓝牙名/别名含 Enco Free4 /
 *   Enco Air4 Pro 名 token，或名中嵌欢律 productId 068C10/06C010/
 *   067C10 等）；
 * - XM4：蓝牙名/别名含 "WH-1000XM4" 或 "XM4"。
 * 两谓词取值域不交叠（OPPO 名不含 XM4 字样、索尼名不含 Enco token
 * 与欢律 productId）。命中后各自走本方 RfcommController（欢律 HEM
 * 通道 / 索尼 SPP 通道，按设备地址建连）、各自写本方
 * Settings.Global 键（magicorig_* / magicxm4_* 前缀隔离），状态
 * 广播、电量回写、ANC 状态互不串扰。其余四宿主两套 hook 并行
 * 装载、各自按本方连接态/设备身份过滤，互不干扰。
 *
 * 配置通道：原 getRemotePreferences("magicxm4_settings") 换 [FilePrefs]
 * 落盘现读（/sdcard/Download/HonorToolbox/magicxm4/），键名/默认值不变。
 */
object MagicXm4Hook {
    private const val TAG = "MagicXM4-HookEntry"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val hooks: List<HookContext> = when (lpparam.packageName) {
            "com.android.systemui" -> listOf(SystemUIHeadsetIconHook, ControlCenterNoiseHook)
            "com.android.bluetooth" -> listOf(HeadsetStateDispatcher, BluetoothUpstreamHeadsetHook)
            "com.hihonor.imedia.sws" -> listOf(MediaSwsBatteryHook)
            "com.hihonor.controlcenter" -> listOf(ControlCenterNoiseHook)
            "com.hihonor.audioaccessorymanager" -> listOf(HonorEarphoneHook)
            else -> return
        }
        // 注：XM4 的 DeviceIdentity 无 warm()（currentApp() 每次反射取
        // Application、无缓存可预热），与 MagicPodsHook 的同名步骤差异在此。
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
