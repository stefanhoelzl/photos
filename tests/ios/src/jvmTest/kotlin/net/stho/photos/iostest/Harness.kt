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
    val timings = Timings()
    val simulator = Simulator(required("photos.ios.device", "the build task passes it"), timings)
    try { runBlocking {
        val s3 = S3Client(
            storage = endpoint.asStorageUrl(),
            secretAccessKey = PASSWORD,
            http = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp),
            payloadSigning = S3Client.PayloadSigning.SIGNED,
        )
        val zone = Zone(s3, Path(File(scratch, "staging").absolutePath))
        zone.clear()
        IosScenario(zone, endpoint, simulator, app, scratch, media, timings).use { scenario ->
            try {
                timings.measure("body") { scenario.body() }
            } catch (failure: Throwable) {
                scenario.printUiTestLogs()
                throw failure
            }
        }
    } } finally {
        println(timings.report(label))
    }
}

/**
 * Where a scenario's time went, printed as one line when it ends, pass or fail.
 *
 * The suite runs every scenario serially on one simulator, so its length is the sum of what each
 * one spends before it asserts anything -- booting, reinstalling, relaunching, starting
 * `xcodebuild`. This is what says which of those is worth taking out.
 *
 * `body` is the whole scenario; every other entry is a part of it, so the parts need not add up.
 */
internal class Timings {
    private val started = System.nanoTime()
    private val spent = linkedMapOf<String, Long>()
    private val calls = linkedMapOf<String, Int>()

