// Map scene and camera (docs/architecture.md 2.5 and 3.7): MapSceneMapper turns the trip and the
// puck into what the map draws; CameraDirector decides the camera (rules K1-K7, camera.json copied
// from packbuilder/routing/camera.json by android/routing/scripts/sync_data.py). Plain Kotlin/JVM,
// so a later car screen uses it the same way. Owner during the feature round: track M.
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
    api(project(":core:api"))
    api(project(":core:location"))
    implementation(libs.kotlinx.serialization.json)   // JSON tree parser for camera.json

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The data-file check compares camera.json with packbuilder/ (repo root = ../../..)
    systemProperty("beamline.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
