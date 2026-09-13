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
import net.stho.photos.app.AppUi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
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

    /** Starts the app, exactly as `main` would minus the window, and lets the first sync run. */
    suspend fun launch(): AppUi {
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
        val scene = ImageComposeScene(width = 430, height = 890, density = Density(2f)) {
            PhotosTheme { App(running.model, running.thumbnails, endpoint.asStorageUrl(), onLogOut = {}) }
        }
        try {
            val png = requireNotNull(scene.render().encodeToData()) { "skia declined to encode" }.bytes
            java.io.File(cacheRoot.toString()).parentFile.resolve("$name.png").writeBytes(png)
        } finally {
            scene.close()
        }
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
        return write(name, rows, thumbnails).id
    }

    /**
     * An album of the three shapes a row can have, each backed by real bytes (decision 2).
     *
     * A still, a video with its poster and transcode, and a Live Photo with its viewing image,
     * untouched still and MOV — six blobs, uploaded under the content hashes the rows name, from
     * the media `:tests:fixtures` generated for this build. The app never learns how a blob was
     * made, so nothing here runs ingest; that correctness is `:tests:cli`'s.
     */
    suspend fun mediaAlbum(name: String): MediaAlbum {
        val media = java.io.File(
            requireNotNull(System.getProperty("photos.fixture.media")) {
                "photos.fixture.media is unset -- the build writes it with :tests:fixtures:fixtureMedia"
            },
        )
        val files = mutableMapOf<ObjectId, java.io.File>()
        suspend fun blob(file: String): ObjectId {
            val source = java.io.File(media, file)
            require(source.isFile) { "missing fixture media: $source" }
            val path = Path(source.absolutePath)
            val id = ObjectId.ofContent(path)
            s3.put(id.blobKey, Body.File(path))
            files[id] = source
            return id
        }
        val at = Instant.parse("2024-06-01T12:00:00Z")
        val still = blob(PHOTO)
        val poster = blob(POSTER)
        val video = blob(VIDEO)
        val liveView = blob(LIVE_VIEW)
        val liveStill = blob(LIVE_STILL)
        val liveVideo = blob(LIVE_VIDEO)
        // One `takenAt`, so §3's order is the filename's and the three rows sit in a known order.
        val rows = listOf(
            PhotoRow(
                id = Uuid.random(), filename = "IMG_0001.HEIC", takenAt = at, width = 320, height = 240,
                bytes = files.getValue(still).length(), mediaType = MediaType.PHOTO, imageId = still,
            ),
            PhotoRow(
                id = Uuid.random(), filename = "IMG_0002.mp4", takenAt = at, width = 64, height = 48,
                bytes = files.getValue(video).length(), mediaType = MediaType.VIDEO,
                imageId = poster, videoId = video,
            ),
            PhotoRow(
                id = Uuid.random(), filename = "IMG_0003.HEIC", takenAt = at, width = 352, height = 264,
                bytes = files.getValue(liveView).length(), mediaType = MediaType.LIVE_PHOTO,
                imageId = liveView, liveStillId = liveStill, liveVideoId = liveVideo,
                liveVideoFilename = "IMG_0003.mov",
            ),
        )
        val written = write(name, rows, thumbnails = true)
        return MediaAlbum(written.id, written.thumbsId, rows, files)
    }

    private class Written(val id: Uuid, val thumbsId: ObjectId?)

    private suspend fun write(name: String, rows: List<PhotoRow>, thumbnails: Boolean): Written {
        kotlinx.io.files.SystemFileSystem.createDirectories(staging)
        val albumId = Uuid.random()
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
        return Written(albumId, thumbsId)
    }

    private companion object {
        // The generator's file names (`FixtureMedia` in `:tests:fixtures`). Spelled again here
        // because this suite runs on the JVM and cannot link that linuxX64 module.
        const val PHOTO = "photo.heic"
        const val POSTER = "poster.heic"
        const val VIDEO = "video.mp4"
        const val LIVE_VIEW = "live-view.heic"
        const val LIVE_STILL = "live-still.heic"
        const val LIVE_VIDEO = "live.mov"
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

/** A media album as declared: its rows, and the source file behind every blob they name. */
internal class MediaAlbum(
    val id: Uuid,
    val thumbsId: ObjectId?,
    val rows: List<PhotoRow>,
    private val files: Map<ObjectId, java.io.File>,
) {
    /** Every blob the rows own — what a download must fetch, and nothing more. */
    val objectIds: Set<ObjectId> get() = rows.flatMap { it.objectIds }.toSet()

    fun bytesOf(id: ObjectId): ByteArray = files.getValue(id).readBytes()

    fun row(type: MediaType): PhotoRow = rows.single { it.mediaType == type }
}
