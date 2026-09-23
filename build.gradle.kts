plugins {
    // Declared, not applied: the root builds nothing. It is here so this file can name
    // KotlinNativeTest when wiring S3Mock into the module test tasks below.
    alias(libs.plugins.kotlin.multiplatform) apply false
    id("photos.native-toolchain")
}

// The native libraries' install directories, as an input of every linuxX64 link. A link takes
// them through `-L` and `-l` flags in a cinterop klib, which Gradle cannot see through: without
// this, a rebuilt ffmpeg left every link UP-TO-DATE and every binary holding the old library.
// Resolving the directories also makes each link wait for `:native` to have built them.
native.link("jpeg", "lcms2", "exif", "heif", "ffmpeg", "dbus", "sqlite")
val nativeLibraryDirs: FileCollection = native.libraries

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>()
        .matching { it.name.endsWith("LinuxX64") }
        .configureEach { inputs.files(nativeLibraryDirs).withPropertyName("nativeLibraries") }
}

// ---------------------------------------------------------------- S3Mock
//
// Round-trip tests need a real S3 server, and the build owns its lifecycle rather than the
// tests: a test is handed an endpoint and never has to know how to spawn a JVM.
//
// S3Mock rather than a real server: MinIO's
// community edition was archived, SeaweedFS's conditional PUT is absent or broken, and
// s3proxy answers 412 with a 500. `If-Match` is what guards §2's single-owner shard rule, so
// a server that cannot do it proves nothing. S3Mock does NOT validate signatures — accepted,
// because the vendored AWS vector suite is what proves the signer.

// Resolved like any other dependency -- verified, and cached once per machine. `exec` is the
// self-contained Spring Boot jar; nothing else is wanted from its graph.
val s3mock: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { add(s3mock.name, variantOf(libs.s3mock) { classifier("exec") }) }
val s3mockJar: FileCollection = s3mock

/**
 * Starts S3Mock for [task] and exports `PHOTOS_S3MOCK_ENDPOINT` to it.
 *
 * Without java the round-trip tests skip and everything else still runs: a machine that cannot
 * run a JVM should still be able to run the signer vectors and the offline tests.
 */
/**
 * The same server for a JVM test task.
 *
 * Kept apart from [configureS3Mock] because the endpoint reaches a Kotlin/Native test through
 * the process environment and a JVM one through a system property -- and because the cast in
 * the other function is to `KotlinNativeTest`, which a `Test` is not.
 */
