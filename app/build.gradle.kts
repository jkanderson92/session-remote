plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sessionremote.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sessionremote.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        // arm64 = your S24; x86_64 = the cloud emulator test. Keeps the APK small.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Fixed debug key committed to the repo so every CI build installs over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // VLC engine: plays the HERO4's quirky UDP MPEG-TS preview and camera files over HTTP
    implementation("org.videolan.android:libvlc-all:3.7.6")
}
