pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
    // The task types and plugins behind `:native` and the projects that link it.
    includeBuild("build-logic")
}

plugins {
    // Provisions the JDK toolchain, so a checkout needs no particular JDK preinstalled.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        // Compose Multiplatform republishes the androidx artifacts it is built on, and those
        // live on Google's Maven rather than Central -- so a Compose target cannot resolve
        // without it.
        mavenCentral()
        google()

        // The native build's inputs: source tarballs, two build tools and Kotlin/Native's gcc
        // toolchain, each fetched from where its upstream publishes it. Resolved as ordinary
        // dependencies, so each is downloaded once per machine rather than once per worktree,
        // and checked against gradle/verification-metadata.xml like everything else. Versions
        // are in gradle/libs.versions.toml; the group is the key that routes a tarball here,
        // and nothing else is ever looked up in these repositories.
        //
        // `[classifier]` is a spare slot for URLs that carry more than the version: expat's
        // release tag, sqlite's year. `:native` fills it.
        fun tarballs(group: String, base: String, pattern: String) = exclusiveContent {
            forRepository {
                ivy {
                    name = group
                    url = uri(base)
                    patternLayout { artifact(pattern) }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup(group) }
        }
        val github = "https://github.com"
        tarballs("native.toolchain", "https://download.jetbrains.com/kotlin/native", "[module]-[revision].[ext]")
        tarballs("native.cmake", github, "Kitware/CMake/releases/download/v[revision]/[module]-[revision]-[classifier].[ext]")
        tarballs("native.nasm", "https://www.nasm.us/pub/nasm/releasebuilds", "[revision]/[module]-[revision].[ext]")
        tarballs("native.libjpeg-turbo", github, "libjpeg-turbo/libjpeg-turbo/releases/download/[revision]/[module]-[revision].[ext]")
        tarballs("native.lcms2", github, "mm2/Little-CMS/releases/download/lcms[revision]/[module]-[revision].[ext]")
        tarballs("native.libexif", github, "libexif/libexif/releases/download/v[revision]/[module]-[revision].[ext]")
        tarballs("native.libde265", github, "strukturag/libde265/releases/download/v[revision]/[module]-[revision].[ext]")
        // VideoLAN's mirror rather than Bitbucket's downloads page, which redirects to a
        // presigned S3 URL that answers Gradle's HEAD with 403. The tarball is byte-identical.
        tarballs("native.x265", "https://download.videolan.org/pub/videolan/x265", "[module]_[revision].[ext]")
        tarballs("native.libheif", github, "strukturag/libheif/releases/download/v[revision]/[module]-[revision].[ext]")
        tarballs("native.ffmpeg", "https://ffmpeg.org/releases", "[module]-[revision].[ext]")
        tarballs("native.expat", github, "libexpat/libexpat/releases/download/[classifier]/[module]-[revision].[ext]")
        tarballs("native.dbus", "https://dbus.freedesktop.org/releases/dbus", "[module]-[revision].[ext]")
        tarballs("native.sqlite", "https://sqlite.org", "[classifier]/[module]-[revision].[ext]")
        // GitHub's tag archive: the file is named for the tag alone, not the project.
        tarballs("native.opencv", github, "opencv/opencv/archive/refs/tags/[revision].[ext]")

        // Test data for the face adapter (DESIGN §12), fetched and verified like the tarballs: the
        // models by opencv_zoo commit, and public-domain portraits from NASA's own image library,
        // by photo number. (Not Wikimedia Commons, which answers Gradle's downloads with 429.)
        tarballs("testdata.opencv-zoo", "https://media.githubusercontent.com/media/opencv/opencv_zoo", "[revision]/models/[classifier]/[module].[ext]")
        tarballs("testdata.nasa", "https://images-assets.nasa.gov/image", "[module]/[module]~orig.[ext]")
    }
}

rootProject.name = "photos"

// DESIGN §7's ports-and-adapters split. `:app:cli` is the composition root: the only place
// that knows which adapter satisfies which port, and the only place that constructs anything.
include(":domain")
// The native libraries `:adapter:linux` and `:domain` link, built from pinned sources with
// Kotlin/Native's own toolchain -- one task and one published variant per library.
include(":native")
include(":adapter:linux")
include(":app:cli")

// The apps (DESIGN §6, §11). Three composition roots, and the modules they share.
//
// `:app:domain` is the apps' own shared tier, and the counterpart to `:domain`: that one is
// what the apps share with the CLI, this one is what they share with each other -- the ports,
// the model, §6's download scheduler and §4's on-device cache.
//
// The UI is three modules, because there are two UIs (§6). `:ui:shared` is the components both
// draw -- compiled for Linux and the phone -- `:ui:phone` the phone's screens, and `:ui:desktop`
// §11's viewer, JVM only. Composables and nothing else, in all three.
//
// `:app:harness` and `:app:ios` are the phone's two roots -- the phone's UI drawn on Linux, and
// the phone -- and `:app:desktop` is the desktop viewer's. Only the roots know which adapter
// satisfies which port. `:adapter:ios` is `:adapter:linux`'s counterpart and follows the same
// rule: value adapters only, and no Compose import anywhere. What the phone needs that a value
// cannot express -- a video surface, a decoded image -- lives in `:app:ios`, which is already a
// UI module.
// `:app:control` is the server the roots start when they are being driven by a test or an
// agent. A module of its own so that the release boundary is an edge a build can check: the iOS
// Release framework does not link it, so a TestFlight binary carries no listener at all.
include(":app:domain")
include(":app:control")
include(":ui:shared")
include(":ui:phone")
include(":ui:desktop")
// The basemap, and nothing else: MapLibre behind `:ui:shared`'s `BaseMap` port. A module of its
// own so that the UI modules -- and every headless render of them -- link no native map renderer.
include(":app:map")
// Media on Linux, for both Linux roots: libvlc behind `:ui:shared`'s `VideoSurface` port, and the
// decode shim's pixels as Compose images.
include(":app:media")
include(":app:harness")
include(":app:desktop")
include(":adapter:ios")
include(":app:ios")
include(":app:ios-debug")

// Test-only modules. `:tests:fixtures` generates the synthetic media both the adapter's own
// tests and the end-to-end suite work from; `:tests:cli` drives the shipped binary.
include(":tests:fixtures")
include(":tests:cli")
// The app's end-to-end suite, `:tests:cli`'s counterpart (decision 22).
include(":tests:app")
// The desktop viewer's (§11): the real `photos-cli sync` into a library, then the viewer over it.
include(":tests:desktop")
// The zone builder `:tests:app` and the iOS suite share, kept free of anything Linux-only.
include(":tests:zone")
// The iOS suite: `:tests:app`'s scenarios, run on a Mac against the signed Debug app on a
// simulator, driven through its control server. Opt-in -- `:tests:ios:e2e` -- because it fails,
// rather than skips, anywhere without a simulator.
include(":tests:ios")
