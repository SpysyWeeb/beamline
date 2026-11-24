pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download the JDK named in gradle/gradle-daemon-jvm.properties when a machine
    // does not have it (Gradle's own daemon-JVM provisioning; nothing machine-specific is committed).
    // Version must match foojayResolver in gradle/libs.versions.toml (the catalog is not readable here).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "beamline"

include(":app")
include(":core:packs")
include(":core:settings")
include(":core:location")

// Routing track (r2b-routing): on-device routing with valhalla-mobile, route planner, guidance
// banner model, routing packs, and a demo app. See android/routing/README.md.
include(":routing:core")
include(":routing:banner")
include(":routing:packs")
include(":routing:valhalla")
include(":routing:demo")

// Track r2b-search (search on the device): the UI-free search module and its demo app.
include(":core:search")
include(":demo:search")
include(":core:search-bench")   // benchmark and device-conformance tooling (demo and JVM bench only)

// Feature round (docs/architecture.md, section 1): the frozen contract modules, the navigation
// session (with its route follower), the map layers, the feature modules and two device-check hosts.
include(":core:api")            // frozen shared kernel
include(":core:units")          // frozen unit formatter
include(":ui:theme")            // frozen theme and screen icons
include(":core:installed")      // track P
include(":nav:session")         // track N
include(":map:scene")           // track M
include(":map:layers")          // track M
include(":map:demo")            // track M, not shipped
include(":feature:search")      // track S
include(":feature:drive")       // track D
include(":feature:about")       // track A
include(":demo:about")          // track A, not shipped

// Track G: getting state packs in the app (docs/architecture-downloads.md).
include(":core:download")       // track G
include(":feature:states")      // track G
include(":demo:states")         // track G, not shipped
