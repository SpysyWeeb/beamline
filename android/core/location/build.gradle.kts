// Location: position and heading sources behind interfaces, the browsing puck's rules, a simulated source.
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
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // LocationTest reads the app's rules file: a change to it runs the tests again (batch C2).
    inputs.file("../../app/src/main/assets/rules/position.json")
    testLogging {
        events("passed", "failed", "skipped")
    }
}
