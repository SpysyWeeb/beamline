// The shared kernel of the feature round (docs/architecture.md, sections 1 and 2): places, the
// installed-data view, the navigation session interface and its state, the map scene and camera
// types. Plain Kotlin/JVM with no project dependencies, so it can be frozen while six tracks build
// on it. Changes only through the orchestrator (docs/architecture.md 5.2).
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
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The contract's "same as" claims are checked against the real modules (tests only).
    testImplementation(project(":routing:banner"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
