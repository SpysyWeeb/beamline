// Getting state packs, the Android side (docs/architecture-downloads.md, sections 1, 5 and 6):
// GetPacks (the in-process controller I creates once in AppGraph), StatesScreen (the first-run
// "Choose your state" and the States screen), the drawn US map and its tap rules, DownloadService
// (foreground service, type dataSync), the notifications, the network watcher and the tagging
// socket factory. Android library; :demo:states hosts it for device checks. Owner during the
// feature round: track G.
plugins {
    // By id only: AGP is already on the classpath (see :routing:valhalla).
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

val javaTarget = JavaVersion.toVersion(libs.versions.java.get())

android {
    namespace = "io.github.spysyweeb.beamline.feature.states"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = javaTarget
        targetCompatibility = javaTarget
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    // Types of these appear in GetPacks' and DownloadService's public signatures
    // (InstalledData, CatalogState, DownloadJob, SettingsStore, UnitFormatter, LifecycleService).
    api(project(":core:download"))
    api(project(":core:settings"))
    api(project(":core:units"))
    api(libs.androidx.lifecycle.service)
    implementation(project(":ui:theme"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)   // assets/states/us_states.json, getpacks/ui.json

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
