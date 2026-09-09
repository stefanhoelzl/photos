plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
}

// The prefix's SQLite, not the distro's. `Scripts/PROVENANCE.md` records why: a distro's
// libsqlite3.so is built against that distro's glibc, and linking it would raise the shipped
// binary's floor to whatever the build host happened to have. SQLDelight's native driver
// (SQLiter) deliberately ships no `-lsqlite3` of its own, so the consumer supplies it — which
// is exactly what lets us supply ours.
val nativePrefix: File = rootProject.extra["nativePrefix"] as File

// DESIGN §7: everything a laptop/phone disagreement would corrupt. One flat module -- the
// internal boundaries are package conventions, not module edges.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    // `kotlin.uuid.Uuid` is still opt-in, and SQLDelight's generated code cannot carry an
    // annotation of its own -- a `TEXT AS Uuid` column puts the type in a generated signature.
    // Every key in the zone is a UUID (§2), so the alternative is a stringly-typed schema.
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
    linuxX64 {
        compilations.getByName("main").defaultSourceSet.dependencies {
            implementation(libs.ktor.client.curl)
        }
        binaries.all {
            linkerOpts("-L${nativePrefix.resolve("lib")}", "-lsqlite3")
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
            api(libs.sqldelight.runtime)
            // The native driver sits in `commonMain`, not under `linuxX64`, because it is the
            // driver for *every* target this project will ever have -- Kotlin/Native is the
            // whole platform set (§7), and SQLiter ships iOS variants as well. Keeping it here
            // is what lets the catalog open a database in shared code instead of behind an
            // `expect fun` that would have exactly one `actual`.
            //
            // This has a known expiry. `native-driver` is Kotlin/Native only, and the app needs
            // a **JVM desktop target** for the Compose harness (§6) — which is most of why this
            // project is Multiplatform at all. The day that target is added, `commonMain` can no
            // longer name `NativeSqliteDriver`, and `Path.openDriver` becomes `expect`/`actual`
            // with a JDBC driver beside the native one. Mechanical, but it will not announce
            // itself until the target appears.
            implementation(libs.sqldelight.driver.native)
            // SQLDelight's own adapters for the primitives SQLite has no separate type for:
            // `INTEGER AS Int`, which the shard's dimensions and schema_version need.
            implementation(libs.sqldelight.primitive.adapters)
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
        rootDir.resolve("testdata/sigv4").absolutePath,
    )
}

// Four databases because there are four schemas (§3): the per-album shard and its thumbnail
// pack, both of which are objects in the zone, and the merged database and sync state, which
// are per-device and never uploaded.
//
// The dialect is pinned to **SQLite 3.24** — the floor DESIGN §3 claims and the oldest release
// that has `ON CONFLICT … DO UPDATE`, the newest syntax anything here uses. Pinning it is what
// turns that claim into a build failure rather than a promise: SQL needing anything newer stops
// compiling here instead of failing on whichever device has the older library.
sqldelight {
    databases {
        create("ShardDatabase") {
            packageName.set("net.stho.photos.catalog.shard")
            srcDirs.setFrom("src/commonMain/sqldelight/shard")
            dialect(libs.sqldelight.dialect.sqlite324)
        }
        create("ThumbPackDatabase") {
            packageName.set("net.stho.photos.catalog.thumb")
            srcDirs.setFrom("src/commonMain/sqldelight/thumb")
            dialect(libs.sqldelight.dialect.sqlite324)
        }
        create("MergedDatabase") {
            packageName.set("net.stho.photos.catalog.merged")
            srcDirs.setFrom("src/commonMain/sqldelight/merged")
            dialect(libs.sqldelight.dialect.sqlite324)
        }
        create("SyncStateDatabase") {
            packageName.set("net.stho.photos.catalog.syncstate")
            srcDirs.setFrom("src/commonMain/sqldelight/syncstate")
            dialect(libs.sqldelight.dialect.sqlite324)
        }
    }
}
