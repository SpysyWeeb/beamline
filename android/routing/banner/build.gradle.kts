// Guidance banner model: for every maneuver of a route, what the banner shows (main instruction,
// the following turn beside it, the lane row). A pure function over :routing:core's route model;
// its rules come from banner.json (copied from packbuilder/routing/banner.json). Plain Kotlin/JVM.
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
    test { resources.srcDir("../testdata") }
}

dependencies {
    api(project(":routing:core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
