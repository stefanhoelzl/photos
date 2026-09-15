package net.stho.photos.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
        assertEquals(AlbumSort.DateNewest, model.state.value.sort, "and back: a toggle, not a cycle")
        assertEquals(listOf("Alpha", "Zeta"), model.names())
    }

    /**
     * An album spanning the whole library used to top both date orders: newest first read its
     * latest date and oldest first its earliest, so the first row never moved when the icon was
     * tapped. Both orders now read the latest date, and oldest first is the exact reverse.
     */
    @Test
    fun oldestFirstIsNewestFirstReversedEvenForAnAlbumSpanningEveryYear() = runTest {
        val everything = album("Everything", 2001).copy(dateMax = Instant.parse("2024-12-31T00:00:00Z"))
        val model = model(this, albums = listOf(everything, album("Iceland", 2019), album("Rome", 2010)))
        model.start()

        assertEquals(listOf("Everything", "Iceland", "Rome"), model.names())
        model.cycleSort()
        assertEquals(listOf("Rome", "Iceland", "Everything"), model.names())
    }

    /**
     * A container owns no photos, so read straight from the catalog it said "0 photos" and sorted
     * as undated — at the far end of both date orders. It carries its descendants' instead.
     */
    @Test
    fun aContainerSortsByItsNewestDescendantAndCountsWhatItHolds() = runTest {
        val trips = album("Trips", null).copy(photoCount = 0)
        val iceland = album("Iceland", 2024).copy(parent = trips.id)
        val rome = album("Rome", 2010).copy(parent = trips.id)
        val model = model(this, albums = listOf(album("Garden", 2019), trips, iceland, rome))
        model.start()

        assertEquals(listOf("Trips", "Iceland", "Rome", "Garden"), model.names(), "Trips holds 2024, and heads its albums")
        model.cycleSort()
        assertEquals(listOf("Garden", "Trips", "Rome", "Iceland"), model.names(), "last when oldest comes first, its albums turned too")
        assertEquals("2 albums · 2 photos", model.state.value.contentsOf(trips))
        assertEquals("1 photos", model.state.value.contentsOf(iceland))
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

        assertEquals("1 albums · newest first", model.state.value.subtitle)
        model.cycleSort()
        assertEquals("1 albums · oldest first", model.state.value.subtitle)
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
        val model = AppModel(FakeCatalog(emptyList()), syncer, FakeThumbnails(), FakePreviews(), FakeVideos(), own(this).let { s -> idleQueue(s) }, own(this))
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
        val model = AppModel(FakeCatalog(listOf(album("Iceland", 2024))), syncer, FakeThumbnails(), FakePreviews(), FakeVideos(), own(this).let { s -> idleQueue(s) }, own(this))
        model.start()

        syncer.report(fetched = 1, total = 288)
        assertFalse(model.state.value.loading)
        assertEquals(listOf("Iceland"), model.names())
    }

    @Test
    fun theSubtitleNamesThePackQueueWhileItDrains() = runTest {
        val thumbs = FakeThumbnails()
        val model = AppModel(FakeCatalog(listOf(album("Iceland", 2024))), FakeSyncer(SyncOutcome.Succeeded(1, 1)), thumbs, FakePreviews(), FakeVideos(), own(this).let { s -> idleQueue(s) }, own(this))
        model.start()
        thumbs.outstanding.value = 254
        thumbs.arrivals.value = 34

        assertEquals("1 albums · newest first · thumbnails 34/288", model.state.value.subtitle)
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

    // ---------------------------------------------------------------------- containers on the list

    /**
     * Every level is on the list (§6): a container heads its sub-albums, indented one step per
     * level, and a line closes each group. Siblings keep one date order, album or container.
     */
    @Test
    fun everySubAlbumIsListedIndentedUnderItsContainer() = runTest {
        val norway = container("Norway")
        val lofoten = container("Lofoten", norway)
        val reine = album("Reine", 2023).copy(parent = lofoten.id)
        val oslo = album("Oslo", 2021).copy(parent = norway.id)
        val model = model(this, albums = listOf(album("Paris", 2022), norway, lofoten, reine, oslo))
        model.start()

        assertEquals(
            listOf("Norway ▾", "  Lofoten ▾", "    Reine", "  — Lofoten", "  Oslo", "— Norway", "Paris"),
            model.state.value.lines(),
        )
        assertEquals(listOf(norway.id, lofoten.id), model.state.value.rowOf(reine).ancestors, "what pins above it")
        assertEquals("2 albums · 2 photos", model.state.value.rowOf(norway).contents)
    }

    /** A header opens its container, whose screen lists everything beneath it from the left edge. */
    @Test
    fun aContainersScreenListsItsWholeSubtree() = runTest {
        val norway = container("Norway")
        val lofoten = container("Lofoten", norway)
        val model = model(
            this,
            albums = listOf(norway, lofoten, album("Reine", 2023).copy(parent = lofoten.id), album("Oslo", 2021).copy(parent = norway.id)),
        )
        model.start()
        model.open(norway)

        assertEquals(Screen.Container(norway.id, "Norway"), model.state.value.screen)
        assertEquals(listOf("Lofoten ▾", "  Reine", "— Lofoten", "Oslo"), model.state.value.lines())
    }

    /**
     * A search keeps each match under its containers' headers and hides everything else. A header
     * that only holds a match counts what the search kept, not the whole container.
     */
    @Test
    fun aSearchKeepsEachMatchUnderItsContainersAndCountsOnlyWhatItKept() = runTest {
        val library = Library()
        val model = model(this, albums = library.all)
        model.start()
        model.search("re")

        assertEquals(
            listOf("Iceland ▾", "  Reykjavík", "— Iceland", "Norway ▾", "  Lofoten ▾", "    Reine", "  — Lofoten", "— Norway"),
            model.state.value.lines(),
        )
        assertEquals("1 of 2 albums · 40 photos", model.state.value.rowOf(library.iceland).contents)
        assertEquals("1 of 2 albums · 80 photos", model.state.value.rowOf(library.norway).contents)
        assertEquals("1 of 2 albums · 80 photos", model.state.value.rowOf(library.lofoten).contents)
        assertEquals("2 matching", model.state.value.subtitle)
    }

    @Test
    fun aMatchingContainerKeepsEverythingBeneathIt() = runTest {
        val library = Library()
        val model = model(this, albums = library.all)
        model.start()
        model.search("lof")

        assertEquals(
            listOf("Norway ▾", "  Lofoten ▾", "    Reine", "    Henningsvær", "  — Lofoten", "— Norway"),
            model.state.value.lines(),
        )
        assertEquals("2 albums · 120 photos", model.state.value.rowOf(library.lofoten).contents)
        assertEquals("1 of 2 albums · 120 photos", model.state.value.rowOf(library.norway).contents)
        assertEquals("1 matching", model.state.value.subtitle)
    }

    /**
     * Under a search a header's strip and actions describe what is on screen, as its count does:
     * downloading Iceland from a search for Reykjavík must not fetch Ring Road, which is hidden.
     */
    @Test
    fun aNarrowedHeadersStripAndActionsCoverOnlyItsMatches() = runTest {
        val library = Library()
        val blobs = library.all.filter { it.photoCount > 0 }.mapIndexed { i, album ->
            album.id to listOf(BlobRef(requireNotNull(net.stho.photos.catalog.ObjectId.parse(i.toString(16).padStart(64, '0'))), 1_000, album.id))
        }.toMap()
        val own = own(this)
        // Paused, so a request stays asked-for while the test looks: with nothing ever fetched
        // the queue would otherwise settle it the moment it was made.
        val paused = CoroutineScope(StandardTestDispatcher(testScheduler)).also { scopes += it }
        val catalog = object : Catalog by FakeCatalog(library.all) {
            override fun blobs(): Map<Uuid, List<BlobRef>> = blobs
        }
        val model = AppModel(
            catalog, FakeSyncer(SyncOutcome.Succeeded(0, 0)), FakeThumbnails(), FakePreviews(), FakeVideos(),
            idleQueue(paused), own,
        )
        model.start()
        model.search("re")

        val iceland = model.state.value.rowOf(library.iceland)
        assertEquals(listOf(library.reykjavik.id), iceland.covers)
        model.act(iceland, CacheAction.Download)
        assertEquals(setOf(library.reykjavik.id), model.state.value.wanted, "the match alone is asked for")
        assertEquals(listOf(CacheAction.Pause, CacheAction.Clear), model.state.value.actionsOf(iceland))

        val held = model.state.value.copy(
            cache = mapOf(
                library.reykjavik.id to AlbumCache(10, 100, moving = false),
                library.ringRoad.id to AlbumCache(50, 50, moving = false),
                library.iceland.id to AlbumCache(60, 150, moving = false),
            ),
        )
        assertEquals(AlbumCache(10, 100, moving = false), held.cacheOf(iceland), "Ring Road's bytes are not on this strip")

        model.search("")
        assertEquals(listOf(library.iceland.id), model.state.value.rowOf(library.iceland).covers, "without a search, the whole container")
    }


    // ------------------------------------------------------------------------------ fixtures

    @Test
    fun openingALivePhotoHandsTheViewerBothHalves() = runTest {
        val live = PhotoRow(
            id = Uuid.random(),
            filename = "IMG_0001.HEIC",
            mediaType = net.stho.photos.model.MediaType.LIVE_PHOTO,
        )
        val iceland = album("Iceland", 2024)
        val pair = LivePair("/cache/blobs/still", "/cache/blobs/video")
        val own = own(this)
        val model = AppModel(
            FakeCatalog(listOf(iceland), photos = listOf(live)),
            FakeSyncer(SyncOutcome.Succeeded(1, 1)), FakeThumbnails(), FakePreviews(),
            FakeVideos(pair), idleQueue(own), own,
        )
        model.start()
        model.open(iceland)
        model.openPhoto(0)

        assertEquals(pair, model.state.value.livePair)
        assertNull(model.state.value.videoPath, "a Live Photo is not a video")

        // Leaving the photo drops the pair, so the next one opened never flashes the last one's.
        model.back()
        assertNull(model.state.value.livePair)
    }

    /**
     * Opening a photo fetches it, whether or not its album's thumbnail pack has landed.
     *
     * The iOS suite found this: a scenario that opened a Live Photo straight after the first sync,
     * before the pack arrived, waited a minute for blobs that never downloaded. Opening an album
     * starts its images only once the pack is there, and the open photo's own top-tier fetch was
     * queued only by a swipe — so a photo opened first was fetched by nothing.
     */
    @Test
    fun openingAPhotoFetchesItEvenBeforeItsAlbumsPackHasLanded() = runTest {
        val blob = requireNotNull(net.stho.photos.catalog.ObjectId.parse("ab".repeat(32)))
        val still = PhotoRow(id = Uuid.random(), filename = "IMG_0001.HEIC", imageId = blob, bytes = 1_000)
        val iceland = album("Iceland", 2024)
        val fetched = mutableListOf<net.stho.photos.catalog.ObjectId>()
        val own = own(this)
        val queue = CacheQueue(
            store = object : BlobStore {
                override fun has(id: net.stho.photos.catalog.ObjectId) = id in fetched
                override suspend fun fetch(id: net.stho.photos.catalog.ObjectId) { fetched += id }
                override fun delete(id: net.stho.photos.catalog.ObjectId) = Unit
                override fun present() = fetched.toSet()
            },
            scope = own,
            backoff = { },
        )
        val catalog = object : Catalog by FakeCatalog(listOf(iceland), photos = listOf(still)) {
            override fun blobs(): Map<Uuid, List<BlobRef>> = mapOf(iceland.id to listOf(BlobRef(blob, 1_000, iceland.id)))
        }
        val model = AppModel(
            catalog, FakeSyncer(SyncOutcome.Succeeded(1, 1)),
            FakeThumbnails(present = false), FakePreviews(), FakeVideos(), queue, own,
        )
        model.start()
        model.open(iceland)
        model.openPhoto(0)

        assertEquals(listOf(blob), fetched, "the open photo's blob is fetched with no pack on disk")
    }

    /**
     * A swipe drags a neighbour into view before it settles, so the photos either side are decoded
     * too — and only those: a decoded frame is tens of megabytes, so a swipe drops the one it left.
     */
    @Test
    fun thePhotosEitherSideOfTheOpenOneAreDecodedForTheSwipe() = runTest {
        val photos = List(3) { PhotoRow(id = Uuid.random(), filename = "IMG_000$it.HEIC") }
        val iceland = album("Iceland", 2024)
        // A scheduler that runs launches when advanced, not on the spot: the model starts preview
        // loads from inside a state update, and an eager dispatcher lets that update overwrite them.
        val own = CoroutineScope(StandardTestDispatcher(testScheduler))
        val decoding = object : Previews {
            override fun cached(photo: PhotoRow): Preview? = null
            override suspend fun load(photo: PhotoRow): Preview = Preview(photo.id, NoPixels)
            override fun prefetch(photos: List<PhotoRow>, index: Int) = Unit
            override fun cancelPrefetch() = Unit
        }
        val model = AppModel(
            FakeCatalog(listOf(iceland), photos = photos), FakeSyncer(SyncOutcome.Succeeded(1, 3)),
            FakeThumbnails(), decoding, FakeVideos(), idleQueue(own), own,
        )
        model.start()
        advanceUntilIdle()
        model.open(iceland)
        model.openPhoto(1)
        advanceUntilIdle()

        assertEquals(photos[1].id, model.state.value.preview?.id)
        assertEquals(setOf(photos[0].id, photos[2].id), model.state.value.nearby.keys)

        model.showPhoto(2)
        advanceUntilIdle()
        // The photo two behind is dropped. The one now open stays: it is what the viewer draws for
        // the frames between the pager settling and the model's own preview landing.
        assertEquals(setOf(photos[1].id, photos[2].id), model.state.value.nearby.keys, "the photo two behind is dropped")
        own.cancel()
    }

    /**
     * Opening an album starts its images whether or not its thumbnail pack has landed.
     *
     * They used to wait for it, and the wait was built as "only if the pack is already there" with
     * nothing to try again when it arrived — so an album opened early never fetched its images.
     * No ordering needed that: the album's pack is queued as a visible pack, a tier above the
     * album's images, so the pack is still what the queue fetches first.
     */
    @Test
    fun openingAnAlbumFetchesItsImagesEvenBeforeItsPackHasLanded() = runTest {
        val pack = requireNotNull(net.stho.photos.catalog.ObjectId.parse("cd".repeat(32)))
        val image = requireNotNull(net.stho.photos.catalog.ObjectId.parse("ef".repeat(32)))
        val iceland = album("Iceland", 2024).copy(thumbsId = pack)
        val still = PhotoRow(id = Uuid.random(), filename = "IMG_0001.HEIC", imageId = image, bytes = 1_000)
        val fetched = mutableListOf<net.stho.photos.catalog.ObjectId>()
        val own = own(this)
        val queue = CacheQueue(
            store = object : BlobStore {
                override fun has(id: net.stho.photos.catalog.ObjectId) = id in fetched
                override suspend fun fetch(id: net.stho.photos.catalog.ObjectId) { fetched += id }
                override fun delete(id: net.stho.photos.catalog.ObjectId) = Unit
                override fun present() = fetched.toSet()
            },
            scope = own,
            backoff = { },
        )
        val catalog = object : Catalog by FakeCatalog(listOf(iceland), photos = listOf(still)) {
            override fun blobs(): Map<Uuid, List<BlobRef>> = mapOf(iceland.id to listOf(BlobRef(image, 1_000, iceland.id)))
        }
        val model = AppModel(
            catalog, FakeSyncer(SyncOutcome.Succeeded(1, 1)),
            FakeThumbnails(present = false), FakePreviews(), FakeVideos(), queue, own,
        )
        model.start()
        model.open(iceland)

        assertEquals(listOf(pack, image), fetched, "the pack first, then the album's image -- with no pack on disk yet")
    }

    // ------------------------------------------------------------------------------ the map

    /**
     * §6: the album list's map is flat. A container's centroid lands between its albums, so it
     * gets no pin; an album with no location is counted, and the subtitle says how many are missing.
     */
    @Test
    fun theListsMapPlacesEveryLocatedAlbumThatOwnsPhotosWhateverTheLevel() = runTest {
        val trips = album("Trips", null).copy(photoCount = 0, latitude = 53.0, longitude = -4.7)
        val iceland = located("Iceland", 64.14, -21.94).copy(parent = trips.id)
        val rome = located("Rome", 41.9, 12.5).copy(parent = trips.id)
        val model = model(this, albums = listOf(trips, iceland, rome, album("Garden", 2019)))
        model.start()
        model.toggleMap()

        val ui = model.state.value
        assertTrue(ui.showingMap)
        assertEquals(setOf("Iceland", "Rome"), ui.pinNames())
        assertEquals("2 of 3 albums on the map", ui.subtitle, "Garden has no location; Trips owns no photos")
    }

    @Test
    fun aSearchNarrowsTheMapAsItNarrowsTheList() = runTest {
        val model = model(
            this,
            albums = listOf(located("Iceland", 64.14, -21.94), located("Alps", 46.5, 10.0), album("Ice Cave", 2020)),
        )
        model.start()
        model.search("ice")
        model.toggleMap()

        assertEquals(setOf("Iceland"), model.state.value.pinNames())
        assertEquals("1 of 2 matching on the map", model.state.value.subtitle)
    }

    /** A container's map opens on its own albums; the root's on the whole library. */
    @Test
    fun aMapFirstFramesItsOwnLevelsAlbums() = runTest {
        val trips = album("Trips", null).copy(photoCount = 0)
        val iceland = located("Iceland", 64.14, -21.94).copy(parent = trips.id)
        val rome = located("Rome", 41.9, 12.5).copy(parent = trips.id)
        val sydney = located("Sydney", -33.86, 151.21)
        val model = model(this, albums = listOf(trips, iceland, rome, sydney))
        model.start()
        model.mapViewport(390.0, 640.0)

        model.toggleMap()
        val library = requireNotNull(model.state.value.stack.map?.camera)
        assertTrue(listOf(iceland, rome, sydney).all { library.shows(it) }, "the root frames every album")

        model.toggleMap()
        model.open(trips)
        model.toggleMap()
        val container = requireNotNull(model.state.value.stack.map?.camera)
        assertTrue(container.shows(iceland) && container.shows(rome), "Trips frames its own albums")
        assertFalse(container.shows(sydney), "and not the rest of the library")
        assertEquals(setOf("Iceland", "Rome", "Sydney"), model.state.value.pinNames(), "though every pin is still there")
    }

    @Test
    fun theCameraSurvivesOpeningAnAlbumAndTogglingButNotLeavingItsLevel() = runTest {
        val trips = album("Trips", null).copy(photoCount = 0)
        val iceland = located("Iceland", 64.14, -21.94).copy(parent = trips.id)
        val model = model(this, albums = listOf(trips, iceland))
        model.start()

        model.toggleMap()
        val looking = MapCamera(60.0, -10.0, 4.0)
        model.moveCamera(looking)
        model.tapMap(model.state.value.clusterOf("Iceland"))
        assertTrue(model.state.value.screen is Screen.Grid, "a pin opens its album")
        assertTrue(model.state.value.showingMap, "on its own map")
        assertTrue(model.state.value.map?.pins.orEmpty().all { it is MapPin.OfPhoto }, "which places its photos")
        model.back()
        assertTrue(model.state.value.showingMap, "Back returns to the map")
        assertEquals(looking, model.state.value.stack.map?.camera, "where it was left")

        model.toggleMap()
        model.toggleMap()
        assertEquals(looking, model.state.value.stack.map?.camera, "toggling away and back keeps it")

        model.toggleMap()
        model.open(trips)
        model.toggleMap()
        model.moveCamera(looking)
        model.back()
        model.open(trips)
        assertFalse(model.state.value.showingMap, "a level left is a level forgotten")
        model.toggleMap()
        assertTrue(model.state.value.stack.map?.camera != looking, "so its map is framed afresh")
    }

    @Test
    fun tappingAClusterZoomsUntilItSplits() = runTest {
        // About 400 m apart: one circle over the country, two pins over the street.
        val model = model(this, albums = listOf(located("Marienplatz", 48.137, 11.575), located("Isartor", 48.139, 11.580)))
        model.start()
        model.toggleMap()
        model.moveCamera(MapCamera(48.138, 11.577, 5.0))
        val before = requireNotNull(model.state.value.stack.map)

        model.tapMap(model.state.value.clusterAt())

        val after = requireNotNull(model.state.value.stack.map)
        assertEquals(before.moves + 1, after.moves, "the renderer is told to follow")
        val zoom = requireNotNull(after.camera).zoom
        assertEquals(2, requireNotNull(model.state.value.map).clusters.at(zoom).size, "and at the new zoom they are two pins")
    }

    /** Several trips to one town: no zoom separates them, so they are listed instead (§6). */
    @Test
    fun albumsInOneSpotAreListedRatherThanZoomedInto() = runTest {
        val first = located("Reykjavík 2019", 64.14, -21.94)
        val second = located("Reykjavík 2023", 64.14, -21.94)
        val model = model(this, albums = listOf(first, second))
        model.start()
        model.toggleMap()

        model.tapMap(model.state.value.clusterAt())
        assertEquals(setOf(first, second), model.state.value.map?.sheet?.toSet())

        model.openFromSheet(second)
        assertEquals(Screen.Grid(second.id, second.name), model.state.value.screen)
        assertTrue(model.state.value.showingMap, "opened on its map, as its pin would be")
        model.back()
        assertNull(model.state.value.map?.sheet, "the list does not reopen on the way back")
    }

    @Test
    fun anAlbumsMapPlacesItsPhotosAndOpensTheViewerAtOne() = runTest {
        val photos = listOf(
            PhotoRow(id = Uuid.random(), filename = "IMG_0000.jpg", latitude = 64.14, longitude = -21.94),
            PhotoRow(id = Uuid.random(), filename = "IMG_0001.jpg"),
            PhotoRow(id = Uuid.random(), filename = "IMG_0002.jpg", latitude = 64.14, longitude = -21.94),
            PhotoRow(id = Uuid.random(), filename = "IMG_0003.jpg", latitude = 65.68, longitude = -18.09),
        )
        val iceland = album("Iceland", 2024)
        val own = own(this)
        val model = AppModel(
            FakeCatalog(listOf(iceland), photos = photos), FakeSyncer(SyncOutcome.Succeeded(1, 4)),
            FakeThumbnails(), FakePreviews(), FakeVideos(), idleQueue(own), own,
        )
        model.start()
        model.open(iceland)
        model.toggleMap()
        assertEquals("3 of 4 photos on the map", model.state.value.photosSubtitle)

        // The two in one spot never split, so the viewer opens at the earlier of them.
        model.moveCamera(MapCamera(64.14, -21.94, MapLimits.MAX_ZOOM.toDouble()))
        val together = requireNotNull(model.state.value.map).clusters.at(MapLimits.MAX_ZOOM.toDouble()).single { !it.isPin }
        model.tapMap(together)
        assertEquals(0, (model.state.value.screen as Screen.Photo).index)

        model.back()
        assertTrue(model.state.value.showingMap, "Back from the viewer is the album's map")
        val akureyri = requireNotNull(model.state.value.map).clusters.at(MapLimits.MAX_ZOOM.toDouble()).single { it.members == listOf(2) }
        model.tapMap(akureyri)
        assertEquals(3, (model.state.value.screen as Screen.Photo).index, "a pin opens the viewer at its own photo")
    }

    /**
     * The first frame is made before the screen says how big it is. A map narrower than the phone
     * the model assumed opened with both pins past its edges — which is what the desktop
     * scenario's first frame of this map showed.
     */
    @Test
    fun theFirstFrameIsRefittedToTheRealViewportUntilTheCameraIsTakenOver() = runTest {
        val iceland = located("Iceland", 64.14, -21.94)
        val rome = located("Rome", 41.9, 12.5)
        val model = model(this, albums = listOf(iceland, rome))
        model.start()
        model.toggleMap()
        val assumed = requireNotNull(model.state.value.stack.map)
        assertFalse(requireNotNull(assumed.camera).shows(iceland, 215.0, 387.0), "framed for a phone, Iceland is past the edge")

        model.mapViewport(215.0, 387.0)
        val refit = requireNotNull(model.state.value.stack.map)
        val camera = requireNotNull(refit.camera)
        assertTrue(camera.shows(iceland, 215.0, 387.0) && camera.shows(rome, 215.0, 387.0), "both fit the map that is really there")
        assertEquals(assumed.moves + 1, refit.moves, "and the renderer is told to follow")

        val chosen = MapCamera(50.0, 0.0, 3.0)
        model.moveCamera(chosen)
        model.mapViewport(390.0, 640.0)
        assertEquals(chosen, model.state.value.stack.map?.camera, "once someone has moved it, a resize leaves it alone")
    }

    private fun located(name: String, latitude: Double, longitude: Double): Album =
        album(name, 2024).copy(latitude = latitude, longitude = longitude)

    private fun AppUi.pinNames(): Set<String> =
        map?.pins.orEmpty().map { (it as MapPin.OfAlbum).album.name }.toSet()

    /** The cluster holding [name]'s pin at the camera's zoom. */
    private fun AppUi.clusterOf(name: String): Cluster {
        val map = requireNotNull(map)
        return map.clusters.at(requireNotNull(stack.map?.camera).zoom)
            .single { cluster -> cluster.members.any { (map.pins[it] as MapPin.OfAlbum).album.name == name } }
    }

    /** The one thing drawn at the camera's zoom. */
    private fun AppUi.clusterAt(): Cluster =
        requireNotNull(map).clusters.at(requireNotNull(stack.map?.camera).zoom).single()

    private fun MapCamera.shows(album: Album, width: Double = 390.0, height: Double = 640.0): Boolean {
        val at = toScreen(Mercator.project(album.latitude!!, album.longitude!!), width, height)
        return at.x in 0.0..width && at.y in 0.0..height
    }

    private fun AppModel.names(): List<String> = state.value.albums.map { it.name }

    /** The list as it reads: indented by depth, a header marked ▾, a group's closing line as — name. */
    private fun AppUi.lines(): List<String> {
        val names = albums.associate { it.id to it.name }
        return rows.map { entry ->
            when (entry) {
                is ListEntry.Row -> "  ".repeat(entry.depth) + entry.album.name + if (entry.header) " ▾" else ""
                is ListEntry.End -> "  ".repeat(entry.ancestors.size) + "— " + names[entry.container]
            }
        }
    }

    private fun AppUi.rowOf(album: Album): ListEntry.Row =
        rows.filterIsInstance<ListEntry.Row>().single { it.album.id == album.id }

    private fun container(name: String, parent: Album? = null): Album =
        album(name, null).copy(photoCount = 0, parent = parent?.id)

    /** Two trips, one of them nested, for the searches. */
    private inner class Library {
        val iceland = container("Iceland")
        val reykjavik = album("Reykjavík", 2024).copy(parent = iceland.id, photoCount = 40, nameFolded = "reykjavik")
        val ringRoad = album("Ring Road", 2023).copy(parent = iceland.id, photoCount = 212)
        val norway = container("Norway")
        val lofoten = container("Lofoten", norway)
        val reine = album("Reine", 2022).copy(parent = lofoten.id, photoCount = 80)
        val henningsvaer = album("Henningsvær", 2021).copy(parent = lofoten.id, photoCount = 40, nameFolded = "henningsvaer")
        val oslo = album("Oslo", 2020).copy(parent = norway.id, photoCount = 70)
        val all = listOf(iceland, reykjavik, ringRoad, norway, lofoten, reine, henningsvaer, oslo)
    }

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
        return AppModel(FakeCatalog(albums), FakeSyncer(outcome), FakeThumbnails(), FakePreviews(), FakeVideos(), idleQueue(own), own)
    }

    private class FakeCatalog(
        private val albums: List<Album>,
        private val photos: List<PhotoRow> = emptyList(),
    ) : Catalog {
        override fun albums(under: Uuid?): List<Album> = albums.filter { it.parent == under }
        override fun search(text: String): List<Album> =
            albums.filter { it.nameFolded.contains(text.lowercase()) }
        override fun photos(inAlbum: Uuid): List<PhotoRow> = photos
        override fun album(id: Uuid): Album? = albums.firstOrNull { it.id == id }
        override fun totals() = Totals(albums.size, albums.sumOf { it.photoCount })
        override fun blobs(): Map<Uuid, List<BlobRef>> = emptyMap()
    }

    /**
     * A queue over a store with nothing in it and nothing to fetch.
     *
     * The ladder has its own suite; what these tests care about is that the model still renders
     * when every album reads as holding nothing.
     */
    private fun idleQueue(scope: CoroutineScope) = CacheQueue(
        store = object : BlobStore {
            override fun has(id: net.stho.photos.catalog.ObjectId) = false
            override suspend fun fetch(id: net.stho.photos.catalog.ObjectId) = Unit
            override fun delete(id: net.stho.photos.catalog.ObjectId) = Unit
            override fun present() = emptySet<net.stho.photos.catalog.ObjectId>()
        },
        scope = scope,
        backoff = { },
    )

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
    private class FakeVideos(private val pair: LivePair? = null) : Videos {
        override suspend fun localFile(photo: PhotoRow): String? = null
        override suspend fun livePair(photo: PhotoRow): LivePair? = pair
    }

    /** No previews: enough for the state tier, which decides *when* to ask, not what comes back. */
    /**
     * A bitmap with nothing behind it. `ImageBitmap(w, h)` allocates through Skia, whose native
     * library this JVM test run does not load — the fake decoder threw, and the preview it was
     * meant to deliver silently never arrived.
     */
    private object NoPixels : ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace = ColorSpaces.Srgb
        override val hasAlpha = false
        override val config = ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
        override fun prepareToDraw() = Unit
    }

    private class FakePreviews : Previews {
        override fun cached(photo: PhotoRow): Preview? = null
        override suspend fun load(photo: PhotoRow): Preview? = null
        override fun prefetch(photos: List<PhotoRow>, index: Int) = Unit
        override fun cancelPrefetch() = Unit
    }
}
