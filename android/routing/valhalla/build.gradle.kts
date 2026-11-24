// The routing engine on the phone: valhalla-mobile (Valhalla 3.9.1, the version the tiles were
// built with) behind :routing:core's RoutingEngine interface. Android library: valhalla-mobile is
// an AAR with native code. The engine is opened from a config FILE (valhalla-mobile's
// Valhalla(configPath) constructor), so no config key passes through the wrapper's config model.
plugins {
    // By id only: AGP (same jar as com.android.application, declared in the root build file) is
    // already on the classpath, and Gradle refuses a second request for it with a version.
    id("com.android.library")
}

val javaTarget = JavaVersion.toVersion(libs.versions.java.get())

android {
    namespace = "io.github.spysyweeb.beamline.routing.mobile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = javaTarget
        targetCompatibility = javaTarget
    }
}

dependencies {
    api(project(":routing:core"))
    implementation(libs.valhalla.mobile)
    testImplementation(libs.junit4)
    // On-device tests of this module run from the demo app (:routing:demo androidTest), which
    // holds the tiles.
}
