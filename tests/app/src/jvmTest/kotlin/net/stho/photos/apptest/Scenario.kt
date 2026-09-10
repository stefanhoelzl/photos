package net.stho.photos.apptest

import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.stho.photos.catalog.ObjectId
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import net.stho.photos.adapter.linux.JdbcSqlDrivers
import net.stho.photos.catalog.AlbumInfo
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.blobKey
import net.stho.photos.catalog.packThumbnails
import net.stho.photos.catalog.shardKey
import net.stho.photos.catalog.writeTo
import net.stho.photos.desktop.PhotosApp
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.storage.Body
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.asStorageUrl
import net.stho.photos.storage.list
import net.stho.photos.ui.screens.VideoSurface
import net.stho.photos.ui.state.AppUi

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
    val scratch = root.resolve(label).also { Files.createDirectories(it) }
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
    private val cacheRoot: Path,
) : AutoCloseable {

    private var app: PhotosApp? = null

    /** Starts the app, exactly as `main` would minus the window, and lets the first sync run. */
    suspend fun launch(): AppUi {
        val started = PhotosApp(
            endpoint = endpoint,
            password = PASSWORD,
            cacheRoot = cacheRoot,
            // No shim: nothing here opens a HEIC preview, and a suite should not need a
            // native library to assert what is on a screen.
            decodeLibrary = null,
            // A player that opens nothing. `expect`/`actual` could not have been substituted
            // here, which is why the video surface is an injected port (decision 28).
            videoSurface = VideoSurface { _, _ -> },
        )
        app = started
        started.model.start()
        return settle()
    }

    val model get() = requireNotNull(app) { "launch() first" }.model

    /** Waits for the sync and any pack fetch the app queued behind it. */
    suspend fun settle(): AppUi {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val ui = model.state.value
            if (ui.sync !is net.stho.photos.ui.state.SyncStatus.Running &&
                ui.sync !is net.stho.photos.ui.state.SyncStatus.Never
            ) {
                return ui
            }
            kotlinx.coroutines.delay(50)
        }
        error("the app never finished its first sync")
    }

    override fun close() {
        app?.close()
    }
}

/** What the zone holds, declared rather than uploaded by hand at each call site. */
internal class Zone(private val s3: S3Client, private val staging: Path) {
    private val drivers = JdbcSqlDrivers()

    /** Everything in the zone, gone. Each scenario starts from a bucket it fully owns. */
    suspend fun clear() {
        s3.list().collect { listed ->
            if (!listed.isDirectoryMarker) s3.delete(listed.key)
        }
    }

    /**
     * One album: a shard, and a pack holding a thumbnail for each of its photos.
     *
     * Written with the same writer the CLI uses, so a scenario cannot declare a zone the real
     * ingest could never produce.
     */
    suspend fun album(name: String, photos: Int, thumbnails: Boolean = true): Uuid {
        kotlinx.io.files.SystemFileSystem.createDirectories(staging)
        val albumId = Uuid.random()
        val rows = (0 until photos).map { index ->
            PhotoRow(
                id = Uuid.random(),
                filename = "IMG_%04d.jpg".format(index),
                takenAt = Instant.parse("2024-01-01T00:00:00Z"),
                width = 4032,
                height = 3024,
                bytes = 3_400_000,
                mediaType = MediaType.PHOTO,
            )
        }
        // Packed first, then named after what it holds — since §2 a blob's key *is* its
        // content, so the id cannot be minted before the bytes exist.
        var thumbsId: ObjectId? = null
        if (thumbnails) {
            val pack = Path(staging, "$albumId-thumbs.db")
            rows.associate { it.id to jpeg() }.packThumbnails(pack, drivers)
            thumbsId = ObjectId.ofContent(pack)
            s3.put(thumbsId.blobKey, Body.File(pack))
        }
        val shardFile = Path(staging, "$albumId.db")
        Shard(
            AlbumInfo(
                id = albumId,
                name = name,
                addedAt = Instant.parse("2024-01-01T00:00:00Z"),
                thumbsId = thumbsId,
            ),
            rows,
        ).writeTo(shardFile, drivers)
        s3.put(albumId.shardKey, Body.File(shardFile))
        return albumId
    }

    /** A 16px square. Small on purpose: these are asserted on, never looked at. */
    private fun jpeg(): ByteArray {
        val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
        return java.io.ByteArrayOutputStream().use { out ->
            javax.imageio.ImageIO.write(image, "jpg", out)
            out.toByteArray()
        }
    }
}
