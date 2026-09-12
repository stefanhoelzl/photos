package net.stho.photos.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.stho.photos.catalog.ObjectId

/**
 * The ladder, the worker roles and the retry policy, with no zone and no filesystem.
 *
 * This is why the scheduler lives in `state/` rather than in an adapter: the order things are
 * fetched in is the part that can be wrong, and here a test can hold every fetch open and look
 * at exactly which ones started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheQueueTest {

    // The workers run on `backgroundScope`, which `runTest` tears down for us -- so there is
    // nothing here to close by hand, and a worker looping for ever cannot hang the suite.

    // --------------------------------------------------------------------------- the ladder

    @Test
    fun theOpenPhotoOutranksEverythingAlreadyQueued() = runTest {
        val store = FakeStore()
        val queue = queue(store, this)

        queue.sweepPacks(listOf(small("sweep")))
        queue.openAlbum(listOf(small("album")))
        runCurrent()
        queue.viewing(open = listOf(small("open")))
        runCurrent()

        assertTrue("open" in store.started, "the open photo starts at once")
    }

    @Test
    fun tiersDrainInLadderOrder() = runTest {
        val store = FakeStore(block = false)
        // One worker of each kind would race; a single small worker makes the order observable.
        val queue = queue(store, this, smallWorkers = 1)

        queue.sweepPacks(listOf(small("sweep")))
        queue.openAlbum(listOf(small("album")))
        queue.visiblePacks(listOf(small("visible")))
        runCurrent()

        val order = store.started.filter { it in setOf("visible", "album", "sweep") }
        assertEquals(listOf("visible", "album", "sweep"), order, "tier 2, then 3, then 4")
    }

    @Test
    fun anExplicitRequestIsLastButNotForgotten() = runTest {
        val store = FakeStore(block = false)
        val queue = queue(store, this, smallWorkers = 1)
        val album = Uuid.random()

        queue.request(album, listOf(small("wanted", album)))
        queue.visiblePacks(listOf(small("visible")))
        runCurrent()

        assertEquals(listOf("visible", "wanted"), store.started, "the view goes first")
        assertTrue("wanted" in store.fetched, "and the request still completes")
    }

    // ---------------------------------------------------------------------- the worker roles

    @Test
    fun fourLargeBlobsCannotOccupyEveryWorker() = runTest {
        val store = FakeStore()
        val queue = queue(store, this)

        // The measured hazard: one album holding seven blobs >= 4 MiB, all queued at once.
        queue.openAlbum((1..7).map { large("video$it") })
        runCurrent()

        assertEquals(1, store.started.size, "only `general` may hold a large blob")
        assertEquals("video1", store.started.single())
    }

    @Test
    fun theSmallPoolKeepsWorkingWhileALargeBlobDownloads() = runTest {
        val store = FakeStore()
        val queue = queue(store, this)

        queue.openAlbum(listOf(large("video")) + (1..6).map { small("photo$it") })
        runCurrent()

        // general takes the video; the four small workers take four stills. The immediate
        // worker stays out of it, which is exactly what reserving it means.
        assertTrue("video" in store.started)
        assertEquals(4, store.started.count { it.startsWith("photo") }, "the small pool is free")
    }

    @Test
    fun theImmediateWorkerIsReservedAndDoesNotBorrow() = runTest {
        val store = FakeStore()
        val queue = queue(store, this, smallWorkers = 1)

        queue.sweepPacks(listOf(small("a"), small("b"), small("c")))
        runCurrent()

        // One small worker plus one general worker. The immediate worker idles rather than
        // taking a third: it exists so that a tap is never queued behind anything.
        assertEquals(2, store.started.size, "the reserved worker does not help with tier 4")
    }

    @Test
    fun aLargeOpenPhotoStillStartsAtOnce() = runTest {
        val store = FakeStore()
        val queue = queue(store, this)

        queue.openAlbum((1..3).map { large("bulk$it") })
        runCurrent()
        queue.viewing(open = listOf(large("tapped")))
        runCurrent()

        assertTrue("tapped" in store.started, "tier 0 has a worker of its own, whatever its size")
    }

    // ---------------------------------------------------------------------- cancel vs re-order

    @Test
    fun swipingPastAVideoAbandonsIt() = runTest {
        val store = FakeStore()
        val queue = queue(store, this)

        queue.viewing(open = listOf(large("first")))
        runCurrent()
        assertTrue("first" in store.started)

        queue.viewing(open = listOf(large("second")))
        runCurrent()

        assertTrue(store.cancelled.contains("first"), "a swiped-past video is dropped")
        assertTrue("second" in store.started)
    }

    @Test
    fun anOrdinaryFetchIsNeverCancelled() = runTest {
        val store = FakeStore()
        val queue = queue(store, this)

        queue.openAlbum(listOf(small("bulk")))
        runCurrent()
        queue.viewing(open = listOf(small("open")))
        runCurrent()

        assertTrue(store.cancelled.isEmpty(), "re-ordering never throws away work in flight")
    }

    @Test
    fun leavingTheAlbumStopsItsPendingWork() = runTest {
        val store = FakeStore()
        val queue = queue(store, this, smallWorkers = 1)

        queue.openAlbum(listOf(small("a"), small("b"), small("c")))
        runCurrent()
        queue.leaveAlbum()
        runCurrent()
        store.release()
        runCurrent()

        assertTrue(store.fetched.size < 3, "what had not started does not start")
    }

    @Test
    fun anExplicitRequestSurvivesLeavingTheAlbum() = runTest {
        val store = FakeStore(block = false)
        val queue = queue(store, this)
        val album = Uuid.random()

        queue.request(album, listOf(small("wanted", album)))
        queue.leaveAlbum()
        runCurrent()

        assertTrue("wanted" in store.fetched, "a request is not browsing, and is not transient")
    }

    // ------------------------------------------------------------------------------ failures

    @Test
    fun aBlobThatWillNotDownloadIsGivenUpOnRatherThanRetriedForEver() = runTest {
        val store = FakeStore(block = false, failing = setOf("broken"))
        val queue = queue(store, this)

        queue.openAlbum(listOf(small("broken"), small("fine")))
        runCurrent()

        assertEquals(3, store.attempts["broken"], "three attempts, then skipped for the session")
        assertTrue("fine" in store.fetched, "and the rest of the album still arrives")
        assertFalse("broken".blob() in queue.held.value)
    }

    @Test
    fun aFailedBlobIsNotQueuedAgainThisSession() = runTest {
        val store = FakeStore(block = false, failing = setOf("broken"))
        val queue = queue(store, this)

        queue.openAlbum(listOf(small("broken")))
        runCurrent()
        queue.openAlbum(listOf(small("broken")))
        runCurrent()

        assertEquals(3, store.attempts["broken"], "re-queuing does not restart the retries")
    }

    // -------------------------------------------------------------------------- what it holds

    @Test
    fun heldGrowsAsBlobsLandAndPulsingFollowsOnlyWhatIsMoving() = runTest {
        val store = FakeStore()
        val queue = queue(store, this)

        queue.viewing(open = listOf(small("one")))
        runCurrent()
        assertEquals(setOf("one"), queue.active.value.map { it.toString().trimHex() }.toSet())
        assertTrue(queue.held.value.isEmpty(), "nothing is held until it lands")

        store.release()
        runCurrent()
        assertTrue(queue.active.value.isEmpty(), "and nothing pulses once it has")
        assertEquals(1, queue.held.value.size)
    }

    @Test
    fun clearingAnAlbumStopsItAndRemovesWhatLanded() = runTest {
        val store = FakeStore(block = false)
        val queue = queue(store, this)
        val album = Uuid.random()
        val blobs = listOf(small("a", album), small("b", album))

        queue.request(album, blobs)
        runCurrent()
        assertEquals(2, queue.held.value.size)

        queue.clear(album, blobs)
        runCurrent()

        assertTrue(queue.held.value.isEmpty(), "the bytes are gone")
        assertFalse(album in queue.wanted.value, "and so is the intent")
    }

    @Test
    fun clearingAnAlbumLeavesItsPackAlone() = runTest {
        val store = FakeStore(block = false)
        val queue = queue(store, this)
        val album = Uuid.random()
        val pack = BlobRef("pack".blob(), 400L * 1024, album, BlobKind.Pack)
        val image = small("image", album)

        queue.request(album, listOf(pack, image))
        runCurrent()
        queue.clear(album, listOf(pack, image))
        runCurrent()

        // A pack is always kept, so every grid still opens instantly and offline (§6).
        assertTrue(store.has("pack".blob()), "the pack survives a clear")
        assertFalse(store.has("image".blob()), "the image does not")
    }

    @Test
    fun alreadyHeldBlobsAreNotFetchedAgain() = runTest {
        val store = FakeStore(block = false)
        store.disk += "already".blob()
        val queue = queue(store, this)

        queue.openAlbum(listOf(small("already"), small("missing")))
        runCurrent()

        assertEquals(listOf("missing"), store.started)
    }

    // -------------------------------------------------------------------------------- helpers

    private fun queue(store: FakeStore, scope: TestScope, smallWorkers: Int = 4): CacheQueue {
        return CacheQueue(
            store = store,
            scope = scope.backgroundScope,
            smallWorkers = smallWorkers,
            backoff = { },
        )
    }

    private val defaultAlbum = Uuid.random()

    private fun small(name: String, album: Uuid = defaultAlbum) =
        BlobRef(name.blob(), bytes = 400L * 1024, album = album)

    private fun large(name: String, album: Uuid = defaultAlbum) =
        BlobRef(name.blob(), bytes = 20L * 1024 * 1024, album = album)
}

/**
 * Object ids are 64 hex characters; the tests want readable names, so a name is encoded into
 * one and read back out again. [trimHex] is the inverse, which is what lets an assertion name
 * the blob it means.
 */
