import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.agp.app)
    // AGP 9 内置 Kotlin：不再 apply org.jetbrains.kotlin.android（apply 会直接报错），
    // 与 MagicPods 同款。
    alias(libs.plugins.composeCompiler)
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
        versionCode = 2
        versionName = "1.0.1"
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
    implementation("androidx.activity:activity-compose:1.9.2")
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
}
