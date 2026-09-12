package net.stho.photos.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album
import net.stho.photos.catalog.ObjectId

/**
 * The four readings of the strip, and the container rollup underneath them.
 *
 * The rollup is the part that can be wrong: §2 makes a container hold no blobs of its own, so
 * without it every container row would read empty and asking one to download would do nothing.
 */
class AlbumCacheTest {

    @Test
    fun anAlbumWithNothingOnDiskIsGrey() {
        val album = album("Iceland")
        val state = cacheByAlbum(
            albums = listOf(album),
            blobs = mapOf(album.id to listOf(blob("a", 100), blob("b", 100))),
            held = emptySet(),
            active = emptySet(),
        ).getValue(album.id)

        assertTrue(state.empty)
        assertFalse(state.complete)
        assertEquals(0f, state.fraction)
    }

    @Test
    fun aPartlyHeldAlbumFillsInProportionToBytesNotCount() {
        val album = album("Coast Road")
        // One 900-byte video and three 100-byte stills: by count this is 75% held, by bytes 25%.
        val state = cacheByAlbum(
            albums = listOf(album),
            blobs = mapOf(album.id to listOf(blob("v", 900), blob("a", 100), blob("b", 100), blob("c", 100))),
            held = setOf(id("a"), id("b"), id("c")),
            active = emptySet(),
        ).getValue(album.id)

        assertEquals(0.25f, state.fraction, "bytes, so one video does not read as nearly done")
        assertFalse(state.complete)
    }

    @Test
    fun everyBlobPresentIsComplete() {
        val album = album("Alps")
        val state = cacheByAlbum(
            albums = listOf(album),
            blobs = mapOf(album.id to listOf(blob("a", 100))),
            held = setOf(id("a")),
            active = emptySet(),
        ).getValue(album.id)

        assertTrue(state.complete, "full green")
    }

    @Test
    fun onlyAnAlbumWithAWorkerOnItPulses() {
        val busy = album("Busy")
        val idle = album("Idle")
        val state = cacheByAlbum(
            albums = listOf(busy, idle),
            blobs = mapOf(busy.id to listOf(blob("a", 100)), idle.id to listOf(blob("b", 100))),
            held = emptySet(),
            active = setOf(id("a")),
        )

        assertTrue(state.getValue(busy.id).moving)
        assertFalse(state.getValue(idle.id).moving, "queued is not moving")
    }

    @Test
    fun anEmptyAlbumIsNeverComplete() {
        // §10: emptying a directory leaves an album with zero photos, and it must render.
        val album = album("Emptied")
        val state = cacheByAlbum(listOf(album), emptyMap(), emptySet(), emptySet()).getValue(album.id)

        assertFalse(state.complete, "nothing to hold is not the same as holding everything")
        assertEquals(0f, state.fraction)
    }

    @Test
    fun theThumbnailPackDoesNotCountTowardsTheStrip() {
        // Every album's pack is fetched unconditionally (§6), so counting it would leave a
        // sliver of blue on all 288 rows and grey would never mean anything.
        val album = album("Iceland")
        val state = cacheByAlbum(
            albums = listOf(album),
            blobs = mapOf(
                album.id to listOf(
                    BlobRef(id("pack"), 400, album.id, BlobKind.Pack),
                    BlobRef(id("image"), 600, album.id, BlobKind.Media),
                ),
            ),
            held = setOf(id("pack")),
            active = emptySet(),
        ).getValue(album.id)

        assertTrue(state.empty, "holding only the pack is holding nothing the person asked for")
        assertEquals(600L, state.totalBytes, "and the pack is not part of what there is to fetch")
    }

    @Test
    fun anAlbumWhoseMediaIsAllHeldIsCompleteEvenWithoutCountingItsPack() {
        val album = album("Alps")
        val state = cacheByAlbum(
            albums = listOf(album),
            blobs = mapOf(
                album.id to listOf(
                    BlobRef(id("pack"), 400, album.id, BlobKind.Pack),
                    BlobRef(id("image"), 600, album.id, BlobKind.Media),
                ),
            ),
            held = setOf(id("image")),
            active = emptySet(),
        ).getValue(album.id)

        assertTrue(state.complete)
    }

    // ------------------------------------------------------------------------- the rollup

