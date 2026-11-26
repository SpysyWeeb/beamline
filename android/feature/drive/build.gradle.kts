// Drive feature (docs/architecture.md 2.7 and 3.9): DrivePresenter, a pure function from the trip
// to what is drawn (JVM unit tests), and the Compose overlay: route sheet, stops editor, turn banner
// with lanes, trip bar, status, arrival and resume cards. Android library.
// Owner during the feature round: track D.
plugins {
    // By id only: AGP is already on the classpath (see :routing:valhalla).
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

val javaTarget = JavaVersion.toVersion(libs.versions.java.get())

android {
    namespace = "io.github.spysyweeb.beamline.feature.drive"
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
        unitTests.all {
            it.useJUnitPlatform()
            // N's golden trace (android/routing/testdata/nav/exit24.json) is read from the repository;
            // declared as an input so the tests run again when N rewrites it.
            it.systemProperty("beamline.repoRoot", rootProject.projectDir.parentFile.absolutePath)
            it.inputs.file(rootProject.file("routing/testdata/nav/exit24.json"))
                .withPropertyName("goldenTrace").withPathSensitivity(PathSensitivity.NONE).optional()
        }
    }

    // The exit 24 trip as the session hands it over (src/sharedTest): used by the JVM tests of the
    // presenter and by the device test that draws the screens (src/androidTest).
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    }
}

dependencies {
    api(project(":core:api"))
    api(project(":core:units"))
    implementation(project(":ui:theme"))
    // No library beyond Compose (section 1.1): the status bar's icon colour is set with platform calls (Overlay.kt).

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose) // BackHandler (audit F31)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlinx.serialization.json)   // JSON tree parser for the golden trace (no compiler plugin)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
