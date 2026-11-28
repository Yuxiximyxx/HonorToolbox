import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.agp.app)
    // AGP 9 内置 Kotlin：不再 apply org.jetbrains.kotlin.android（apply 会直接报错），
    // 与 MagicPods 同款。
    alias(libs.plugins.composeCompiler)
    // MagicPods 合入（v1.4.0）：ConfigManager 的 AppConfig 与强提示
    // 数据类用 kotlinx.serialization（原 MagicPods 同款插件）
    alias(libs.plugins.kotlinSerialization)
}

// 发布签名密钥库。*.jks 在 .gitignore 里，克隆仓库后该文件默认不存在；
// 构建脚本据此回退到 debug 签名，保证 assembleRelease 仍能产出可安装的 APK。
// CI（GitHub Actions）通过环境变量 HONORTOOLBOX_KEYSTORE_FILE 指向 secrets 解码出的
// 密钥库，并用 HONORTOOLBOX_STORE_PASSWORD / HONORTOOLBOX_KEY_ALIAS /
// HONORTOOLBOX_KEY_PASSWORD 注入凭据。
val releaseKeystore = System.getenv("HONORTOOLBOX_KEYSTORE_FILE")
    ?.takeIf { it.isNotBlank() }?.let { file(it) }
    ?: file("signing/honortoolbox.jks")

