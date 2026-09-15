package net.stho.photos.iostest

import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.asStorageUrl
import net.stho.photos.zone.Zone

/**
 * One iOS scenario: declare a zone, install and launch the Debug app, set it up, assert.
 *
 * `:tests:app`'s `scenario` for the phone, and the same shape — with the app out of process, so
 * everything is asked for over the control server and read back from `/state` or the container.
 *
 * **Every precondition fails the scenario.** No macOS, no `xcrun`, no built app, no fixture media,
 * no S3Mock: each is an error naming what to do, never a skip. A suite that passes where it could
 * not run is how an iOS regression gets through with every check green.
 */
internal fun iosScenario(label: String, body: suspend IosScenario.() -> Unit) {
    check(System.getProperty("os.name").startsWith("Mac")) {
        "the iOS suite needs macOS (a simulator); this is ${System.getProperty("os.name")}"
    }
    val app = File(required("photos.ios.app", "the build task passes it"))
    check(app.isDirectory) { "no app bundle at $app -- :tests:ios:iosApp builds it with Scripts/ios-sim.sh build" }
    val media = File(required("photos.fixture.media", "pass -Pphotos.fixtureMedia=<dir>"))
    check(media.isDirectory && (media.list()?.size ?: 0) >= 6) {
        "no fixture media in $media -- it is written on Linux by :tests:fixtures:fixtureMedia and handed over"
    }
    val endpoint = required("photos.s3mock.endpoint", "S3Mock did not start; the root build starts it with java")

    val scratch = File(required("photos.test.scratch", "the build task passes it"), label).apply {
        deleteRecursively()
        mkdirs()
    }
    val simulator = Simulator(required("photos.ios.device", "the build task passes it"))
    runBlocking {
        val s3 = S3Client(
            storage = endpoint.asStorageUrl(),
            secretAccessKey = PASSWORD,
            http = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp),
            payloadSigning = S3Client.PayloadSigning.SIGNED,
        )
        val zone = Zone(s3, Path(File(scratch, "staging").absolutePath))
        zone.clear()
        IosScenario(zone, endpoint, simulator, app, scratch, media).use { it.body() }
    }
}

private fun required(property: String, remedy: String): String =
    System.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: error("$property is unset -- $remedy")

internal const val PASSWORD: String = "test-secret"

