plugins { alias(libs.plugins.kotlin.multiplatform) }

// Generated test inputs, shared by `:adapter:linux`'s own tests and by `:tests:cli`.
//
// Deliberately not a `testFixtures` source set inside `:adapter:linux`: Kotlin Multiplatform has
// no such thing for native targets, and the alternative -- making the writers public production
// code -- would ship fixture code inside the module that ships. The C side already draws that
// line, which is why `pi_fixture_*` lives behind its own header.
//
// The code sits in `linuxX64Main` rather than a test source set because a consumer's *test*
// source set can only see another module's *main*.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    linuxX64 {
        // The media set the app suites sync (FixtureMedia.kt). Debug only: it runs once per
        // build to write a handful of kilobyte-sized files, and a release link would cost more
        // than every run of it ever will.
        binaries.executable("fixtureMedia", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG)) {
            entryPoint = "net.stho.photos.fixtures.main"
        }
    }
    sourceSets {
        linuxX64Main.dependencies {
            // For the `photosimaging` cinterop: encoding is done by the pipeline's own encoders,
            // so a fixture cannot be wrong in a way the real thing is not.
            implementation(project(":adapter:linux"))
            implementation(libs.kotlinx.io.core)
        }
    }
}

/**
 * Writes the app suites' media into `build/fixture-media`.
 *
 * A directory rather than something each suite generates, because neither suite can: the JVM
 * cannot reach the C shim, and neither can a Mac. CI uploads this directory for the macOS job,
 * so both suites assert against the same bytes.
 */
val fixtureMediaDirectory = layout.buildDirectory.dir("fixture-media")
val fixtureMedia by tasks.registering(Exec::class) {
    group = "verification"
    description = "Writes the media set the app end-to-end suites sync."
    val link = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>("linkFixtureMediaDebugExecutableLinuxX64")
    dependsOn(link)
    inputs.files(link.map { it.outputFile })
    outputs.dir(fixtureMediaDirectory)
    doFirst { fixtureMediaDirectory.get().asFile.deleteRecursively() }
    executable = link.get().outputFile.get().absolutePath
    args(fixtureMediaDirectory.get().asFile.absolutePath)
}
