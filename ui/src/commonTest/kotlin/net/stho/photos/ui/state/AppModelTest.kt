package net.stho.photos.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.AfterTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.stho.photos.catalog.Album
import net.stho.photos.model.PhotoRow

/**
 * The state tier, with no SQLite, no cache directory and no zone.
 *
 * This is the whole reason `state/` imports no UI framework: what can be wrong here is the
 * part a test can reach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModelTest {

    @Test
    fun theAlbumListIsWhateverTheSortSays() = runTest {
        val model = model(this, albums = listOf(album("Zeta", 2020), album("Alpha", 2024)))
        model.start()

        assertEquals(listOf("Alpha", "Zeta"), model.names(), "date, newest first is the default")
        model.cycleSort()
        assertEquals(listOf("Zeta", "Alpha"), model.names(), "then oldest first")
        model.cycleSort()
        assertEquals(listOf("Alpha", "Zeta"), model.names(), "then by name")
        model.cycleSort()
        assertEquals(AlbumSort.DateNewest, model.state.value.sort, "and back round")
    }

    /** §3: albums with no dated photo at all collect at one end, whichever way dates run. */
    @Test
    fun undatedAlbumsCollectAtTheEnd() = runTest {
        val model = model(this, albums = listOf(album("Undated", null), album("Dated", 2024)))
        model.start()

        assertEquals(listOf("Dated", "Undated"), model.names())
        model.cycleSort()
        assertEquals(listOf("Dated", "Undated"), model.names())
    }

    @Test
    fun theSubtitleNamesTheSortSoNoMenuHasTo() = runTest {
        val model = model(this, albums = listOf(album("One", 2024)))
        model.start()

        assertEquals("1 albums · sorted by date, newest first", model.state.value.subtitle)
        model.cycleSort()
        assertEquals("1 albums · sorted by date, oldest first", model.state.value.subtitle)
    }

    @Test
    fun aFailedSyncNeverEmptiesTheScreen() = runTest {
        val notice = Notice.error("Sync failed: 403 Forbidden", "Log out and check the password.")
        val model = model(this, albums = listOf(album("Iceland", 2024)), outcome = SyncOutcome.Failed(notice))
        model.start()

        assertEquals(listOf("Iceland"), model.names(), "the catalog is local; a failure must not hide it")
        assertEquals(notice, model.state.value.notice)
        assertTrue(model.state.value.sync is SyncStatus.Failed, "and Settings keeps the record")
    }

    @Test
    fun dismissingTheToastLeavesTheRecordBehind() = runTest {
        val model = model(this, outcome = SyncOutcome.Failed(Notice.error("Sync failed: 500", "Retry later.")))
        model.start()
        model.dismissNotice()

        assertNull(model.state.value.notice, "the toast is transient")
        assertTrue(model.state.value.sync is SyncStatus.Failed, "the sync row is the durable record")
    }

    /**
     * A first sync fetches every shard in the zone and takes tens of seconds (§4).
     * "No albums yet" during it is untrue in the worst way: it reads as an empty library.
     */
    @Test
    fun aFirstSyncSaysItIsLoadingRatherThanThatThereIsNothing() = runTest {
        val syncer = SteppingSyncer()
        val model = AppModel(FakeCatalog(emptyList()), syncer, FakeThumbnails(), FakePreviews(), FakeVideos(), own(this))
        model.start()

        syncer.report(fetched = 12, total = 288)
        assertTrue(model.state.value.loading, "nothing to show, and something on its way")
        assertEquals(SyncStatus.Running(12, 288), model.state.value.sync)

        syncer.finish()
        assertFalse(model.state.value.loading, "the sync is over; an empty zone is now the truth")
    }

    /** Once there are albums, a later sync must not blank the screen. */
    @Test
    fun aLaterSyncNeverHidesTheCatalogItAlreadyHas() = runTest {
        val syncer = SteppingSyncer()
        val model = AppModel(FakeCatalog(listOf(album("Iceland", 2024))), syncer, FakeThumbnails(), FakePreviews(), FakeVideos(), own(this))
        model.start()

        syncer.report(fetched = 1, total = 288)
        assertFalse(model.state.value.loading)
        assertEquals(listOf("Iceland"), model.names())
    }

    @Test
    fun theSubtitleNamesThePackQueueWhileItDrains() = runTest {
        val thumbs = FakeThumbnails()
        val model = AppModel(FakeCatalog(listOf(album("Iceland", 2024))), FakeSyncer(SyncOutcome.Succeeded(1, 1)), thumbs, FakePreviews(), FakeVideos(), own(this))
        model.start()
        thumbs.outstanding.value = 254
        thumbs.arrivals.value = 34

        assertEquals("1 albums · fetching thumbnails 34/288", model.state.value.subtitle)
    }

    @Test
    fun backNeverLeavesTheRoot() = runTest {
        val model = model(this)
        model.start()
        model.back()

        assertEquals(Screen.Albums, model.state.value.screen)
    }

    @Test
    fun searchingFiltersAndSayingSoInTheSubtitle() = runTest {
        val model = model(this, albums = listOf(album("Iceland", 2024), album("Alps", 2023)))
        model.start()
        model.search("ice")

        assertEquals(listOf("Iceland"), model.names())
        assertEquals("1 matching", model.state.value.subtitle)
    }


    // ------------------------------------------------------------------------------ fixtures

    private fun AppModel.names(): List<String> = state.value.albums.map { it.name }

    /**
     * A scope of the test's own, unconfined so that everything `start` launches has already run
     * by the time it returns — the sync, and the reload each pack arrival triggers.
     *
     * Not the test's own scope: the model collects pack arrivals for as long as the app lives,
     * and a collector that never completes would hang `runTest` at the end. Cancelled after
     * each test instead.
     */
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stopModels(): Unit = scopes.forEach(CoroutineScope::cancel)

    private fun own(scope: TestScope): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler)).also { scopes += it }

    private fun model(
        scope: TestScope,
        albums: List<Album> = emptyList(),
        outcome: SyncOutcome = SyncOutcome.Succeeded(albums.size, 0),
    ): AppModel {
        val own = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler))
        scopes += own
        return AppModel(FakeCatalog(albums), FakeSyncer(outcome), FakeThumbnails(), FakePreviews(), FakeVideos(), own)
    }

    private class FakeCatalog(private val albums: List<Album>) : Catalog {
        override fun albums(under: Uuid?): List<Album> = if (under == null) albums else emptyList()
        override fun search(text: String): List<Album> =
            albums.filter { it.nameFolded.contains(text.lowercase()) }
        override fun photos(inAlbum: Uuid): List<PhotoRow> = emptyList()
        override fun album(id: Uuid): Album? = albums.firstOrNull { it.id == id }
        override fun totals() = Totals(albums.size, albums.sumOf { it.photoCount })
    }

    /**
     * Packs that are always present and always empty.
     *
     * Enough for the state tier: what it decides is *whether* a pack has landed and what to
     * show when it has not, never what a thumbnail looks like.
     */
    private class FakeThumbnails(private val present: Boolean = true) : Thumbnails {
        override val arrivals = MutableStateFlow(0)
        override val outstanding = MutableStateFlow(0)
        override fun has(album: Album) = present
        override fun cover(album: Album): ByteArray? = null
        override fun all(album: Album): Map<Uuid, ByteArray> = emptyMap()
        override fun prioritise(album: Album) = Unit
    }


    /** A sync that reports progress on demand and finishes when the test says so. */
    private class SteppingSyncer : Syncer {
        private var progress: ((Int, Int) -> Unit)? = null
        private val done = CompletableDeferred<SyncOutcome>()

        override suspend fun sync(onProgress: (Int, Int) -> Unit): SyncOutcome {
            progress = onProgress
            return done.await()
        }

        fun report(fetched: Int, total: Int) = requireNotNull(progress).invoke(fetched, total)

        fun finish() = done.complete(SyncOutcome.Succeeded(0, 0)).let { }
    }

    private class FakeSyncer(private val outcome: SyncOutcome) : Syncer {
        override suspend fun sync(onProgress: (Int, Int) -> Unit): SyncOutcome = outcome
    }

    private fun album(name: String, year: Int?) = Album(
        id = Uuid.random(),
        name = name,
        nameFolded = name.lowercase(),
        parent = null,
        photoCount = 1,
        dateMin = year?.let { Instant.parse("$it-01-01T00:00:00Z") },
        dateMax = year?.let { Instant.parse("$it-12-31T00:00:00Z") },
        latitude = null,
        longitude = null,
        coverPhotoId = null,
        thumbsId = null,
    )

    /** No transcode ever arrives, so the poster is what the viewer keeps showing. */
    private class FakeVideos : Videos {
        override suspend fun localFile(photo: PhotoRow): String? = null
    }

    /** No previews: enough for the state tier, which decides *when* to ask, not what comes back. */
    private class FakePreviews : Previews {
        override fun cached(photo: PhotoRow): Preview? = null
        override suspend fun load(photo: PhotoRow): Preview? = null
        override fun prefetch(photos: List<PhotoRow>, index: Int) = Unit
        override fun cancelPrefetch() = Unit
    }
}