internal class IosScenario(
    val zone: Zone,
    private val endpoint: String,
    private val simulator: Simulator,
    private val app: File,
    private val scratch: File,
    private val media: File,
) : AutoCloseable {

    /**
     * Full photo-library access for the installed app, as a person tapping Allow would give it
     * (§8). Granted after [install], which is what resets it.
     */
    fun allowPhotos() {
        // The UI test runner reads and writes the library too, when it seeds a Live Photo.
        simulator.grantPhotos(BUNDLE_ID, UI_TEST_RUNNER)
    }

    /**
     * Fixture media into the simulator's own photo library — the phone's gallery for an upload
     * scenario. The library is the device's, not the app's, so a reinstall does not empty it.
     */
    fun addToLibrary(vararg fixtures: String) = simulator.addMedia(fixtures.map { File(media, it) })

    /** A fixture file, for a UI test that reads it from the host. */
    fun fixture(name: String): File = File(media, name)

    private val uiTests = mutableListOf<UiTest>()

    /**
     * One test from `PhotosUITests`, started beside the scenario — the suite's hands for what a
     * scenario over HTTP cannot do: tap a system alert, seed the library through PhotoKit.
     *
     * Started with `xcodebuild test-without-building`, which installs the app it tests over the one
     * [install] put there, so a UI test is started *before* [launch]. [environment] reaches the test
     * as `TEST_RUNNER_*`, the one prefix xcodebuild passes on. Returns once [ready] has been printed,
     * or straight away without one.
     */
    fun startUiTest(test: String, environment: Map<String, String> = emptyMap(), ready: String? = null): UiTest {
        val products = requireNotNull(app.parentFile?.parentFile) { "no products directory above $app" }
        val xctestrun = products.listFiles { file -> file.name.endsWith(".xctestrun") }?.singleOrNull()
            ?: error("no .xctestrun in $products -- Scripts/ios-sim.sh build builds the app for testing")
        val log = File(scratch, "${test.replace('/', '-')}.log")
        return UiTest.start(xctestrun, simulator.device, "PhotosUITests/$test", environment, log)
            .also { uiTests += it }
            .also { test -> ready?.let(test::awaitLine) }
    }

    /**
     * A fresh port for every launch, not one per scenario. A terminated app can still hold its
     * listening socket for a moment, and a relaunch onto the same port then fails to bind and
     * answers nothing -- measured, as the setup scenario's relaunch timing out while every
     * single-launch scenario passed.
     */
    private var control = Control("http://127.0.0.1:0")

    /**
     * A clean install, launched with its control port, answering `/state`.
     *
     * Reinstalling empties the container, and resetting the keychain removes credentials a
     * previous scenario stored — iOS keeps Keychain items across an uninstall, so without the
     * reset the next scenario would start already set up.
     */
    fun install() {
        simulator.boot()
        simulator.terminate()
        simulator.uninstall()
        simulator.resetKeychain()
        simulator.install(app)
    }

    /** Launch (or relaunch) and wait until the control server answers. */
    suspend fun launch(): JsonObject {
        simulator.terminate()
        val port = ServerSocket(0).use { it.localPort }
        control = Control("http://127.0.0.1:$port")
        simulator.launch(port)
        return awaitState("the control server on port $port") { true }
    }

    /** §1's setup through the real Keychain, against this scenario's S3Mock zone. */
    suspend fun setUp(): JsonObject {
        control.post("/setup?url=${endpoint.encoded()}&password=${PASSWORD.encoded()}")
        return settle()
    }

    /** Waits for the sync the setup started to finish, and requires that it succeeded. */
    suspend fun settle(): JsonObject {
        val state = awaitState("the first sync to finish") { state ->
            val sync = state["sync"]?.jsonPrimitive?.content.orEmpty()
            sync.startsWith("Succeeded(") || sync.startsWith("Failed(")
        }
        val sync = state.string("sync")
        check(sync.startsWith("Succeeded(")) { "sync did not succeed: $sync" }
        return state
    }

    fun state(): JsonObject = control.state()

    fun post(path: String): JsonObject = control.post(path)

    suspend fun awaitState(what: String, done: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + 60_000
        var lastError: Throwable? = null
        var lastState: JsonObject? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val state = control.state()
                lastState = state
                if (done(state)) return state
            } catch (unreachable: java.io.IOException) {
                lastError = unreachable
            }
            kotlinx.coroutines.delay(250)
        }
        // What the app last said it was doing: a bare "timed out" cannot tell a download that never
        // started from a view that never mounted from an app that died.
        error(
            "timed out waiting for $what" +
                (lastError?.let { "\nlast error: $it" } ?: "") +
                (lastState?.let { "\nlast /state: $it" } ?: "\nthe control server never answered"),
        )
    }

    suspend fun awaitTrue(what: String, done: () -> Boolean) {
        awaitState(what) { done() }
    }

    /** The app's §4 cache root inside its simulator container. */
    fun cacheRoot(): File = File(simulator.dataContainer(), "Library/Application Support/net.stho.photos")

    fun blobsOnDisk(): Set<String> = namesIn("blobs")

    fun packsOnDisk(): Set<String> = namesIn("packs")

    private fun namesIn(directory: String): Set<String> =
        File(cacheRoot(), directory).list()?.filterNot { it.endsWith(".part") }?.toSet().orEmpty()

    /** What the device drew, for a person to look at — never compared (decision 8). */
    fun screenshot(name: String) {
        simulator.screenshot(File(scratch, "$name.png"))
    }

    override fun close() {
        uiTests.forEach(UiTest::close)
        runCatching { simulator.terminate() }
    }
}

/** The runner app Xcode wraps `PhotosUITests` in, which is what asks PhotoKit for access. */
internal const val UI_TEST_RUNNER: String = "net.stho.photos.uitests.xctrunner"

internal const val BUNDLE_ID: String = "net.stho.photos"

/** An XCUITest running in its own `xcodebuild`, its output kept in a log beside the scenario. */
internal class UiTest private constructor(private val process: Process, private val log: File) : AutoCloseable {
    private val output = StringBuffer()
    private val reader = Thread {
        process.inputStream.bufferedReader().forEachLine { line ->
            output.append(line).append('\n')
            log.appendText(line + "\n")
        }
    }.apply {
        isDaemon = true
        start()
    }

    /** The first line holding [marker], waiting for the test to print it. */
    fun awaitLine(marker: String, seconds: Long = 300): String {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (System.currentTimeMillis() < deadline) {
            output.lines().firstOrNull { marker in it }?.let { return it }
            if (!process.isAlive) {
                reader.join(5_000)
                output.lines().firstOrNull { marker in it }?.let { return it }
                error("the UI test ended before printing $marker; its log is $log\n${summary()}")
            }
            Thread.sleep(250)
        }
        error("no $marker from the UI test within ${seconds}s; its log is $log\n${summary()}")
    }

    /** Waits for the test to end, requires that it passed, and returns everything it printed. */
    fun awaitSuccess(seconds: Long = 600): String {
        check(process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) {
            "the UI test did not finish within ${seconds}s; its log is $log"
        }
        reader.join(5_000)
        check(process.exitValue() == 0) { "the UI test failed (exit ${process.exitValue()}); its log is $log\n${summary()}" }
        return output.toString()
    }

    private fun summary(): String =
        output.lines().filter { "PHOTOS_" in it || "error" in it || "failed" in it }.takeLast(20).joinToString("\n")

    override fun close() {
        if (process.isAlive) process.destroy()
    }

    companion object {
        fun start(xctestrun: File, device: String, test: String, environment: Map<String, String>, log: File): UiTest {
            log.parentFile.mkdirs()
            val process = ProcessBuilder(
                "xcodebuild", "test-without-building",
                "-xctestrun", xctestrun.absolutePath,
                "-destination", "platform=iOS Simulator,name=$device",
                "-only-testing:$test",
            )
                .redirectErrorStream(true)
                .apply { environment().putAll(environment.mapKeys { (name, _) -> "TEST_RUNNER_$name" }) }
                .start()
            return UiTest(process, log)
        }
    }
}

