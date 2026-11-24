// Routing demo: loads the device routing tiles, routes between two fixed points with every preset
// and draws the routes on a MapLibre map from the Pennsylvania map pack. Its own application id,
// so it installs beside the real app. Its androidTest holds the on-device checks and measurements
// of the routing modules (docs/experiments/09-routing-on-device.md); they use the tiles pushed to
// this app's files folder (android/routing/scripts/device.py).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val javaTarget = JavaVersion.toVersion(libs.versions.java.get())
val mapRenderer = providers.gradleProperty("beamline.mapRenderer").getOrElse("vulkan")

android {
    namespace = "io.github.spysyweeb.beamline.routing.demo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        applicationId = "io.github.spysyweeb.beamline.routingdemo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (providers.gradleProperty("beamline.r8Measure").isPresent) proguardFiles("proguard-measure.pro")
            // Local testing only, as :app
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // One APK per processor type as well as the universal one, to measure what valhalla-mobile adds
    splits {
        abi {
            isEnable = providers.gradleProperty("beamline.abiSplits").isPresent
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = javaTarget
        targetCompatibility = javaTarget
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("../testdata")
        // The app's map style and compose rules (style/, rules/), read by DemoActivity: one copy in git.
        getByName("main").assets.directories.add("../../app/src/main/assets")
    }
}

dependencies {
    implementation(project(":routing:core"))
    implementation(project(":routing:banner"))
    implementation(project(":routing:valhalla"))
    implementation(project(":core:packs"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(if (mapRenderer == "opengl") libs.maplibre.android.opengl else libs.maplibre.android.vulkan)

    androidTestImplementation(project(":routing:packs")) // tile lists of the tars, for coverage
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}
