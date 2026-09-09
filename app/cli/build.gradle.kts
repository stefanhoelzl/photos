plugins { alias(libs.plugins.kotlin.multiplatform) }

// The shipped binary. DESIGN §7: argument parsing and wiring over the domain, and no judgement
// about the library. Everything that decides what the zone should contain lives in `:domain`;
// this module's whole job is to choose an adapter for each port and hand them over.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    linuxX64 {
        binaries.executable {
            entryPoint = "net.stho.photos.cli.main"
            baseName = "photos-cli"
        }
    }
    sourceSets {
        linuxX64Main.dependencies {
            implementation(project(":domain"))
            implementation(project(":adapter:linux"))
            implementation(libs.clikt)
            // Ktor is the transport abstraction rather than a port (DESIGN §7), so the
            // composition root is what picks the engine and installs the retry policy —
            // `HttpClient` and `HttpClientConfig` are named here, not just curl.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.curl)
            // `IngestConfig` speaks in `kotlinx.io.files.Path`, and the domain keeps kotlinx-io
            // as an `implementation` dependency, so the caller has to name it itself.
            implementation(libs.kotlinx.io.core)
        }
        linuxX64Test.dependencies { implementation(kotlin("test")) }
    }
}