    @Test
    fun aContainerAggregatesItsChildren() {
        val parent = album("Iceland")
        val one = album("Day one", parent = parent.id)
        val two = album("Day two", parent = parent.id)
        val state = cacheByAlbum(
            albums = listOf(parent, one, two),
            blobs = mapOf(one.id to listOf(blob("a", 100)), two.id to listOf(blob("b", 300))),
            held = setOf(id("a")),
            active = emptySet(),
        )

        val container = state.getValue(parent.id)
        assertEquals(400L, container.totalBytes, "a container owns no blobs but its children do")
        assertEquals(100L, container.heldBytes)
        assertEquals(0.25f, container.fraction)
    }

    @Test
    fun aContainerPulsesWhenAnyDescendantIsMoving() {
        val parent = album("Iceland")
        val child = album("Day one", parent = parent.id)
        val grandchild = album("Morning", parent = child.id)
        val state = cacheByAlbum(
            albums = listOf(parent, child, grandchild),
            blobs = mapOf(grandchild.id to listOf(blob("a", 100))),
            held = emptySet(),
            active = setOf(id("a")),
        )

        assertTrue(state.getValue(parent.id).moving, "the rollup is not one level deep")
        assertEquals(100L, state.getValue(parent.id).totalBytes)
    }

    @Test
    fun aContainerIsCompleteOnlyWhenEveryDescendantIs() {
        val parent = album("Iceland")
        val one = album("Day one", parent = parent.id)
        val two = album("Day two", parent = parent.id)
        val state = cacheByAlbum(
            albums = listOf(parent, one, two),
            blobs = mapOf(one.id to listOf(blob("a", 100)), two.id to listOf(blob("b", 100))),
            held = setOf(id("a")),
            active = emptySet(),
        )

        assertFalse(state.getValue(parent.id).complete)
        assertTrue(state.getValue(one.id).complete, "the child that is done still reads done")
    }

    @Test
    fun aShardClaimingItsOwnAncestorAsParentDoesNotHang() {
        // §4 reports rather than resolves: a cycle can only come from a shard that lies, and
        // finding that out via a stack overflow would be a poor trade.
        val a = Uuid.random()
        val b = Uuid.random()
        val albums = listOf(
            album("A", id = a, parent = b),
            album("B", id = b, parent = a),
        )
        val state = cacheByAlbum(albums, emptyMap(), emptySet(), emptySet())

        assertEquals(2, state.size)
    }

    // ------------------------------------------------------------------------ the controls

    @Test
    fun theRowOffersExactlyWhatApplies() {
        val nothing = AlbumCache(0, 400, moving = false)
        val partial = AlbumCache(100, 400, moving = false)
        val running = AlbumCache(100, 400, moving = true)
        val done = AlbumCache(400, 400, moving = false)

        assertEquals(listOf(CacheAction.Download), actionsFor(nothing, wanted = false))
        assertEquals(listOf(CacheAction.Download, CacheAction.Clear), actionsFor(partial, wanted = false))
        assertEquals(listOf(CacheAction.Pause, CacheAction.Clear), actionsFor(running, wanted = true))
        assertEquals(listOf(CacheAction.Clear), actionsFor(done, wanted = false))
    }

    @Test
    fun anAlbumWithNothingToFetchOffersNoControls() {
        // Found by driving the real app: §10's emptied album sat in the list offering a
        // Download that had nothing to download.
        assertEquals(emptyList(), actionsFor(AlbumCache(0, 0, moving = false), wanted = false))
    }

    @Test
    fun aCompleteAlbumOffersOnlyClearEvenIfItWasRequested() {
        val done = AlbumCache(400, 400, moving = false)
        assertEquals(listOf(CacheAction.Clear), actionsFor(done, wanted = true))
    }

    // ------------------------------------------------------------------------------ helpers

    private fun album(name: String, id: Uuid = Uuid.random(), parent: Uuid? = null) = Album(
        id = id,
        name = name,
        nameFolded = name.lowercase(),
        parent = parent,
        photoCount = 0,
        dateMin = null,
        dateMax = null,
        latitude = null,
        longitude = null,
        coverPhotoId = null,
        thumbsId = null,
    )

    private fun id(name: String): ObjectId =
        ObjectId.parse(
            name.encodeToByteArray()
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                .padEnd(64, '0').take(64),
        )!!

    private fun blob(name: String, bytes: Long) = BlobRef(id(name), bytes, album = Uuid.random())
}
