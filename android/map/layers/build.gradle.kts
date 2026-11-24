// The app's layers on MapLibre (docs/architecture.md 2.5): route lines, pins, the puck and the
// driving arrow, camera moves, tap and long-press. MapLibreController implements :core:api's
// MapController. Android library. Owner during the feature round: track M.
plugins {
    // By id only: AGP is already on the classpath (see :routing:valhalla).
    id("com.android.library")
}

val javaTarget = JavaVersion.toVersion(libs.versions.java.get())
// MapLibre renderer, as :app: "vulkan" (org.maplibre.gl:android-sdk) or "opengl" (android-sdk-opengl).
val mapRenderer = providers.gradleProperty("beamline.mapRenderer").getOrElse("vulkan")
require(mapRenderer == "vulkan" || mapRenderer == "opengl") { "beamline.mapRenderer must be vulkan or opengl" }

android {
    namespace = "io.github.spysyweeb.beamline.map.layers"
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

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    api(project(":core:api"))
    api(if (mapRenderer == "opengl") libs.maplibre.android.opengl else libs.maplibre.android.vulkan)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)   // route_colors.json

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}
