package net.stho.photos.apptest

import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import net.stho.photos.desktop.PhotosApp
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.asStorageUrl
import net.stho.photos.ui.screens.VideoSurface
import net.stho.photos.app.AppUi
import net.stho.photos.app.UploadModel
import net.stho.photos.zone.Zone
import net.stho.photos.desktop.renderFrame
import net.stho.photos.ui.screens.App
import net.stho.photos.ui.screens.PhotosTheme

/**
 * One end-to-end scenario: declare a zone, start the app against it, assert what the app says.
 *
 * `:tests:cli`'s counterpart, and deliberately the same shape — declare state, run the real
 * thing, assert state — with two differences the app forces. It starts the composition root
 * **in process and without a window**, because Compose Desktop needs a display for one; and it
 * asserts the state the app reports rather than the zone's contents, because what is under
 * test here is what a person would see.
 *
 * Skipped, not failed, when there is no S3Mock: the same bargain every other suite here
 * strikes, so a machine that cannot run a JVM server still runs everything offline.
 */
internal fun scenario(label: String, body: suspend Scenario.() -> Unit) {
    val endpoint = System.getProperty("photos.s3mock.endpoint")
    if (endpoint.isNullOrBlank()) {
        println("photos.s3mock.endpoint unset -- skipping app scenario '$label'")
        return
    }
    // In the working tree rather than the system temp: a scenario's cache is inspectable
    // after a failure, and it is gitignored with the rest of `build/`.
    val root = System.getProperty("photos.test.scratch")?.let(java.nio.file.Path::of)
        ?: Files.createTempDirectory("photos-app")
    // Emptied first, as `zone.clear()` empties the bucket. The cache is kept after a run so a
    // failure can be inspected, which means the *next* run inherits it -- and a pack from a
    // previous run's album, under a different content hash, is exactly what a scenario asserting
    // "packs holds this album's pack and nothing else" then trips over.
    val scratch = root.resolve(label).also {
        it.toFile().deleteRecursively()
        Files.createDirectories(it)
    }
    val cacheRoot = Path(scratch.resolve("cache").absolutePathString())
    runBlocking {
        val s3 = S3Client(
            storage = endpoint.asStorageUrl(),
            secretAccessKey = PASSWORD,
            http = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp),
            payloadSigning = S3Client.PayloadSigning.SIGNED,
        )
        val zone = Zone(s3, Path(scratch.resolve("staging").absolutePathString()))
        zone.clear()
        Scenario(zone, endpoint, cacheRoot).use { it.body() }
    }
}

internal const val PASSWORD: String = "test-secret"

internal class Scenario(
    val zone: Zone,
    private val endpoint: String,
    val cacheRoot: Path,
) : AutoCloseable {

    private var app: PhotosApp? = null

    /** The scenario's stand-in for the phone's photo library, beside its cache (§8). */
    val gallery: java.io.File get() = java.io.File(cacheRoot.toString()).parentFile.resolve("gallery")

    /**
     * Starts the app, exactly as `main` would minus the window, and lets the first sync run.
     * [withGallery] turns upload on, reading [gallery].
     */
    suspend fun launch(withGallery: Boolean = false): AppUi {
        val started = PhotosApp(
            storage = endpoint.asStorageUrl(),
            password = PASSWORD,
            cacheRoot = cacheRoot,
            // No shim: nothing here opens a HEIC preview, and a suite should not need a
            // native library to assert what is on a screen.
            decodeLibrary = null,
            // A player that opens nothing. `expect`/`actual` could not have been substituted
            // here, which is why the video surface is an injected port (decision 28).
            videoSurface = VideoSurface { _, _ -> },
            galleryRoot = if (withGallery) gallery.also { it.mkdirs() }.path else null,
        )
        app = started
        started.start()
        return settle()
    }

    val model get() = requireNotNull(app) { "launch() first" }.model

    val uploads: UploadModel get() = requireNotNull(requireNotNull(app) { "launch() first" }.uploads) {
        "launch(withGallery = true) to upload"
    }

    /** Waits for the sync and any pack fetch the app queued behind it. */
    suspend fun settle(): AppUi {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val ui = model.state.value
            if (ui.sync !is net.stho.photos.app.SyncStatus.Running &&
                ui.sync !is net.stho.photos.app.SyncStatus.Never
            ) {
                return ui
            }
            kotlinx.coroutines.delay(50)
        }
        error("the app never finished its first sync")
    }

    /** Polls until [done] holds — for what arrives after the first sync: a fetch, a download. */
    suspend fun await(what: String, done: (AppUi) -> Boolean): AppUi {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val ui = model.state.value
            if (done(ui)) return ui
            kotlinx.coroutines.delay(50)
        }
        error("timed out waiting for $what")
    }

    /** The same, for a condition the model does not report — what is on disk. */
    suspend fun awaitTrue(what: String, done: () -> Boolean) {
        await(what) { done() }
    }

    /**
     * What `blobs/` holds, by name: the cache as the disk has it rather than as the model reports
     * it (decision 8). A fetch in flight is a `.part` and is not counted, as `FileBlobStore` does not.
     */
    fun blobsOnDisk(): Set<String> = namesIn("blobs")

    fun packsOnDisk(): Set<String> = namesIn("packs")

    private fun namesIn(directory: String): Set<String> =
        java.io.File(cacheRoot.toString(), directory).list()
            ?.filterNot { it.endsWith(".part") }?.toSet().orEmpty()

    /**
     * One frame of the running app, written beside the scenario's cache for a person to look at.
     *
     * Never compared: the repo's rule for rendered frames, since a pixel diff fails on a font
     * hinting differently rather than on a bug.
     */
    fun screenshot(name: String) {
        val running = requireNotNull(app) { "launch() first" }
        // Through the root's own offscreen path, so a frame here is drawn exactly as `/screenshot`
        // draws one — on the one thread that keeps the scene's effects from measuring it mid-render.
        val png = renderFrame(width = 430, height = 890) {
            PhotosTheme {
                App(running.model, running.thumbnails, endpoint.asStorageUrl(), onLogOut = {}, uploads = running.uploads)
            }
        }
        java.io.File(cacheRoot.toString()).parentFile.resolve("$name.png").writeBytes(png)
    }

    override fun close() {
        app?.close()
    }
}
