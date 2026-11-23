# 传统 Xposed 入口类只被 assets/xposed_init 以字符串引用，
# R8 看不到引用，必须整包保留，防止入口类被裁剪/改名。
-keep class io.github.yuxiximyxx.shadeclock.** { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }

# Xposed API 由 LSPosed 在运行时提供，APK 内不存在这些类
-dontwarn de.robv.android.xposed.**
-dontwarn android.content.res.XResources**

# 资源库增强（LibraryPlus 合入）：入口经 ToolboxHook 分发、Provider
# 在 Manifest 里以类名声明，整包保留防止裁剪/改名
-keep class io.github.yuxiximyxx.honortoolbox.libraryplus.** { *; }
# DexKit 使用 JNI
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# MagicPods（v1.4.0 合入）：详情页被控制中心 hook 以类名字符串
# setClassName 直达、QS 磁贴在 Manifest 声明、配置类用
# kotlinx.serialization 生成序列化器，整包保留防改名/裁剪
-keep class io.github.yuxiximyxx.honortoolbox.magicpods.** { *; }

# PalmPurify（v1.6.0 合入）：hook 类在宿主进程被装载并反射
# 访问宿主与自身成员，整包保留防改名/裁剪
-keep class io.github.yuxiximyxx.honortoolbox.palmpurify.** { *; }

# BetterHeybox（v1.7.0 合入）：移植树（com.better.heybox）整包
# 保留——hook 类在宿主进程被装载、大量反射访问宿主与自身成员，
# 且有类名字符串自检（如 com.better.heybox 前缀判定）；Yuki 适配
# 层（com.highcapable.yukihookapi）是本仓自写 shim，一并保留；
# 外部玻璃库 com.example.liquidglass 同样整包保留防裁剪
-keep class com.better.heybox.** { *; }
-keep class com.highcapable.yukihookapi.** { *; }
-keep class com.example.liquidglass.** { *; }
-dontwarn com.example.liquidglass.**

# MOS液态输入（v1.8.0 合入）：移植树（com.superboyyy.mosinputglass）
# 整包保留——hook 逻辑在宿主进程被装载、大量反射访问宿主与系统
# 玻璃类、且有包名前缀自检（ownerPrefix）；工具箱侧子包
# （wetypeglass）含 Manifest 声明的 Provider，一并保留
-keep class com.superboyyy.mosinputglass.** { *; }
-keep class io.github.yuxiximyxx.honortoolbox.wetypeglass.** { *; }