    inline fun <T> measure(what: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(what, System.nanoTime() - start)
        }
    }

    fun record(what: String, nanos: Long) {
        spent.merge(what, nanos, Long::plus)
        calls.merge(what, 1, Int::plus)
    }

    fun report(label: String): String {
        val seconds = { nanos: Long -> "%.1fs".format(nanos / 1e9) }
        val parts = spent.entries.joinToString(" ") { (what, nanos) ->
            val times = calls.getValue(what).takeIf { it > 1 }?.let { "x$it" }.orEmpty()
            "$what=${seconds(nanos)}$times"
        }
        return "PHOTOS_TIMING $label total=${seconds(System.nanoTime() - started)} $parts"
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
    private val timings: Timings,
) : AutoCloseable {

    /**
     * Full photo-library access for the installed app, as a person tapping Allow would give it
     * (§8). Granted after [install], which is what resets it.
     */
    fun allowPhotos() = timings.measure("allowPhotos") {
        // The UI test runner reads and writes the library too, when it seeds a Live Photo.
        simulator.grantPhotos(BUNDLE_ID, UI_TEST_RUNNER)
    }

    /**
     * Fixture media into the simulator's own photo library — the phone's gallery for an upload
     * scenario. The library is the device's, not the app's, so resetting the app does not empty it.
     */
    fun addToLibrary(vararg fixtures: String) = simulator.addMedia(fixtures.map { File(media, it) })

    /** A fixture file, for a UI test that reads it from the host. */
    fun fixture(name: String): File = File(media, name)

    private val uiTests = mutableListOf<UiTest>()

    /**
     * Tests from `PhotosUITests`, started beside the scenario — the suite's hands for what a
     * scenario over HTTP cannot do: tap a system alert, seed the library through PhotoKit.
     *
     * Started with `xcodebuild test-without-building`, which installs the app it tests over the one
     * [install] put there, so a UI test is started *before* [launch]. [environment] reaches the test
     * as `TEST_RUNNER_*`, the one prefix xcodebuild passes on. Returns once [ready] has been printed,
     * or straight away without one.
     *
     * Several [tests] share one `xcodebuild`, which is worth doing: each one takes 20-30 s to start
     * on the runner. They run one after another in XCTest's order, alphabetical by class.
     */
    fun startUiTest(vararg tests: String, environment: Map<String, String> = emptyMap(), ready: String? = null): UiTest {
        val products = requireNotNull(app.parentFile?.parentFile) { "no products directory above $app" }
        val xctestrun = products.listFiles { file -> file.name.endsWith(".xctestrun") }?.singleOrNull()
            ?: error("no .xctestrun in $products -- Scripts/ios-sim.sh build builds the app for testing")
        val log = File(scratch, "${tests.joinToString("+") { it.replace('/', '-') }}.log")
        // Created by [UiTest.arm], for a `SystemAlerts` to start looking. Unused by any other test.
        val armed = File(scratch, "${log.nameWithoutExtension}.armed").apply { delete() }
        val all = environment + ("PHOTOS_TAP_ARMED" to armed.absolutePath)
        return timings.measure("ui-test start") {
            UiTest.start(xctestrun, simulator.device, tests.map { "PhotosUITests/$it" }, all, log, armed, timings)
                .also { uiTests += it }
                .also { test -> ready?.let(test::awaitLine) }
        }
    }

    /**
     * A fresh port for every launch, not one per scenario. A terminated app can still hold its
     * listening socket for a moment, and a relaunch onto the same port then fails to bind and
     * answers nothing -- measured, as the setup scenario's relaunch timing out while every
     * single-launch scenario passed.
     */
    private var control = Control("http://127.0.0.1:0")

    /**
     * The app as a fresh install leaves it: an empty container, no photo-library grant and no
     * credentials — resetting the keychain because iOS keeps Keychain items across an uninstall, so
     * without it the next scenario would start already set up.
     *
     * Only the suite's first scenario really installs. Every later one puts the same state back
     * without leaving the app uninstalled: the container is emptied back to what that install left
     * in it, and the app's privacy grants are reset, which is the rest of what an uninstall undoes.
     * The app keeps nothing outside its container but the Keychain. An uninstall and install cost
     * 5-6 s on the runner, twenty-odd times a suite.
     */
    fun install() = timings.measure("install") {
        simulator.boot()
        simulator.terminate()
        val fresh = installed[simulator.device]
        if (fresh == null) {
            simulator.uninstall()
            simulator.install(app)
            installed[simulator.device] = FreshContainer.of(simulator.dataContainer())
        } else {
            fresh.restore(simulator.dataContainer())
            simulator.resetPrivacy()
        }
        simulator.resetKeychain()
    }

    /**
     * Whether this scenario has launched the app yet. A first launch follows [install], which leaves
     * nothing running, and `simctl terminate` costs a second or more even with nothing to stop.
     */
    private var launched = false

    /** Launch (or relaunch) and wait until the control server answers. */
    suspend fun launch(): JsonObject = timings.measure("launch") {
        if (launched) simulator.terminate()
        launched = true
        val port = ServerSocket(0).use { it.localPort }
        control = Control("http://127.0.0.1:$port")
        simulator.launch(port)
        awaitState("the control server on port $port") { true }
    }

    /** §1's setup through the real Keychain, against this scenario's S3Mock zone. */
    suspend fun setUp(): JsonObject = timings.measure("setUp") {
        control.post("/setup?url=${endpoint.encoded()}&password=${PASSWORD.encoded()}")
        settle()
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

    /**
     * The end of every UI test this scenario started, for a failure to be read from the test report.
     * A scenario that times out waiting on the app is often waiting on an alert a UI test was meant
     * to tap, and what that test last saw is in its own log -- on the runner, gone with it.
     */
    fun printUiTestLogs() {
        for (test in uiTests) println(test.tail())
    }

    // The app is left running: the next scenario's [install] stops it before it resets it.
    override fun close() {
        uiTests.forEach(UiTest::close)
    }
}

/**
 * The app's installed state, per device, for the suite's JVM: every scenario runs in this one
 * process (`maxParallelForks = 1`), so the first one to install is the suite's only install.
 */
private val installed = mutableMapOf<String, FreshContainer>()

/**
 * What a fresh install puts in the app's data container, by path relative to it: the directory
 * skeleton, and the container manager's own metadata file, which must survive the app's own files.
 */
internal class FreshContainer private constructor(private val paths: Set<String>) {

    /** Deletes everything the app has written since, leaving what the install left. */
    fun restore(container: File) {
        container.walkBottomUp()
            .filter { it != container && it.relativeTo(container).path !in paths }
            .forEach { check(it.deleteRecursively()) { "could not empty $it from the app's container" } }
    }

    companion object {
        fun of(container: File): FreshContainer =
            FreshContainer(container.walkTopDown().filter { it != container }.map { it.relativeTo(container).path }.toSet())
    }
}

/** The runner app Xcode wraps `PhotosUITests` in, which is what asks PhotoKit for access. */
internal const val UI_TEST_RUNNER: String = "net.stho.photos.uitests.xctrunner"

internal const val BUNDLE_ID: String = "net.stho.photos"

/** An XCUITest running in its own `xcodebuild`, its output kept in a log beside the scenario. */
internal class UiTest private constructor(
    private val process: Process,
    private val log: File,
    private val armed: File,
    private val timings: Timings,
) : AutoCloseable {
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
        val finished = timings.measure("ui-test finish") { process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS) }
        check(finished) { "the UI test did not finish within ${seconds}s; its log is $log" }
        reader.join(5_000)
        check(process.exitValue() == 0) { "the UI test failed (exit ${process.exitValue()}); its log is $log\n${summary()}" }
        return output.toString()
    }

    /**
     * Lets a `SystemAlerts` start looking for its buttons, which it does not do until armed: looking
     * snapshots Springboard and the app twice a second, and doing that during `simctl addmedia`
     * stalled the import for minutes. Called just before whatever raises the alert.
     */
    fun arm() {
        armed.createNewFile()
        awaitLine("PHOTOS_TAPPER_ARMED", seconds = 30)
    }

    /** The first line holding [marker] printed so far, without waiting for one. */
    fun printed(marker: String): String? = output.lines().firstOrNull { marker in it }

    /** The last [lines] this test printed, headed by its log's name. */
    fun tail(lines: Int = 60): String =
        "--- the last $lines lines of $log\n" + output.lines().takeLast(lines).joinToString("\n")

    private fun summary(): String =
        output.lines().filter { "PHOTOS_" in it || "error" in it || "failed" in it }.takeLast(20).joinToString("\n")

    override fun close() {
        if (process.isAlive) process.destroy()
    }

    companion object {
        fun start(
            xctestrun: File,
            device: String,
            tests: List<String>,
            environment: Map<String, String>,
            log: File,
            armed: File,
            timings: Timings,
        ): UiTest {
            log.parentFile.mkdirs()
            val process = ProcessBuilder(
                "xcodebuild", "test-without-building",
                "-xctestrun", xctestrun.absolutePath,
                "-destination", "platform=iOS Simulator,name=$device",
                *tests.map { "-only-testing:$it" }.toTypedArray(),
            )
                .redirectErrorStream(true)
                .apply { environment().putAll(environment.mapKeys { (name, _) -> "TEST_RUNNER_$name" }) }
                .start()
            return UiTest(process, log, armed, timings)
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
/** Devices this suite's JVM has booted — see [Simulator.boot]. */
private val booted = mutableSetOf<String>()

internal class Simulator(val device: String, private val timings: Timings) {

    /**
     * Once per suite: nothing shuts the device down between scenarios, and asking again cost 1-2 s
     * a scenario.
     */
    fun boot() {
        if (!booted.add(device)) return
        // Booting a booted device exits non-zero; `bootstatus -b` is what actually waits.
        run("xcrun", "simctl", "boot", device, allowFailure = true)
        run("xcrun", "simctl", "bootstatus", device, "-b")
    }

    /** Every privacy grant the app holds, gone as an uninstall takes them — [grantPhotos]' among them. */
    fun resetPrivacy() = run("xcrun", "simctl", "privacy", device, "reset", "all", BUNDLE_ID)

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

    private fun run(vararg command: String, allowFailure: Boolean = false, environment: Map<String, String> = emptyMap()): String =
        // Keyed by the verb -- `simctl install`, `sqlite3` -- so the report says which command the time went to.
        timings.measure(if (command.first() == "xcrun") "simctl ${command[2]}" else command.first()) {
            runTimed(*command, allowFailure = allowFailure, environment = environment)
        }

    private fun runTimed(vararg command: String, allowFailure: Boolean, environment: Map<String, String>): String {
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
