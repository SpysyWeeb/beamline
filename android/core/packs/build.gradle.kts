// Packs: find, check, install and remove packs; compose the map style.
// Plain Kotlin/JVM on purpose: no Android, no UI. The app, a future Android Auto screen and the
// unit tests all use it the same way.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

val javaTarget = libs.versions.java.get()

java {
    sourceCompatibility = JavaVersion.toVersion(javaTarget)
    targetCompatibility = JavaVersion.toVersion(javaTarget)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaTarget))
    }
}

dependencies {
    // Shared types of the feature round (PackInfo, Bounds; docs/architecture.md 1.1)
    api(project(":core:api"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The real format-3 packs (packbuilder/pack/assemble.py writes them to <repo>/data/r3-packs); the
// tests that read them are skipped when they are not there. Elsewhere: -Pbeamline.r3Packs=<dir>.
val repoRoot = rootProject.projectDir.parentFile
val r3Packs = providers.gradleProperty("beamline.r3Packs").orElse(File(repoRoot, "data/r3-packs").path)

tasks.test {
    useJUnitPlatform()
    systemProperty("beamline.r3Packs", r3Packs.get())
    testLogging {
        events("passed", "failed", "skipped")
    }
}
