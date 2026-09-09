plugins { alias(libs.plugins.kotlin.multiplatform) }

// DESIGN §7: everything a laptop/phone disagreement would corrupt. One flat module -- the
// internal boundaries are package conventions, not module edges.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    linuxX64 {
        compilations.getByName("main").defaultSourceSet.dependencies {
            implementation(libs.ktor.client.curl)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: the pipeline port publishes its progress as a
            // `SharedFlow`, so `Flow` is part of the domain's own surface.
            api(libs.coroutines.core)
            implementation(libs.kotlinx.datetime)
            // Storage: Ktor is both the platform abstraction and the test seam (DESIGN §7),
            // so there is no HttpTransport port. SigV4 needs SHA-256 and HMAC and nothing
            // else — an AWS SDK would bring a credential chain and a retry policy this
            // design has already decided differently about.
            implementation(libs.ktor.client.core)
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.kotlincrypto.hmac.sha2)
            implementation(libs.xmlutil.core)
            // Uploads stream from a file and downloads stream to one, so the domain needs
            // files as well as bytes. kotlinx-io is what Ktor's own IO is built on, so this
            // adds a name, not a dependency.
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// The vendored AWS SigV4 vectors are 382 files that stay where they are, beside the Swift
// tests. Kotlin/Native bundles no test resources, so the path arrives through the environment.
// The test fails loudly when it is missing rather than skipping: a subtle signing bug fails
// every request, and this suite is the only proof the signer is right (§7).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment(
        "PHOTOS_SIGV4_FIXTURES",
        rootDir.resolve("Tests/PhotosStorageTests/Fixtures/sigv4").absolutePath,
    )
}
