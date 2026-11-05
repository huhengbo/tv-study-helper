import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号以根目录 VERSION 为准；versionCode = x*10000 + y*100 + z，覆盖安装要求它单调递增
val appVersion = rootProject.file("VERSION").readText().trim()
val appVersionCode = appVersion.split(".").map(String::toInt).let { (x, y, z) -> x * 10000 + y * 100 + z }

// release 签名读取 secrets/android/key.properties（不进版本库）；没有时用 debug 签名，能安装但不能覆盖 release 签名的版本
val signingFile = rootProject.file("secrets/android/key.properties")
val signingProps = signingFile.takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "de.huyc.studyhelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.huyc.studyhelper"
        // 目标电视为小米电视 4A（Android 6.0.1 / API 23）
        minSdk = 23
        // 侧载应用不上架商店；30 可避开 Android 12+ 前台服务类型等限制，同时兼容较新的小米电视
        targetSdk = 30
        versionCode = appVersionCode
        versionName = appVersion
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        if (signingProps != null) create("release") {
            storeFile = signingFile.parentFile.resolve(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (signingProps != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.belerweb:pinyin4j:2.5.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
