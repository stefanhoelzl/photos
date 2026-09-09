pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}

plugins {
    // Provisions the JDK toolchain, so a checkout needs no particular JDK preinstalled.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "photos"

// DESIGN §7's ports-and-adapters split. `:app:cli` is the composition root: the only place
// that knows which adapter satisfies which port, and the only place that constructs anything.
include(":domain")
include(":adapter:linux")
include(":app:cli")

// Test-only modules. `:tests:fixtures` generates the synthetic media both the adapter's own
// tests and the end-to-end suite work from; `:tests:cli` drives the shipped binary.
include(":tests:fixtures")
include(":tests:cli")
