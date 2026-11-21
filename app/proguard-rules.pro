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
