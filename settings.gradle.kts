pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}

plugins {
    // Provisions the JDK toolchain, so a checkout needs no particular JDK preinstalled.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Compose Multiplatform republishes the androidx artifacts it is built on, and those live
    // on Google's Maven rather than Central -- so a Compose target cannot resolve without it.
    repositories { mavenCentral(); google() }
}

rootProject.name = "photos"

// DESIGN §7's ports-and-adapters split. `:app:cli` is the composition root: the only place
// that knows which adapter satisfies which port, and the only place that constructs anything.
include(":domain")
include(":adapter:linux")
include(":app:cli")

// The app (DESIGN §6). `:ui` is one Compose UI plus the non-Compose state tier it renders --
// sibling packages in one module, the same rule `:domain` follows. `:app:desktop` and
// `:app:ios` are its two composition roots, and the only places that know which adapter
// satisfies which port.
//
// `:adapter:ios` is `:adapter:linux`'s counterpart and follows the same rule: value adapters
// only, and no Compose import anywhere. What the phone needs that a value cannot express --
// a video surface, a decoded image -- lives in `:app:ios`, which is already a UI module.
//
// `:app:domain` is the app's own shared tier, and the counterpart to `:domain`: that one is
// what the app shares with the CLI, this one is what the two apps share with each other.
include(":app:domain")
include(":ui")
include(":app:desktop")
include(":adapter:ios")

// Test-only modules. `:tests:fixtures` generates the synthetic media both the adapter's own
// tests and the end-to-end suite work from; `:tests:cli` drives the shipped binary.
include(":tests:fixtures")
include(":tests:cli")
// The app's end-to-end suite, `:tests:cli`'s counterpart (decision 22).
include(":tests:app")
