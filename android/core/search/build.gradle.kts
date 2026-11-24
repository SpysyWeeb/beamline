// Offline search on the device: the Kotlin port of packbuilder/search/query.py (search engine)
// and packbuilder/addresses/addrlib (address normaliser, parser and house-number lookup).
// Plain Kotlin/JVM on purpose: no Android, no UI. The phone app, a later Android Auto screen,
// the demo app and the JVM tests all call it the same way.
//
// SQLite comes from androidx.sqlite:sqlite-bundled (a Kotlin Multiplatform library). This JVM
// module resolves its JVM variant (natives for Windows/Linux/macOS inside the jar, used by the
// tests); an Android app that depends on this module resolves the Android variant of the same
// library (libsqliteJni.so per ABI) on its own.
import java.util.Properties
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
    // Shared types of the feature round (Place, InstalledSet; docs/architecture.md 1.1)
    api(project(":core:api"))
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)   // JSON tree parser only (no compiler plugin)
    implementation(libs.opening.hours.parser)         // OSM opening_hours (MIT; rule S10, batch B)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Machine-specific inputs (the pack databases) come from android/local.properties, never from
// committed files: beamline.searchDb and beamline.addressesDb. Environment variables
// BEAMLINE_SEARCH_DB / BEAMLINE_ADDRESSES_DB override them. Tests that need a database are
// skipped when it is not configured.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun setting(prop: String, env: String): String =
    System.getenv(env) ?: localProps.getProperty(prop) ?: ""
val repoRoot = rootProject.projectDir.parentFile
val searchDb = setting("beamline.searchDb", "BEAMLINE_SEARCH_DB")
val addressesDb = setting("beamline.addressesDb", "BEAMLINE_ADDRESSES_DB")

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    systemProperty("beamline.repoRoot", repoRoot.absolutePath)
    systemProperty("beamline.searchDb", searchDb)
    systemProperty("beamline.addressesDb", addressesDb)
    // conformance: reference results written by conformance/make_reference.py (in data/, not in git);
    // BEAMLINE_CONFORMANCE_DIR points the tests at another folder (conformance/negative_control.py)
    val confDir = System.getenv("BEAMLINE_CONFORMANCE_DIR") ?: File(repoRoot, "data/search-conformance").absolutePath
    systemProperty("beamline.conformanceDir", confDir)
    // the conformance and pass-rate tests write their findings here
    systemProperty("beamline.outDir", File(confDir, "kotlin").absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")   // the bundled driver loads its JNI library
    // Strict mode for CI and the final check (BEAMLINE_TESTS_STRICT=1 or -Pbeamline.strict=true):
    // a missing database, reference or rule source FAILS the tests instead of skipping them.
    val strict = System.getenv("BEAMLINE_TESTS_STRICT") == "1" ||
        providers.gradleProperty("beamline.strict").orNull == "true"
    systemProperty("beamline.strict", strict.toString())
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The JVM latency benchmark lives in :core:search-bench (./gradlew :core:search-bench:bench).
