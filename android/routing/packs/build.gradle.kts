// Routing packs on the device: install, update and remove per-state Valhalla tile packs in the
// one tile tar the engine loads, with the rules of packbuilder/routing/packs.py (tar layout).
// Plain Kotlin/JVM, no Android; the pack manager screen calls it later.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(project(":routing:core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The tests run against the real PA and OH packs (about 1.3 GB, read only) and write test devices
// under <repo>/data/routing-packs-test. Point them elsewhere with -Pbeamline.routingPacks=<dir> and
// -Pbeamline.routingPacksWork=<dir>; without packs the tests that need them are skipped.
val repoRoot = rootProject.projectDir.parentFile
val packsDir = providers.gradleProperty("beamline.routingPacks").orElse(File(repoRoot, "data/routing/packs").path)
val workDir = providers.gradleProperty("beamline.routingPacksWork").orElse(File(repoRoot, "data/routing-packs-test").path)

tasks.test {
    useJUnitPlatform()
    systemProperty("beamline.routingPacks", packsDir.get())
    systemProperty("beamline.routingPacksWork", workDir.get())
    maxHeapSize = "1g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
