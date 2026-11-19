plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Published builds (GitHub Releases, .github/workflows/release.yml) get their version and signing key
// from the environment; a local build keeps version 1 and signs its release build with the debug key.
val publishedVersionCode = providers.environmentVariable("VERSION_CODE").orNull?.toInt() ?: 1
val publishedVersionName = providers.environmentVariable("VERSION_NAME").orNull ?: "0.1.0"
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE").orNull

android {
    namespace = "dev.wellbeing.phone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Same application id as the watch app: the Wearable Data Layer requires it.
        applicationId = "dev.wellbeing.app"
        minSdk = 34
        targetSdk = 36
        versionCode = publishedVersionCode
        versionName = publishedVersionName
        // MapLibre ships native code for four architectures: the Pixel 8 needs arm64, the emulator x86_64.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // The exported schemas let the migration test build each version of the database. Robolectric
    // reads the variant's assets, not the test source set's: they ship in debug builds only.
    sourceSets {
        getByName("debug").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.location)
    implementation(libs.play.services.wearable)
    implementation(libs.maplibre.android)
    implementation(libs.wear.remote.interactions)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
}
