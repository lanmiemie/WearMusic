plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shijiu.wearmusic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shijiu.wearmusic"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/wear-music.jks")
            storePassword = "wearmusic123"
            keyAlias = "wearmusic"
            keyPassword = "wearmusic123"
        }
    }

    buildTypes {
        release {
            // R8 混淆压缩 + 无用资源裁剪：material-icons-extended 等大依赖
            // 未引用的图标/类全量打进 dex 会让 APK 超过 50MB，压缩后大幅缩小
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST")
        }
    }

    lint {
        // AGP 8.7 内置 lint 对升级后的 androidx 库存在 UAST 兼容缺陷（lintVital 崩溃），
        // 打包前不跑 release lint 检查，质量检查交给 IDE。
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:netease"))

    // Compose for Wear OS（Material 3 + Expressive 动效）
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.wear.compose:compose-navigation:1.6.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3") // 仅用 Slider
    implementation("androidx.compose.material:material-icons-extended") // 扩展图标（Pause/SkipNext/QrCode2 等）
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 播放
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // 图片
    implementation("io.coil-kt:coil-compose:2.7.0")
}
