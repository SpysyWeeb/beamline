// Installed packs (docs/architecture.md 2.2): InstalledLibrary implements :core:api's
// InstalledData with one install and removal flow for all four parts of a state pack (format 3),
// the merged routing tile set (:routing:packs), generations and the hold during guidance.
// Plain Kotlin/JVM. Owner during the feature round: track P.
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
    api(project(":core:api"))
    api(project(":core:packs"))       // Readers and InstallRules appear in the constructor
    implementation(project(":routing:packs"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The real format-3 archives and pack folders (packbuilder/pack/assemble.py writes them to
// <repo>/data/r3-packs, read only here); RealArchivesInstallTest and RealArchivesRulesTest are
// skipped without them. Their test devices go to a temporary folder on the same volume as the
// archives (download folders are made of hard links). Elsewhere: -Pbeamline.r3Packs=<dir>.
// The version-2 archive of round 2 (only its manifest is read): -Pbeamline.v2Archive=<file>.
val repoRoot = rootProject.projectDir.parentFile
val r3Packs = providers.gradleProperty("beamline.r3Packs").orElse(File(repoRoot, "data/r3-packs").path)
val v2Archive = providers.gradleProperty("beamline.v2Archive")
    .orElse(File(repoRoot, "data/android/archives/us-pa-2026-11-14.tar").path)

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    systemProperty("beamline.r3Packs", r3Packs.get())
    systemProperty("beamline.v2Archive", v2Archive.get())
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
