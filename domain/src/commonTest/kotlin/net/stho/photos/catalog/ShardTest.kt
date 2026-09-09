package net.stho.photos.catalog

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import net.stho.photos.ShardFailure
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow

/**
 * A shard is the unit of write in §2's conflict model — rewritten wholesale, guarded by
 * `If-Match`. If a round trip loses a field, the loss is silent and permanent, so every column is
 * checked rather than sampled.
 */
class ShardTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()


    private fun roundTrip(shard: Shard): Shard {
        val path = Path(temporaryDirectory("shard"), "${shard.info.id}.db")
        shard.writeTo(path)
        return path.readShard()
    }

    @Test
    fun everyColumnSurvivesARoundTrip() {
        val photos = listOf(
            photo("IMG_0001.jpg"),
            photo("IMG_0002.HEIC", mediaType = MediaType.LIVE_PHOTO),
            photo("MVI_0003.MOV", mediaType = MediaType.VIDEO),
        )
        val original = album("Neuseeland", photos = photos, coverPhotoId = photos[1].id)

        val restored = roundTrip(original)

        assertEquals(original.info, restored.info)
        assertEquals(original.photos.toSet(), restored.photos.toSet())
    }

    /** Containers have none, by §2's XOR rule. */
    @Test
    fun anAlbumWithNoPhotosIsLegal() {
        val restored = roundTrip(album("Weihnachten", photos = emptyList(), thumbsId = null))

        assertTrue(restored.photos.isEmpty())
        assertNull(restored.info.thumbsId)
        assertEquals("Weihnachten", restored.info.name)
    }

    @Test
    fun nullableFieldsStayNullRatherThanBecomingZero() {
        val bare = PhotoRow(id = Uuid.random(), filename = "scan.png")
        val restored = roundTrip(album("Scans", photos = listOf(bare)))

        val photo = assertNotNull(restored.photos.firstOrNull())
        assertNull(photo.takenAt)
        assertNull(photo.latitude)
        assertNull(photo.width)
        assertNull(photo.bytes)
        assertNull(photo.originalId)
        assertNull(photo.previewId)
    }

    /**
     * §2 measured NFC normalisation out of the project: names are stored exactly as given, and a
     * decomposed name is a different name. This is that decision, asserted.
     */
    @Test
    fun namesAreStoredExactlyAsGiven() {
        val decomposed = "Gru\u0308n" // "Grün" as u + combining diaeresis

        val restored = roundTrip(album(decomposed, photos = listOf(photo("cafe\u0301.jpg"))))

        assertEquals(decomposed, restored.info.name)
        assertEquals(5, restored.info.name.length)
        assertEquals("cafe\u0301.jpg", restored.photos[0].filename)
    }

    @Test
    fun allThreeMediaTypesRoundTripWithTheRightObjectIds() {
        val restored = roundTrip(
            album(
                "Mixed",
                photos = listOf(
                    photo("IMG_0099.HEIC", mediaType = MediaType.LIVE_PHOTO),
                    photo("clip.avi", mediaType = MediaType.VIDEO),
                    photo("IMG_0001.jpg"),
                ),
            ),
        )
        val byName = restored.photos.associateBy(PhotoRow::filename)

        assertEquals(MediaType.LIVE_PHOTO, byName["IMG_0099.HEIC"]?.mediaType)
        assertNotNull(byName["IMG_0099.HEIC"]?.liveVideoId)
        assertNotNull(byName["clip.avi"]?.videoId)
        assertNull(byName["IMG_0001.jpg"]?.liveVideoId)
        assertNull(byName["IMG_0001.jpg"]?.videoId)
    }

    /**
     * §3: a reader reads anything at or below its own version and skips what is newer. The skip
     * must be a *distinguishable* failure — the CLI has to tell "unreadable" from "absent", or it
     * re-uploads the album as a duplicate.
     */
    @Test
    fun aShardFromANewerSchemaIsRefusedNotMisread() {
        val directory = temporaryDirectory("future")
        val shard = futureShard(directory)
        val path = Path(directory, "${shard.info.id}.db")

        val failure = assertFailsWith<ShardFailure.UnsupportedVersion> { path.readShard() }
        assertEquals(SHARD_SCHEMA_VERSION + 1, failure.found)
        assertEquals(SHARD_SCHEMA_VERSION, failure.supported)
        assertEquals(SHARD_SCHEMA_VERSION + 1, path.shardSchemaVersion())
    }

    @Test
    fun aShardAtTheCurrentVersionReadsNormally() {
        val shard = album("Now")
        val path = Path(temporaryDirectory("current"), "${shard.info.id}.db")
        shard.writeTo(path)

        assertEquals(SHARD_SCHEMA_VERSION, path.shardSchemaVersion())
        assertEquals("Now", path.readShard().info.name)
    }

    /** §3's probe: what a shard says about itself even when it is too new to read. */
    @Test
    fun aShardTooNewToReadIsStillIdentifiable() {
        val directory = temporaryDirectory("probe")
        val shard = futureShard(directory)
        val probe = Path(directory, "${shard.info.id}.db").probeShard()

        assertEquals(shard.info.id, probe.albumId)
        assertEquals("From The Future", probe.sourcePath)
        assertEquals(SHARD_SCHEMA_VERSION + 1, probe.schemaVersion)
    }

    /**
     * A database with no `album_info` is not a shard — a truncated download, a wrong key, an
     * unrelated file. A thumbnail pack is the nearest such thing this system actually has.
     */
    @Test
    fun aDatabaseThatIsNotAShardIsRejected() {
        val path = Path(temporaryDirectory("not-a-shard"), "unrelated.db")
        emptyMap<Uuid, ByteArray>().packThumbnails(into = path)

        assertFailsWith<ShardFailure.MissingAlbumInfo> { path.readShard() }
    }

    @Test
    fun objectIdsNamesEveryBlobTheAlbumOwns() {
        val live = photo("IMG_0099.HEIC", mediaType = MediaType.LIVE_PHOTO)
        val shard = album("Sommer", photos = listOf(live))

        val ids = shard.objectIds.toSet()
        assertTrue(assertNotNull(live.originalId) in ids)
        assertTrue(assertNotNull(live.liveVideoId) in ids)
        assertTrue(assertNotNull(live.previewId) in ids)
        assertTrue(assertNotNull(shard.info.thumbsId) in ids)
        assertEquals(4, ids.size)
    }
}