fun configureS3MockJvm(task: Task) {
    task.dependsOn(s3mockJar)
    // A local, so the action captures the file collection rather than the script. It resolves
    // when the action runs, not while configuring.
    val jar = s3mockJar
    var process: Process? = null
    task.doFirst {
        val javaOk = runCatching {
            ProcessBuilder("java", "-version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
        if (!javaOk) {
            logger.lifecycle("S3Mock: java not found — app scenarios will skip")
            return@doFirst
        }
        val port = java.net.ServerSocket(0).use { it.localPort }
        val started = ProcessBuilder(
            "java", "-Dhttp.port=$port", "-DinitialBuckets=my-photos",
            "-Dserver.port=${java.net.ServerSocket(0).use { it.localPort }}",
            "-jar", jar.singleFile.path,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process = started
        Runtime.getRuntime().addShutdownHook(Thread { started.destroyForcibly() })
        val deadline = System.currentTimeMillis() + 60_000
        var url: String? = null
        while (System.currentTimeMillis() < deadline && started.isAlive) {
            if (runCatching { java.net.Socket("127.0.0.1", port).close(); true }.getOrDefault(false)) {
                url = "http://127.0.0.1:$port"
                break
            }
            Thread.sleep(200)
        }
        if (url == null) {
            started.destroyForcibly()
            process = null
            logger.lifecycle("S3Mock: did not become ready — app scenarios will skip")
            return@doFirst
        }
        logger.lifecycle("S3Mock listening on 127.0.0.1:$port, bucket 'my-photos'")
        (this as Test).systemProperty("photos.s3mock.endpoint", "$url/my-photos")
    }
    task.doLast { process?.destroy() }
}

fun configureS3Mock(task: Task) {
    task.dependsOn(s3mockJar)
    // A local, so the action captures the file collection rather than the script. It resolves
    // when the action runs, not while configuring.
    val jar = s3mockJar
    var process: Process? = null

    task.doFirst {
        val javaOk = runCatching {
            ProcessBuilder("java", "-version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
        if (!javaOk) {
            logger.lifecycle("S3Mock: java not found — round-trip tests will skip")
            return@doFirst
        }

        val port = java.net.ServerSocket(0).use { it.localPort }
        val bucket = "my-photos"
        val started = ProcessBuilder(
            "java",
            // `http.port` takes effect ONLY as a JVM system property. Passed as a Spring CLI
            // argument (`--http.port=`) it is silently ignored and HTTP stays on 9090 — which
            // looks exactly like a server that never started. Verified against S3Mock 4.11.0.
            "-Dhttp.port=$port",
            // S3Mock starts empty, so the bucket is declared up front rather than provisioned
            // by a test — the build owns the server's lifecycle, so it owns its initial state.
            // Unlike `http.port` above, a mis-spelling here is not silent: without the bucket
            // every request answers 404 NoSuchBucket, which says exactly what is wrong.
            "-DinitialBuckets=$bucket",
            // Keep the TLS connector off a port we might collide with.
            "-Dserver.port=${java.net.ServerSocket(0).use { it.localPort }}",
            "-jar", jar.singleFile.path,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process = started

        // `doLast` does NOT run when a task fails, and a failing test is exactly when a leaked
        // 58 MB JVM is least welcome — so the hook, not the doLast, is what actually guarantees
        // cleanup. It fires when the Gradle JVM exits, which under `--no-daemon` is build end.
        //
        // Known gap: with a long-lived daemon AND a failing test, S3Mock survives until the
        // daemon exits. A BuildService would close correctly in that case too, but it must live
        // in buildSrc — a class declared in a .gradle.kts is a non-static inner class Gradle
        // cannot instantiate — and buildSrc needs a JDK with javac, which this machine has not
        // got. Not worth provisioning one for a test harness; the port is random, so a stray
        // server collides with nothing.
        Runtime.getRuntime().addShutdownHook(Thread { started.destroyForcibly() })

        val deadline = System.currentTimeMillis() + 60_000
        var url: String? = null
        while (System.currentTimeMillis() < deadline && started.isAlive) {
            if (runCatching { java.net.Socket("127.0.0.1", port).close(); true }.getOrDefault(false)) {
                url = "http://127.0.0.1:$port"
                break
            }
            Thread.sleep(200)
        }
        if (url == null) {
            started.destroyForcibly()
            process = null
            logger.lifecycle("S3Mock: did not become ready — round-trip tests will skip")
            return@doFirst
        }
        logger.lifecycle("S3Mock listening on 127.0.0.1:$port, bucket '$bucket'")
        // Deliberately not a safe cast. KotlinNativeHostTest is NOT a ProcessForkOptions -- an
        // `as?` here would silently do nothing, the variable would never reach the test, and
        // the round-trip tests would skip forever while the build stayed green.
        val test = this as org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
        // The full storage URL, bucket included, so the name lives in one place.
        test.environment("PHOTOS_S3MOCK_ENDPOINT", "$url/$bucket")
    }

    task.doLast { process?.destroy() }
}

// Wired from the root rather than from domain/build.gradle.kts so that the module's build file
// stays about the module. If more consumers appear, this becomes a convention plugin.
project(":domain") {
    tasks.matching { it.name == "linuxX64Test" }.configureEach { configureS3Mock(this) }
}

// The end-to-end suite needs one too. Its test task is opt-in -- `:tests:cli:e2e` -- and an
// `onlyIf` skips the task and its actions together, so `build` never starts a second JVM.
// The app suite needs a zone too, and its test task is an ordinary JVM `Test`.
project(":tests:app") {
    tasks.matching { it.name == "jvmTest" }.configureEach { configureS3MockJvm(this) }
}

// The desktop viewer's suite syncs into a zone with the real CLI before the viewer reads it.
project(":tests:desktop") {
    tasks.matching { it.name == "jvmTest" }.configureEach { configureS3MockJvm(this) }
}

// The iOS suite reaches the same S3Mock from the simulator, which shares the Mac's loopback.
project(":tests:ios") {
    tasks.matching { it.name == "jvmTest" }.configureEach { configureS3MockJvm(this) }
}

project(":tests:cli") {
    tasks.matching { it.name == "linuxX64Test" }.configureEach { configureS3Mock(this) }
}

// ---------------------------------------------------------------- the simulator
//
// Kotlin/Native's simulator tests boot a device of their own for every test binary and shut it
// down afterwards (`simctl spawn --standalone`). CI's iOS job runs them on the device its app
// scenarios drive, already booted: `-Pphotos.simulator=<device name>` names that device, and the
// tests then run on it as it is. Unset, the plugin's own behaviour stands.
val sharedSimulator: String? = providers.gradleProperty("photos.simulator").orNull
if (sharedSimulator != null) subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        device.set(sharedSimulator)
        standalone.set(false)
    }
}
