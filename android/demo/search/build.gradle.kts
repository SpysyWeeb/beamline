// Search demo: a search box with results as you type near downtown Erie or Pittsburgh, nothing
// else. Its own application id, so it installs beside the Beamline app. Uses :core:search, plus
// :core:search-bench for its bench and dump modes. The pack's search and address files are copied
// to the app's storage with android/scripts/search_demo.py push
// (see docs/experiments/10-search-on-device.md).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val javaTarget = JavaVersion.toVersion(libs.versions.java.get())

android {
    namespace = "io.github.spysyweeb.beamline.searchdemo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        applicationId = "io.github.spysyweeb.beamline.searchdemo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Local testing only (as the :app module): signed with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = javaTarget
        targetCompatibility = javaTarget
    }

    buildFeatures {
        compose = true
    }
}

// The benchmark's query list is read from the APK's assets (one copy, generated at build time
// from android/core/search/conformance/search_queries.json).
abstract class BenchAssetsTask : DefaultTask() {
    @get:InputFile
    abstract val queries: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        queries.get().asFile.copyTo(File(dir, "search_queries.json"), overwrite = true)
    }
}
val benchAssets = tasks.register<BenchAssetsTask>("benchAssets") {
    queries.set(rootProject.layout.projectDirectory.file("core/search/conformance/search_queries.json"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(benchAssets, BenchAssetsTask::outputDir)
    }
}

dependencies {
    implementation(project(":core:search"))
    implementation(project(":core:search-bench"))   // bench and dump modes of the demo

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)   // the bench writes its JSON report
}
