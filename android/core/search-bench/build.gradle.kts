// Benchmark and device-conformance tooling of :core:search (SearchBench, ConformanceDump,
// MathParity, the JVM BenchMain). Kept out of :core:search so the app module that depends on the
// search module does not carry it; only the demo app (:demo:search) and the JVM bench use it.
//   ./gradlew :core:search-bench:bench   (writes data/search-conformance/bench-jvm.json)
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
    implementation(project(":core:search"))
    implementation(libs.kotlinx.serialization.json)
}

// The pack databases come from android/local.properties (beamline.searchDb, beamline.addressesDb)
// or the environment (BEAMLINE_SEARCH_DB, BEAMLINE_ADDRESSES_DB), as for :core:search's tests.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun setting(prop: String, env: String): String =
    System.getenv(env) ?: localProps.getProperty(prop) ?: ""
val repoRoot = rootProject.projectDir.parentFile

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Search latency, open time and memory on the JVM (writes data/search-conformance/bench-jvm.json)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.spysyweeb.beamline.search.bench.BenchMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")   // the bundled driver loads its JNI library
    maxHeapSize = "1g"
    // BEAMLINE_BENCH_OUT writes elsewhere (repeated runs of the review re-measurement)
    val out = System.getenv("BEAMLINE_BENCH_OUT") ?: File(repoRoot, "data/search-conformance/bench-jvm.json").absolutePath
    args = listOf(
        setting("beamline.searchDb", "BEAMLINE_SEARCH_DB"), setting("beamline.addressesDb", "BEAMLINE_ADDRESSES_DB"),
        rootProject.file("core/search/conformance/search_queries.json").absolutePath, out,
    )
}
