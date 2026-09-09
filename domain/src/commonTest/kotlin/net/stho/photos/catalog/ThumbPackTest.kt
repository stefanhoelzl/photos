package net.stho.photos.catalog

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The pack exists to keep §6's promise that a grid opens in one request, offline. What has to
 * hold: the bytes come back exactly, and one thumbnail can be read without the rest.
 */
class ThumbPackTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()


    private fun jpeg(seed: Int, size: Int = 8_700): ByteArray =
        ByteArray(size) { ((it + seed) % 251).toByte() }

    private fun pack(thumbnails: Map<Uuid, ByteArray>, label: String = "pack"): Path {
        val path = Path(temporaryDirectory(label), "thumbs.db")
        thumbnails.packThumbnails(into = path, drivers = testDrivers)
        return path
    }

    @Test
    fun thumbnailsRoundTripByteForByte() {
        val thumbnails = (0 until 20).associate { Uuid.random() to jpeg(it) }
        val restored = ThumbPack(pack(thumbnails), testDrivers).unpack()

        assertEquals(thumbnails.size, restored.size)
        for ((id, bytes) in thumbnails) assertContentEquals(bytes, restored[id])
    }

    @Test
    fun oneThumbnailCanBeReadWithoutUnpackingTheAlbum() {
        val thumbnails = (0 until 50).associate { Uuid.random() to jpeg(it) }
        val wanted = thumbnails.keys.minBy(Uuid::toString)
        val pack = ThumbPack(pack(thumbnails, "single"), testDrivers)

        assertContentEquals(thumbnails.getValue(wanted), pack.thumbnail(wanted))
        assertNull(pack.thumbnail(Uuid.random()))
    }

    @Test
    fun idsCanBeListedWithoutTheirBytes() {
        val thumbnails = (0 until 10).associate { Uuid.random() to jpeg(it) }
        assertEquals(thumbnails.keys, ThumbPack(pack(thumbnails, "ids"), testDrivers).ids())
    }

    /** A container owns no photos. */
    @Test
    fun anEmptyPackIsLegal() {
        val pack = ThumbPack(pack(emptyMap(), "empty"), testDrivers)
        assertTrue(pack.unpack().isEmpty())
        assertTrue(pack.ids().isEmpty())
    }

    /**
     * Not required for correctness — blobs are immutable and a repack gets a fresh uuid either
     * way — but it makes two packs comparable when a person needs to know whether anything
     * actually changed.
     */
    @Test
    fun packingTheSameThumbnailsTwiceGivesTheSameBytes() {
        val thumbnails = (0 until 30).associate { Uuid.random() to jpeg(it) }
        val directory = temporaryDirectory("deterministic")
        val first = Path(directory, "first.db")
        val second = Path(directory, "second.db")
        thumbnails.packThumbnails(into = first, drivers = testDrivers)
        thumbnails.packThumbnails(into = second, drivers = testDrivers)

        assertContentEquals(first.readBytes(), second.readBytes())
    }

    /** §3's sizing: ~9 KB each, so a 100-photo album is ~0.9 MB and opens in one request. */
    @Test
    fun a100PhotoAlbumPacksToRoughlyAMegabyte() {
        val thumbnails = (0 until 100).associate { Uuid.random() to jpeg(it % 251) }
        val path = pack(thumbnails, "size")
        val size = SystemFileSystem.metadataOrNull(path)?.size ?: 0

        // 100 × 8.7 KB = 870 KB of payload; the envelope must not be a meaningful share.
        assertTrue(size > 870_000, "pack is $size bytes")
        assertTrue(size < 1_100_000, "pack is $size bytes")
    }
}
