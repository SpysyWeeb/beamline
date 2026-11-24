// Getting state packs (docs/architecture-downloads.md, sections 2-6): the catalog format and its
// parser, the address and network rules, PackHttp (the only code of the app that opens network
// connections, rule N1), the catalog cache, DownloadEngine (state machine, resume, hashing,
// unpacking, space) and the hand-off to the installer. Plain Kotlin/JVM: tested on the JVM against
// a local Range server. download_rules.json is copied from packbuilder/catalog/ by
// android/routing/scripts/sync_data.py. Owner during the feature round: track G.
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
    // Types of :core:api (Bounds, InstalledData, InstallStep, ...) appear in the public signatures.
    api(project(":core:api"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The data-file check compares download_rules.json with packbuilder/ (repo root = ../../..)
    systemProperty("beamline.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
