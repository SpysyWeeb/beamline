// About and identity (docs/architecture.md 2.8): the Night drive launcher icon (resources
// @mipmap/ic_launcher, ic_launcher_round, monochrome), the splash and its reveal, About, licences
// and the notices file, the first-run notice. Android library; :demo:about hosts it for device
// checks. Owner during the feature round: track A.
plugins {
    // By id only: AGP is already on the classpath (see :routing:valhalla).
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

val javaTarget = JavaVersion.toVersion(libs.versions.java.get())

android {
    namespace = "io.github.spysyweeb.beamline.feature.about"
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
            // Tests compare the splash scene and the notice text with the approved sources
            // (docs/design/app-icon/, docs/architecture.md); repo root = ../../..
            it.systemProperty("beamline.repoRoot", rootProject.projectDir.parentFile.absolutePath)
        }
    }
}

dependencies {
    implementation(project(":ui:theme"))
    api(libs.androidx.core.splashscreen)
    api(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.serialization.json)   // notices.json

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
