plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
