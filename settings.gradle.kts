pluginManagement {
    repositories {
        // 优先走国内镜像（阿里云），公网直连在国内常被 TLS 掐断；
        // 镜像里没有的包再回落到官方源。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
include(":app")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        // 小黑盒增强（BetterHeybox 合入，v1.7.0）：其液态玻璃
        // 底栏依赖的 com.example.liquidglass 仅在 JitPack 分发
        maven("https://jitpack.io")
        maven("https://s01.oss.sonatype.org/content/repositories/releases/")
    }
}
