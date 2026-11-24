// Search feature (docs/architecture.md 2.6 and 3.8): SearchFeature, the UI-free controller (JVM
// unit tests), and the Compose search bar, search screen and place sheet. Android library.
// Owner during the feature round: track S.
plugins {
    // By id only: AGP is already on the classpath (see :routing:valhalla).
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

val javaTarget = JavaVersion.toVersion(libs.versions.java.get())

android {
    namespace = "io.github.spysyweeb.beamline.feature.search"
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

    sourceSets {
        // The app's style sprites (style/sprites/) for the screenshot tests: one copy in git, as
        // :map:demo and :routing:demo read it (docs/architecture.md 5.1 S).
        getByName("androidTest").assets.directories.add("../../app/src/main/assets")
    }
}

dependencies {
    api(project(":core:api"))
    api(project(":core:search"))
    api(project(":core:units"))
    implementation(project(":ui:theme"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)   // search_app.json, poi_icons.json

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