/** `/state` over HTTP. The protocol is `:app:control`'s, shared with the desktop. */
internal class Control(private val base: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    fun state(): JsonObject = request(HttpRequest.newBuilder(URI("$base/state")).GET())

    fun post(path: String): JsonObject =
        request(HttpRequest.newBuilder(URI("$base$path")).POST(HttpRequest.BodyPublishers.noBody()))

    private fun request(builder: HttpRequest.Builder): JsonObject {
        val response = http.send(builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "${response.request().uri()} answered ${response.statusCode()}: ${response.body()}" }
        return Json.parseToJsonElement(response.body()).jsonObject
    }
}

/** `xcrun simctl`, one device, and every failure loud. */
internal class Simulator(val device: String) {

    fun boot() {
        // Booting a booted device exits non-zero; `bootstatus -b` is what actually waits.
        run("xcrun", "simctl", "boot", device, allowFailure = true)
        run("xcrun", "simctl", "bootstatus", device, "-b")
    }

    fun install(app: File) = run("xcrun", "simctl", "install", device, app.absolutePath)

    fun uninstall() = run("xcrun", "simctl", "uninstall", device, BUNDLE_ID, allowFailure = true)

    fun resetKeychain() = run("xcrun", "simctl", "keychain", device, "reset")

    /**
     * Full photo-library access, as a person tapping "Allow Full Access" records it.
     *
     * `simctl privacy grant photos` alone is not enough on iOS 26: it writes the grant as set by the
     * system (`auth_reason` 4, `auth_version` 1), and PhotoKit still puts up its full-access alert —
     * then waits on it inside `PLPrivacy`, so the app's request never returns. Measured on the
     * runner: the same row rewritten as the user's choice at the current version (`auth_reason` 2,
     * `auth_version` 2), with `tccd` restarted to drop its cache, answers Full with no alert.
     */
    fun grantPhotos(vararg bundles: String) {
        // The simulator's HOME is its data directory on the host, where TCC.db lives.
        val database = File(run("xcrun", "simctl", "getenv", device, "HOME").trim(), "Library/TCC/TCC.db")
        for (bundle in bundles) {
            run("xcrun", "simctl", "privacy", device, "grant", "photos", bundle)
            check(database.isFile) { "no TCC database at $database" }
            run(
                "sqlite3", database.absolutePath,
                "update access set auth_reason = 2, auth_version = 2 " +
                    "where client = '$bundle' and service = 'kTCCServicePhotos';",
            )
        }
        // Once, after every row: a second kill straight after the first finds no tccd to stop and
        // exits 3 — measured, as a scenario failing before it had done anything. launchd brings tccd
        // back on the next request, so an already-stopped one is not a failure.
        run("xcrun", "simctl", "spawn", device, "launchctl", "kill", "TERM", "user/foreground/com.apple.tccd", allowFailure = true)
        Thread.sleep(2_000)
    }

    fun addMedia(files: List<File>) = run("xcrun", "simctl", "addmedia", device, *files.map { it.absolutePath }.toTypedArray())

    fun terminate() = run("xcrun", "simctl", "terminate", device, BUNDLE_ID, allowFailure = true)

    /**
     * Launched without `--console-pty`, so the app outlives this call. The port crosses as
     * `SIMCTL_CHILD_*`, the only variables `simctl` passes on to the app.
     */
    fun launch(port: Int) =
        run("xcrun", "simctl", "launch", device, BUNDLE_ID, environment = mapOf("SIMCTL_CHILD_PHOTOS_CONTROL_PORT" to "$port"))

    fun dataContainer(): File = File(run("xcrun", "simctl", "get_app_container", device, BUNDLE_ID, "data").trim())

    fun screenshot(file: File) = run("xcrun", "simctl", "io", device, "screenshot", "--type=png", file.absolutePath)

    private fun run(vararg command: String, allowFailure: Boolean = false, environment: Map<String, String> = emptyMap()): String {
        val process = try {
            ProcessBuilder(*command).redirectErrorStream(true).apply { environment().putAll(environment) }.start()
        } catch (missing: java.io.IOException) {
            error("${command.first()} is not available -- the iOS suite needs Xcode's command-line tools")
        }
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(allowFailure || exit == 0) { "${command.joinToString(" ")} exited $exit:\n$output" }
        return output
    }

}

internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

internal fun JsonObject.albums(): List<JsonObject> = getValue("albums").jsonArray.map { it.jsonObject }

internal fun JsonObject.photos(): List<JsonObject> = getValue("photos").jsonArray.map { it.jsonObject }

internal fun JsonObject.album(name: String): JsonObject = albums().single { it.string("name") == name }

internal fun JsonObject.cache(): JsonObject = getValue("cache").jsonObject

internal fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean

internal fun JsonObject.nullableString(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun String.encoded(): String = URLEncoder.encode(this, Charsets.UTF_8)
