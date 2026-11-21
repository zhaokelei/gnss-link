plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "top.wxip.gpslink"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.wxip.gpslink"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.5.0"
    }

    // Release 签名。未签名的 APK 无法安装（系统会报"安装包异常/解析失败"）。
    // 优先读环境变量（GitHub Actions Secrets），本地回退到仓库内 keystore。
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("SIGNING_KEY_STORE_PATH")
                ?: "../keystore/gpslink.jks"
            storeFile = file(ksPath)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "gpslink2026"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "gpslink"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "gpslink2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 按 ABI 拆分：64 位 arm64-v8a、32 位 armeabi-v7a，外加通用包兜底。
    // 本项目为纯 Kotlin，无 native so；拆分后各包内容一致，仅 android:abi 声明不同，
    // 便于应用市场/用户按架构选择，也方便 CI 分别产出。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.service)
}
