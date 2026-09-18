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
            // Kotlin/Native links without --as-needed, so the binary records a DT_NEEDED for
            // every library on its default link line whether or not a symbol is taken from it.
            // Four were spurious -- libcrypt, libresolv, libutil, librt, none contributing a
            // single undefined symbol -- and libcrypt is not a harmless entry: glibc moved crypt
            // to libxcrypt, so Fedora ships libcrypt.so.2 and has libcrypt.so.1 only when
            // libxcrypt-compat is installed. The binary refused to start there.
            //
            // `linkerOpts` reaches ld.lld directly, so no `-Wl,` prefix (DESIGN §7).
            linkerOpts("--as-needed")
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

// ---------------------------------------------------------------- packaging
//
// What ships is not quite what the linker emits: `photos-cli` rather than `photos-cli.kexe`,
// and stripped. §7 advertises **26.7 MiB stripped** and nothing in the build had ever done the
// stripping -- it was a measurement taken by hand during the spike. This is that step, and CI
// uploads its output from every green run.
//
// Standalone, asked for by name, the way `:tests:cli:e2e` is: `build` and the local `.ship`
// gate answer to how fast an inner loop can stay, and neither has any use for a packaged
// binary.
val konanToolchain: File? = rootProject.extra["konanToolchain"] as File?
val releaseBinary = layout.buildDirectory.file("bin/linuxX64/releaseExecutable/photos-cli.kexe")
val distBinary = layout.buildDirectory.file("dist/photos-cli")

val dist by tasks.registering {
    group = "distribution"
    description = "Strips the release binary to build/dist/photos-cli, the artifact CI uploads."
    dependsOn("linkReleaseExecutableLinuxX64")
    inputs.file(releaseBinary)
    outputs.file(distBinary)
    // Locals, so the action does not capture the script object (configuration cache).
    val konanToolchain = konanToolchain
    val releaseBinary = releaseBinary
    val distBinary = distBinary
    val providers = providers
    doLast {
        // konan's binutils rather than the host's -- the same toolchain that linked the binary
        // and that Scripts/build-native.sh builds the imaging prefix with, so a checkout that
        // can build at all can strip, with nothing asked of the host.
        val tc = requireNotNull(konanToolchain) {
            "konan gcc toolchain not found -- link a linuxX64 binary once to fetch it"
        }
        val strip = tc.resolve("bin/x86_64-unknown-linux-gnu-strip").absolutePath
        val out = distBinary.get().asFile
        out.parentFile.mkdirs()
        // `-o` rather than stripping in place: `:tests:cli` forks the `.kexe`, and a failing
        // scenario should still print Kotlin frames rather than addresses.
        providers.exec {
            commandLine(strip, "-o", out.absolutePath, releaseBinary.get().asFile.absolutePath)
        }.standardOutput.asText.get()
        logger.lifecycle("photos-cli: %.1f MiB stripped".format(out.length() / 1048576.0))
    }
}
