pluginManagement {
    repositories {
        // 阿里云镜像
		mavenCentral()
    
        // 保留Google仓库(备选)
        google()
        maven ("https://maven.aliyun.com/repository/public")
        maven ("https://maven.aliyun.com/repository/google")
        maven ("https://maven.aliyun.com/repository/gradle-plugin")
        maven ("https://maven.aliyun.com/repository/central")
    
        // 华为云镜像
        maven ("https://repo.huaweicloud.com/repository/maven/")
    
        // 腾讯云镜像
        maven ("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    
        // 网易镜像
        maven ("https://mirrors.163.com/maven/repository/maven-public/")
    
        
    
        // 保留中央仓库

    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

    repositories {
        // ======== 官方必须优先 ========


        // ======== 国内镜像（辅助加速） ========
        maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.huaweicloud.com/repository/maven/")
		google()         // 必须第一个
        mavenCentral()   // 其次

        // ======== JitPack（特殊依赖） ========
        maven("https://jitpack.io")
    }
}

rootProject.name = "iRadio_fixed"
include(":app")
