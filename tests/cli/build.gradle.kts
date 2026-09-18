plugins { alias(libs.plugins.kotlin.multiplatform) }

// The end-to-end suite: declare a library tree and a zone, run the *shipped* `photos-cli`
// binary against a local S3, assert the library and the zone that result.
//
// It forks the release executable rather than calling `main` in-process, because `Main.kt` ends
// in `exitProcess` and because the point is to exercise the artifact that ships -- the same
// cinterop, the same static archives, the same link.
val cliBinary = project(":app:cli").layout.buildDirectory
    .file("bin/linuxX64/releaseExecutable/photos-cli.kexe")

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    linuxX64()
    sourceSets {
        linuxX64Test.dependencies {
            implementation(kotlin("test"))
            implementation(project(":domain"))
            implementation(project(":adapter:linux"))
            implementation(project(":tests:fixtures"))
            // The assertions read the zone with the same client the CLI writes it with, so a
            // mistake in how Ktor is used surfaces here too rather than only in production.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.curl)
            implementation(libs.kotlinx.io.core)
            implementation(libs.coroutines.core)
            implementation(libs.coroutines.test)
        }
    }
}

// Opt-in, not gated (interview decision 13). `build` compiles the scenarios -- they reference
// real domain types, so a rename in `:domain` breaks the build immediately rather than months
// later on first run -- but only `:tests:cli:e2e` runs them. `onlyIf` skips the test task
// itself; its dependencies, which are the compile and link, run regardless.
val e2e by tasks.registering {
    group = "verification"
    description = "Runs the shipped photos-cli against a local S3 and asserts the result."
    dependsOn(tasks.named("linuxX64Test"))
}

// Whether `e2e` is in this build, decided once the graph is known rather than asked of it while
// the test task runs: the configuration cache stores the answer, but cannot store `gradle`.
val e2eRequested = objects.property<Boolean>().convention(false)
gradle.taskGraph.whenReady { e2eRequested.set(hasTask(e2e.get())) }

tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>("linuxX64Test") {
    val requested = e2eRequested
    onlyIf { requested.get() }
    dependsOn(":app:cli:linkReleaseExecutableLinuxX64")
    environment("PHOTOS_CLI_BINARY", cliBinary.get().asFile.path)

    // Custom AssertionError subclasses carry the whole rendered state; without FULL, Gradle's
    // console prints only the exception's class name. The text reaches the XML report either
    // way, but a failure should be readable where it is read.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = false
    }
}
