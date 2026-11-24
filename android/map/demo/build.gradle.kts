// Map demo: the host app for track M's device checks (route lines, pins, markers, camera on the
// exit 24 geometry; docs/architecture.md 5.1). Not shipped; its own application id, so it installs
// beside the real app. Placeholder from Round 0: track M writes its activity.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val javaTarget = JavaVersion.toVersion(libs.versions.java.get())

android {
    namespace = "io.github.spysyweeb.beamline.map.demo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        applicationId = "io.github.spysyweeb.beamline.mapdemo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = javaTarget
        targetCompatibility = javaTarget
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        // The app's map style (style/, rules/): one copy in git, as :routing:demo reads it.
        getByName("main").assets.directories.add("../../app/src/main/assets")
    }
}

dependencies {
    implementation(project(":map:layers"))
    implementation(project(":map:scene"))
    implementation(project(":core:packs"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}
