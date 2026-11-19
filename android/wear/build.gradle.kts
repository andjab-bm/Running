plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Published builds (GitHub Releases, .github/workflows/release.yml) get their version and signing key
// from the environment; a local build keeps version 1 and signs its release build with the debug key.
val publishedVersionCode = providers.environmentVariable("VERSION_CODE").orNull?.toInt() ?: 1
val publishedVersionName = providers.environmentVariable("VERSION_NAME").orNull ?: "0.1.0"
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE").orNull

android {
    namespace = "dev.wellbeing.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Same application id as the phone app: the Wearable Data Layer requires it.
        applicationId = "dev.wellbeing.app"
        minSdk = 30
        targetSdk = 36
        versionCode = publishedVersionCode
        versionName = publishedVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (releaseKeystore != null) create("published") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The phone and the watch must share their signing key: the Data Layer only links apps signed alike.
            signingConfig = signingConfigs.findByName("published") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear)
    // androidx.wear pulls in an old Fragment library that breaks permission results; use a current one.
    implementation(libs.androidx.fragment)
    implementation(libs.wear.ongoing)
    implementation(libs.health.services)
    implementation(libs.wear.tiles)
    implementation(libs.wear.remote.interactions)
    implementation(libs.wear.protolayout)
    implementation(libs.concurrent.futures)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
}
