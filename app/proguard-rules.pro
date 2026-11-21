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
