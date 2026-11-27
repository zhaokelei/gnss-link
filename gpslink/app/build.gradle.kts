plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "top.wxip.gpslink"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.wxip.gpslink"
        minSdk = 19
        targetSdk = 34
        versionCode = 26
        versionName = "1.3.8"
        // API 19/20 不会自动分包，appcompat+material 方法数可能超 64K，必须开启并走 legacy multidex
        multiDexEnabled = true
        // minSdk<21 时 AGP 会尝试把矢量图转 PNG，遇到 @color 引用会失败；
        // 交给 AppCompat 运行时渲染矢量图
        vectorDrawables.useSupportLibrary = true
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    implementation("androidx.multidex:multidex:2.0.1")
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.service)
    // 扫码：4.3.0 默认要求 minSdk 24，这里强制用 core 3.3.0 兼容 API 19
    implementation(libs.zxing.embedded) {
        exclude(group = "com.google.zxing", module = "core")
    }
    implementation(libs.zxing.core)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
}
