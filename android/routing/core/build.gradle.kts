// Route model and planner: requests, presets, the engine's answers turned into a route model,
// errors in plain words, stop order. Plain Kotlin/JVM: no Android, no UI. The engine sits behind
// the RoutingEngine interface (valhalla-mobile on the phone in :routing:valhalla, a fake in tests).
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

sourceSets {
    // Recorded engine answers and the trips (android/routing/testdata), shared with the other modules
    test { resources.srcDir("../testdata") }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The data-file check compares the copies with packbuilder/ (repo root = ../../..)
    systemProperty("beamline.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
