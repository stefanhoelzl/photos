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

// DESIGN §7's ports-and-adapters split. Modules appear here as they gain content rather than
// as empty shells: `:adapter:generic`, `:adapter:fake` and `:app:cli` arrive with the domain.
include(":domain")
include(":adapter:linux")
include(":tools:smoke")
