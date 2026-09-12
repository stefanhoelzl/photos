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
    // The desktop app is Compose Multiplatform, whose desktop target is the JVM (§6). The
    // domain is platform-agnostic source -- no `platform.*` or cinterop import anywhere in
    // commonMain -- but a KMP module still has to declare what it compiles to.
    jvm()
    linuxX64 {
        compilations.getByName("main").defaultSourceSet.dependencies {
            implementation(libs.ktor.client.curl)
        }
        binaries.all {
            linkerOpts("-L${nativePrefix.resolve("lib")}", "-lsqlite3")
        }
    }
    // The phone (§6). Device and Apple-silicon simulator, and no `iosX64`: the build runner and
    // every iPhone this targets are arm64, so an Intel simulator slice would be a link nothing
    // here can run. Note the absence of any `linkerOpts` -- unlike linuxX64 above, iOS takes the
    // platform's own SQLite (§3), so there is nothing to point a linker at.
    iosArm64()
    iosSimulatorArm64()

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
            // No driver here any more. The expiry an earlier comment predicted arrived with
            // the `jvm` target above: `native-driver` is Kotlin/Native only, so `commonMain`
            // cannot name it. It became the `SqlDrivers` **port** rather than the
            // `expect`/`actual` that comment expected — one mechanism for a platform
            // difference across the whole repo, and one a test can substitute.
            // SQLDelight's own adapters for the primitives SQLite has no separate type for:
            // `INTEGER AS Int`, which the shard's dimensions and schema_version need.
            implementation(libs.sqldelight.primitive.adapters)
            // Uploads stream from a file and downloads stream to one, so the domain needs
            // files as well as bytes. kotlinx-io is what Ktor's own IO is built on, so this
            // adds a name, not a dependency.
            implementation(libs.kotlinx.io.core)
        }
        // NSURLSession, which is what an HTTP client on Apple platforms is -- and the only
        // engine that a background `URLSession` (§6's tier 5, still unbuilt) could ever extend.
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        // Each target's own driver, behind the port, lives with that target's adapter --
        // except in tests, which construct one directly.
        linuxX64Test.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
        iosTest.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.jdbc)
            implementation(libs.ktor.client.okhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// The vendored AWS SigV4 vectors are 382 files under `testdata/sigv4`. Kotlin/Native bundles
// no test resources, so the path arrives through the environment.
// The test fails loudly when it is missing rather than skipping: a subtle signing bug fails
// every request, and this suite is the only proof the signer is right (§7).
// Both targets, not just the native one: with a `jvm` target the same suite runs twice, which
// is how the signer is proven against whichever crypto provider each platform actually uses.
val sigv4Fixtures: String = rootDir.resolve("testdata/sigv4").absolutePath
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("PHOTOS_SIGV4_FIXTURES", sigv4Fixtures)
    // A simulator test is not a child process of Gradle: the binary is handed to `simctl
    // spawn`, which passes on only the variables named `SIMCTL_CHILD_*` and strips the prefix
    // as it does. So the line above reaches the *client* and the test sees nothing -- measured,
    // as six SigV4 vector failures on an otherwise green iosSimulatorArm64 run. Setting both
    // keeps one statement true of every native target rather than branching on the task type.
    environment("SIMCTL_CHILD_PHOTOS_SIGV4_FIXTURES", sigv4Fixtures)
}
tasks.withType<Test>().configureEach {
    environment("PHOTOS_SIGV4_FIXTURES", sigv4Fixtures)
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
