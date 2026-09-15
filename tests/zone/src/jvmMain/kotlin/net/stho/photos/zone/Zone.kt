package net.stho.photos.zone

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import net.stho.photos.catalog.AlbumInfo
import net.stho.photos.catalog.ObjectId
import net.stho.photos.catalog.PAGE_SIZE
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.blobKey
import net.stho.photos.catalog.packThumbnails
import net.stho.photos.catalog.shardKey
import net.stho.photos.catalog.writeTo
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.ports.Journal
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.storage.Body
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.list

/** What the zone holds, declared rather than uploaded by hand at each call site. */
public class Zone(private val s3: S3Client, private val staging: Path) {
    private val drivers = JdbcShardDrivers()

    /** Everything in the zone, gone. Each scenario starts from a bucket it fully owns. */
    public suspend fun clear() {
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
    public suspend fun album(
        name: String,
        photos: Int,
        thumbnails: Boolean = true,
        /** Where every photo was taken, as EXIF GPS would say; null for photos with no location. */
        at: Pair<Double, Double>? = null,
        /** Per photo, by position, overriding [at]: a null entry is a photo with no location. */
        places: List<Pair<Double, Double>?>? = null,
    ): Uuid {
        val rows = (0 until photos).map { index ->
            val place = if (places != null) places.getOrNull(index) else at
            PhotoRow(
                id = Uuid.random(),
                filename = "IMG_%04d.jpg".format(index),
                takenAt = Instant.parse("2024-01-01T00:00:00Z"),
                latitude = place?.first,
                longitude = place?.second,
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
    public suspend fun mediaAlbum(name: String): MediaAlbum {
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
public class MediaAlbum(
    public val id: Uuid,
    public val thumbsId: ObjectId?,
    public val rows: List<PhotoRow>,
    private val files: Map<ObjectId, java.io.File>,
) {
    /** Every blob the rows own — what a download must fetch, and nothing more. */
    public val objectIds: Set<ObjectId> get() = rows.flatMap { it.objectIds }.toSet()

    public fun bytesOf(id: ObjectId): ByteArray = files.getValue(id).readBytes()

    public fun row(type: MediaType): PhotoRow = rows.single { it.mediaType == type }
}


/**
 * How the builder opens the shards and packs it writes.
 *
 * Its own, rather than `:adapter:linux`'s `JdbcSqlDrivers`, and the reason is the Mac: that
 * module's JVM compile builds the Linux imaging shim first, which no macOS machine can do, and
 * `:tests:ios` has to declare zones there. The JDBC calls are the same three the adapter makes —
 * page size before anything is written, journal mode, schema only into an empty file.
 */
internal class JdbcShardDrivers : SqlDrivers {
    override fun open(
        path: String,
        schema: SqlSchema<QueryResult.Value<Unit>>,
        creating: Boolean,
        journal: Journal,
    ): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        driver.execute(null, "PRAGMA page_size = $PAGE_SIZE", 0)
        driver.execute(null, "PRAGMA journal_mode = ${journal.name}", 0)
        if (creating && driver.userVersion() == 0L) {
            schema.create(driver).value
            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
        }
        return driver
    }

    private fun SqlDriver.userVersion(): Long =
        executeQuery(null, "PRAGMA user_version", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
        }, 0).value ?: 0L
}