android {
    namespace = "io.github.yuxiximyxx.honortoolbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yuxiximyxx.honortoolbox"
        minSdk = 34   // Android 14 (MagicOS 8 起，与 MagicPods 一致)
        targetSdk = 35
        versionCode = 31
        versionName = "1.10.2"
        // 资源库增强（LibraryPlus 合入）带 DexKit 原生库，只留 arm64
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("HONORTOOLBOX_STORE_PASSWORD") ?: "honortoolbox2026"
            keyAlias = System.getenv("HONORTOOLBOX_KEY_ALIAS") ?: "honortoolbox"
            keyPassword = System.getenv("HONORTOOLBOX_KEY_PASSWORD") ?: "honortoolbox2026"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            multiDexEnabled = true
            // 密钥库缺失时回退到 debug 签名，避免 assembleRelease 直接失败
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                println("HonorToolbox: 未找到 $releaseKeystore，assembleRelease 将使用 debug 签名")
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo.includeInApk = false

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(JavaVersion.VERSION_21.majorVersion)
        }
    }

    kotlin {
        jvmToolchain(JavaVersion.VERSION_21.majorVersion.toInt())
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    tasks.withType<com.android.build.gradle.internal.tasks.CheckAarMetadataTask>().configureEach {
        enabled = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**.version"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "okhttp3/**"
            excludes += "kotlin/**"
            excludes += "org/**"
            excludes += "**.properties"
            excludes += "**.bin"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
}

/**
 * 打包后校验传统 Xposed 模块声明：assets/xposed_init 必须存在且指向模块入口类。
 * 该文件一旦被打包规则吞掉，LSPosed 就识别不到模块，而构建仍会「成功」——强制校验。
 */
val verifyXposedModule = tasks.register("verifyXposedModule") {
    group = "verification"
    description = "校验 APK 内 assets/xposed_init 是否完整，防止 LSPosed 识别不到模块"

    val apkDirectory = layout.buildDirectory.dir("outputs/apk")

    doLast {
        val dir = apkDirectory.get().asFile
        val apks = if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        } else emptyList()

        if (apks.isEmpty()) {
            logger.warn("verifyXposedModule: $dir 下没有 APK，跳过校验")
            return@doLast
        }

        var failed = false
        for (apk in apks) {
            logger.lifecycle("verifyXposedModule: 检查 ${apk.name}")
            try {
                ZipFile(apk).use { zip ->
                    val init = zip.getEntry("assets/xposed_init")
                    if (init == null) {
                        failed = true
                        logger.error("  [FAIL] 缺少 assets/xposed_init —— LSPosed 不会把它识别为模块")
                    } else {
                        val entries = zip.getInputStream(init).bufferedReader()
                            .readLines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (entries.isEmpty()) {
                            failed = true
                            logger.error("  [FAIL] assets/xposed_init 为空")
                        } else {
                            entries.forEach { logger.lifecycle("  [OK] 入口类: $it") }
                        }
                    }
                }
            } catch (error: Throwable) {
                failed = true
                logger.error("  [FAIL] 读取 ${apk.name} 失败: ${error.message}")
            }
        }

        if (failed) {
            throw GradleException(
                "verifyXposedModule 失败：APK 内的 Xposed 模块声明不完整，" +
                    "构建出的 APK 在 LSPosed 模块列表里不会出现。"
            )
        }
        logger.lifecycle("verifyXposedModule: 全部通过")
    }
}

// 每次打包完成后自动校验
tasks.matching { it.name.startsWith("assemble") }.configureEach {
    finalizedBy(verifyXposedModule)
}

configurations.configureEach {
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
    // Force Kotlin stdlib to match our compiler version
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("2.4.10")
            because("Must match Kotlin compiler version")
        }
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    // v1.1.0 拆地雷：miuix 0.8.8 的 WindowBottomSheet/WindowDialog/
    // ListPopup/SuperBottomSheet/SearchBar 内部都无条件用
    // navigationevent-compose 的 NavigationBackHandler，强制要求
    // 宿主提供 NavigationEventDispatcherOwner（v1.0.1 首页重启
    // 菜单一点就闪退的根因——当时 activity-compose 钉 1.9.2，
    // ComponentActivity 既不实现该 Owner、也不设 ViewTree 标签）。
    // activity 1.12 起 ComponentActivity 正式实现
    // NavigationEventDispatcherOwner 并在 decor view 上设 ViewTree
    // 标签，activity-compose 1.12.4 配套带 navigationevent-compose
    // 1.0.2（与 miuix 经 JetBrains 工件在 Android 上解析到的版本
    // 一致）。宿主侧另在 setContent 根部显式 CompositionLocalProvider
    // 提供 Owner，双保险覆盖 Dialog 宿主内的弹层组合。
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.navigationevent:navigationevent-compose:1.0.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")

    // 传统 Xposed API（仅编译期引用，运行时由 LSPosed 提供）
    compileOnly("de.robv.android.xposed:api:82")

    // Compose BOM（2026.05.00：Compose 1.10.x / Material3 1.4.x，随 miuix 0.8.8 工具链对齐）
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")

    // Miuix UI（MIUIX 统一风格）：工具链与 MagicPods v2.11 一致
    implementation("top.yukonga.miuix.kmp:miuix:0.8.8")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.8.8")

    // Kyant Backdrop（液态玻璃真采样）：泥坛/贴吧/酷安三家
    // fork 的悬浮 Dock 同款（backdrop 2.0.1）。其 POM 依赖
    // kotlin-stdlib 2.4.10（与本仓一致）与 JB Compose 1.12
    // 薄壳——后者在 Android 上桥接 androidx Compose，泥坛
    // LumaGA 在与本仓相同的 BOM 2026.05.00（Compose UI
    // 1.10.4）下已实证共存，本仓照同一路径接入。shapes 仅
    // 是 backdrop 的 runtime 依赖、用 Capsule 需显式声明
    // （贴吧 fork 同款处理）。
    implementation("io.github.kyant0:backdrop:2.0.1")

    // MagicPods 合入（v1.4.0）：配置 JSON 与详情页/协议层协程
    // （原 MagicPods 同款版本）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DexKit（资源库增强合入）：hook 侧按字符串定位混淆类，
    // 找不到目标时各 hook 自带硬编码类名兜底（原 LibraryPlus 同款）
    implementation("org.luckypray:dexkit:2.3.0")

    // 小黑盒增强（BetterHeybox 合入，v1.7.0）：原模块的液态
    // 玻璃底栏已于 v1.10.0 整套移除（底栏归 LiquidTab 接管），
    // 其 View 版玻璃库依赖（com.github.QWEA0:liquidglass）随之去掉。
    implementation("io.github.kyant0:shapes:1.2.1")
}
