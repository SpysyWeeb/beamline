// The navigation session (docs/architecture.md 2.4 and 3): BeamlineNavigationSession implements
// :core:api's NavigationSession with the rules of guidance.json (copied from
// packbuilder/routing/guidance.json by android/routing/scripts/sync_data.py), the route follower
// port, the simulated drive and the trip store. Plain Kotlin/JVM: the phone, later Android Auto and
// the JVM tests use it the same way. Owner during the feature round: track N.
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
    // Types of these modules appear in the session's public signatures.
    api(project(":core:api"))
    api(project(":core:location"))
    api(project(":core:settings"))
    api(project(":routing:core"))
    api(project(":routing:banner"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

sourceSets {
    // Recorded engine answers, the trips and the golden traces (android/routing/testdata)
    test { resources.srcDir("../../routing/testdata") }
}

tasks.test {
    useJUnitPlatform()
    // The data-file check compares guidance.json with packbuilder/ (repo root = ../../..)
    systemProperty("beamline.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    // Requests the recordings lack: the session tests list them here, android/routing/scripts/record_session.py records them
    val missing = layout.buildDirectory.file("missing-requests.json").get().asFile
    systemProperty("beamline.missingRequests", missing.absolutePath)
    // -Pbeamline.writeGolden=true rewrites android/routing/testdata/nav/exit24.json instead of comparing with it
    systemProperty("beamline.writeGolden", providers.gradleProperty("beamline.writeGolden").getOrElse("false"))
    doFirst { missing.delete() }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
