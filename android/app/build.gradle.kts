import java.io.StringReader
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The app's identity (application id, launcher label), kept in gradle.properties only.
val appId = providers.gradleProperty("beamline.applicationId").get()
val appName = providers.gradleProperty("beamline.appName").get()
val javaTarget = JavaVersion.toVersion(libs.versions.java.get())
// MapLibre renderer: "opengl" (org.maplibre.gl:android-sdk-opengl, gradle.properties, the owner's choice of
// 2026-11-16) or "vulkan" (android-sdk; for a measurement only: it links glslang, which the licence gate refuses).
val mapRenderer = providers.gradleProperty("beamline.mapRenderer").getOrElse("opengl")
require(mapRenderer == "vulkan" || mapRenderer == "opengl") { "beamline.mapRenderer must be vulkan or opengl" }

// The whole-US overview map is not in git: android/scripts/sync_assets.py copies it into
// app/assets-local/basemap/. Without it the app still works but shows no overview map; a build
// without it must say so on purpose: -Pbeamline.allowNoOverview=true
val overviewFile = layout.projectDirectory.file("assets-local/basemap/us-base.pmtiles").asFile
val allowNoOverview = providers.gradleProperty("beamline.allowNoOverview").getOrElse("false").toBoolean()
require(overviewFile.isFile || allowNoOverview) {
    "No overview map in app/assets-local/basemap/. Run `python android/scripts/sync_assets.py basemap` from the " +
        "repo root, or build with -Pbeamline.allowNoOverview=true to leave it out."
}
// The test intent extras (camera position, screen; see MainActivity.readTestExtras) are read by
// debug builds always, and by a release build only when asked for, for the measurements:
// ./gradlew assembleRelease -Pbeamline.testExtras=true
val releaseTestExtras = providers.gradleProperty("beamline.testExtras").getOrElse("false").toBoolean()
// Batch O2 (docs/online-design.md 4.4): Beamline's OAuth 2 registrations, public clients with PKCE (no
// secret): on openstreetmap.org under the owner's account, and on OSM's test server for development builds.
// Redirect io.github.spysyweeb.beamline://oauth, scopes read_prefs write_api write_notes.
val osmLiveClientId = "3usES0gtJKfry00nwOP5_W-E5JM8maeYxDUHaNszCVU"
val osmTestClientId = "kboFIWlThtxHpIJhBrXal-6iNmQVNTXoB6BmZZi0Mvc"

// Pack downloads (docs/architecture-downloads.md, section 8). The packs repository is public since
// 2026-11-22, so a release reads its catalog unless an https beamline.catalogUrl (android/local.properties,
// git-ignored, or a -P property) names another; the owner, 2026-11-24: the phone gets the weekly packs.
// Debug builds read only the configured address (the PC's test server for the emulator, 10.0.2.2).
val publicCatalogUrl = "https://github.com/SpysyWeeb/beamline-packs/releases/download/catalog/catalog.json"
val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { load(StringReader(it)) }
}
// The owner's release key (a PKCS12 file outside git): all three or none, and the file must exist.
val releaseKeystore: File? = localProperties.getProperty("beamline.releaseKeystore")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
val releaseKeyAlias: String? = localProperties.getProperty("beamline.releaseKeyAlias")?.trim()
val releaseKeyPassword: String? = localProperties.getProperty("beamline.releaseKeyPassword")
require(releaseKeystore == null || (releaseKeystore.isFile && !releaseKeyAlias.isNullOrEmpty() && !releaseKeyPassword.isNullOrEmpty())) {
    "beamline.releaseKeystore is set in local.properties: the file must exist, and beamline.releaseKeyAlias and beamline.releaseKeyPassword must be set too"
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
    namespace = "io.github.spysyweeb.beamline.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.android.buildTools.get()

    defaultConfig {
        applicationId = appId
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 28
        versionName = "0.1.25"
        manifestPlaceholders["appName"] = appName
        buildConfigField("String", "MAP_RENDERER", "\"$mapRenderer\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            // assets-local/: inputs copied in by scripts/sync_assets.py and not committed (overview map).
            assets.directories.add("assets-local")
        }
    }

    androidResources {
        // MapLibre reads PMTiles from assets by byte range (AAsset_seek64). A compressed asset would
        // have to be inflated from its start for every read; tiles inside are gzipped already.
        noCompress += "pmtiles"
    }

    packaging {
        resources {
            // kotlinx-coroutines' debug-agent class file, used only by IDE debuggers.
            excludes += "DebugProbesKt.bin"
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("owner") {
                storeFile = releaseKeystore
                storeType = "PKCS12"
                storePassword = releaseKeyPassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "TEST_EXTRAS", "true")
            buildConfigField("String", "CATALOG_URL", "\"$catalogUrl\"")
            // Online features (docs/online-design.md 4.6): development builds talk only to OSM's test server.
            buildConfigField("String", "OSM_SERVER", "\"test\"")
            buildConfigField("String", "OSM_CLIENT_ID", "\"$osmTestClientId\"")
        }
        release {
            buildConfigField("boolean", "TEST_EXTRAS", releaseTestExtras.toString())
            // An http address never reaches a release build: it reads the public catalog instead.
            buildConfigField("String", "CATALOG_URL", "\"${if (catalogUri?.scheme == "https") catalogUrl else publicCatalogUrl}\"")
            // openstreetmap.org only in the plain release; a release built with test extras is a test build (4.6).
            buildConfigField("String", "OSM_SERVER", "\"${if (releaseTestExtras) "test" else "live"}\"")
            // Batch O2 (4.4): the app's public OAuth 2 client id on that server (no secret is kept in the app).
            buildConfigField("String", "OSM_CLIENT_ID", "\"${if (releaseTestExtras) osmTestClientId else osmLiveClientId}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The plain release is signed with the owner's key when android/local.properties names it
            // (beamline.releaseKeystore, beamline.releaseKeyAlias, beamline.releaseKeyPassword; the key
            // file and that file are never committed). Without them, and for a release built with test
            // extras (emulator measurements), the debug key signs: such a build is for testing only.
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null && !releaseTestExtras) "owner" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = javaTarget
        targetCompatibility = javaTarget
        // No core library desugaring: it served Ferrostar alone, dropped 2026-11-17 (decision 6.34).
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // ComposedStyleOfflineTest reads the app's real style assets.
            it.systemProperty("beamline.appDir", layout.projectDirectory.asFile.absolutePath)
        }
    }
}

dependencies {
    // Every module of docs/architecture.md 1.1 except the demos (5.4 item 1).
    implementation(project(":core:api"))
    implementation(project(":core:units"))
    implementation(project(":core:settings"))
    implementation(project(":core:location"))
    implementation(project(":ui:theme"))   // the theme and screen icons (moved out of :app, docs/architecture.md)
    implementation(project(":core:packs"))
    implementation(project(":core:installed"))
    implementation(project(":routing:core"))
    implementation(project(":routing:banner"))
    implementation(project(":routing:packs"))
    implementation(project(":routing:valhalla"))
    implementation(project(":nav:session"))
    implementation(project(":core:search"))
    implementation(project(":feature:search"))
    implementation(project(":feature:drive"))
    implementation(project(":map:scene"))
    implementation(project(":map:layers"))
    implementation(project(":feature:about"))
    implementation(project(":core:download"))
    implementation(project(":feature:states"))
    // OfflineMapHttp: the HTTP client MapLibre gets, which refuses every request (rule N5).
    implementation(libs.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(if (mapRenderer == "opengl") libs.maplibre.android.opengl else libs.maplibre.android.vulkan)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
