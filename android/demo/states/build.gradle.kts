// States demo: the host app for track G's device checks (first-run picker, States screen,
// DownloadService, notifications, the network rule on the device; docs/architecture-downloads.md
// 10.3), with a fake InstalledData. Not shipped; its own application id, so it installs beside the
// real app. Placeholder from Round G0: track G writes its activity.
import java.io.StringReader
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val javaTarget = JavaVersion.toVersion(libs.versions.java.get())

// Pack downloads (docs/architecture-downloads.md, section 8), as in :app. While the project is
// private the address lives only in android/local.properties (git-ignored) or a -P property.
val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { load(StringReader(it)) }
}
val catalogUrl: String = (localProperties.getProperty("beamline.catalogUrl")
    ?: providers.gradleProperty("beamline.catalogUrl").orNull ?: "").trim()
val catalogUri: URI? = catalogUrl.takeIf { it.isNotEmpty() }?.let { URI(it) }
val privateIpv4 = Regex("""^(10\.\d+|172\.(1[6-9]|2\d|3[01])|192\.168|127\.\d+)\.\d+\.\d+$""")
require(catalogUri == null || catalogUri.scheme == "https" ||
    (catalogUri.scheme == "http" && (catalogUri.host == "localhost" || privateIpv4.matches(catalogUri.host)))) {
    "beamline.catalogUrl must be https, or http to localhost or a private IPv4 address"
}
// Debug builds: cleartext only to the emulator host, loopback and the configured private address.
abstract class BeamlineNetworkConfig : DefaultTask() {
    @get:Input abstract val hosts: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun write() {
        val f = outputDir.get().file("xml/beamline_network.xml").asFile
        f.parentFile.mkdirs()
        f.writeText(buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<network-security-config>\n")
            append("    <domain-config cleartextTrafficPermitted=\"true\">\n")
            hosts.get().forEach { append("        <domain includeSubdomains=\"false\">$it</domain>\n") }
            append("    </domain-config>\n</network-security-config>\n")
        })
    }
}
val debugNetworkConfig = tasks.register<BeamlineNetworkConfig>("beamlineDebugNetworkConfig") {
    hosts.set((listOf("10.0.2.2", "127.0.0.1", "localhost") +
        listOfNotNull(catalogUri?.takeIf { it.scheme == "http" }?.host)).distinct())
    outputDir.set(layout.buildDirectory.dir("generated/beamline/debugNetworkRes"))
}
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(debugNetworkConfig, BeamlineNetworkConfig::outputDir)
    }
}

android {
    namespace = "io.github.spysyweeb.beamline.states.demo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        applicationId = "io.github.spysyweeb.beamline.statesdemo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "CATALOG_URL", "\"$catalogUrl\"")
        }
        release {
            // An http address never reaches a release build.
            buildConfigField("String", "CATALOG_URL", "\"${if (catalogUri?.scheme == "https") catalogUrl else ""}\"")
        }
    }

    compileOptions {
        sourceCompatibility = javaTarget
        targetCompatibility = javaTarget
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":feature:states"))
    implementation(project(":ui:theme"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}
