pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()

        // 国内镜像加速 Gradle 插件
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/gradle-plugin/") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

    repositories {
        // ======== 国内镜像（加速） ========
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }

        // ======== 官方仓库（必须保留） ========
        google()
        mavenCentral()

        // ======== JitPack（NewPipeExtractor 必须依赖） ========
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "iRadio_fixed"
include(":app")
