plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
	id("com.chaquo.python")version "17.0.0"
}

android {
    namespace = "com.zhangjq0908.iradio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhangjq0908.iradio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3.6"
    
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
     }
        // 3️⃣ Python 运行环境 + 依赖安装
    chaquopy {
        defaultConfig {
               buildPython("C:/Users/Ami/AppData/Local/Programs/Python/Python310/python.exe")
                      // 本地解释器路径，可绝对路径
                pip {
				      
                      install("D:/yt_dlp-2025.12.8-py3-none-any.whl")
				      //install("requests")
               // install"tqdm==4.66.1"     // 可选，进度条美化
			    }
           
	    }	
    }
    signingConfigs {
        create("release") {
            // 替换成你的 Release keystore 路径和密码
            keyAlias = "key88"
            keyPassword = ""
            storeFile = file("C:/Users/Ami/88key.jks")
            storePassword = ""
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // 跳过 lintVital 检查，避免国内网络超时
            lint {
                abortOnError = false
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 跳过 lintVital 检查，避免国内网络超时
            lint {
                abortOnError = false
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "rome-utils-*.jar",
                "**/rome-utils-*.jar",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        dataBinding = false
        viewBinding = true
    }
}

repositories {
    // 国内镜像优先
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    google()      // 官方仓库保留
    mavenCentral()
    maven { url = uri("https://jitpack.io") } // JitPack 特殊依赖
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    implementation("com.google.android.exoplayer:exoplayer-core:2.18.1")
    implementation("com.google.android.exoplayer:exoplayer-hls:2.18.1")
    implementation("com.google.android.exoplayer:extension-okhttp:2.18.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("com.rometools:rome:2.1.0")
    implementation("com.rometools:rome-modules:2.1.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
	//implementation("androidx.media:media:1.7.0")
}
