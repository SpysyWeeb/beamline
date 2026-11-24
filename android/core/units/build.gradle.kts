// The one formatter for distance, duration and clock texts (docs/architecture.md 2.9), driven by
// units.json (copied from packbuilder/routing/units.json by android/routing/scripts/sync_data.py).
// Plain Kotlin/JVM. Finished in Round 0 and frozen during the round.
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
    implementation(libs.kotlinx.serialization.json)   // JSON tree parser only (no compiler plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The copy check compares units.json with packbuilder/routing/units.json (repo root = ../../..)
    systemProperty("beamline.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