private fun String.blob(): ObjectId = ObjectId.parse(hex())!!

private fun String.hex(): String =
    encodeToByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        .padEnd(64, '0').take(64)

private fun String.trimHex(): String {
    val bytes = chunked(2).takeWhile { it != "00" }.map { it.toInt(16).toByte() }.toByteArray()
    return bytes.decodeToString()
}

/**
 * A store that holds every fetch open until released, so a test can see what *started* rather
 * than only what finished — which is the whole question the ladder answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class FakeStore(
    private val block: Boolean = true,
    private val failing: Set<String> = emptySet(),
) : BlobStore {
    val disk = mutableSetOf<ObjectId>()
    val started = mutableListOf<String>()
    val fetched = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val attempts = mutableMapOf<String, Int>()

    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val lock = Mutex()

    override fun has(id: ObjectId): Boolean = id in disk

    override fun present(): Set<ObjectId> = disk.toSet()

    override fun delete(id: ObjectId) {
        disk -= id
    }

    override suspend fun fetch(id: ObjectId) {
        val name = id.toString().trimHex()
        lock.withLock {
            started += name
            attempts[name] = (attempts[name] ?: 0) + 1
        }
        if (name in failing) throw IllegalStateException("500 from the zone")
        if (block) {
            val gate = lock.withLock { gates.getOrPut(name) { CompletableDeferred() } }
            try {
                gate.await()
            } catch (stop: kotlinx.coroutines.CancellationException) {
                lock.withLock { cancelled += name }
                throw stop
            }
        }
        disk += id
        lock.withLock { fetched += name }
    }

    /** Let every held fetch complete. */
    fun release() {
        gates.values.forEach { it.complete(Unit) }
    }
}