/**
 * Keys are the entire sync mechanism, so parsing one has to be strict: a key this cannot read is
 * skipped, never guessed at.
 */
class StorageKeyTest {

    @Test
    fun shardKeysRoundTripThroughTheirUuid() {
        val id = Uuid.random()

        assertEquals("meta/$id.db", id.shardKey)
        assertEquals(id, id.shardKey.asShardAlbumId())
    }

    @Test
    fun blobKeysCarryNoExtension() {
        val id = Uuid.random()
        assertEquals("blob/$id", id.blobKey)
        assertEquals(id, id.blobKey.asBlobObjectId())
    }

    @Test
    fun anythingThatIsNotAShardKeyIsRefused() {
        val refused = listOf(
            "meta/", // the prefix's own directory marker
            "meta/not-a-uuid.db",
            "meta/9F2C1AB7-3E44-4C2A-9D81-77B0E5C1AF02", // no .db
            "blob/9f2c1ab7-3e44-4c2a-9d81-77b0e5c1af02", // wrong prefix
            "meta/.db",
            "",
        )
        for (key in refused) assertNull(key.asShardAlbumId(), key)
    }

    @Test
    fun uuidCaseDoesNotMatterWhenReadingAKeyBack() {
        val id = Uuid.random()
        assertEquals(id, "meta/${id.toString().uppercase()}.db".asShardAlbumId())
    }
}
