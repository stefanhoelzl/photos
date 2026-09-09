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
    linuxX64()
    sourceSets {
        linuxX64Main.dependencies {
            // For the `photosimaging` cinterop: encoding is done by the pipeline's own encoders,
            // so a fixture cannot be wrong in a way the real thing is not.
            implementation(project(":adapter:linux"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
