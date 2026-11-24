// Top-level build file. Plugins are declared here once (apply false) so every module uses the
// versions from gradle/libs.versions.toml. Declaring the Kotlin plugins here also lifts the
// Kotlin Gradle plugin that AGP 9 brings (2.2.10) to the catalog's Kotlin version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Gradle isolation for parallel work in one checkout (docs/architecture.md, section 5.3).
// With -Pbeamline.buildRoot=<dir> (relative to this folder) every project builds into
// <dir>/<project path, ':' as '/'> instead of its own build/ folder, so builds started from
// different shells never read or delete each other's outputs. Combine it with
// --project-cache-dir and -Pkotlin.project.persistent.dir. Without the property nothing changes.
providers.gradleProperty("beamline.buildRoot").orNull?.let { root ->
    val base = rootProject.file(root)
    allprojects {
        val rel = if (path == ":") "_root" else path.removePrefix(":").replace(':', '/')
        layout.buildDirectory.set(File(base, rel))
    }
}
