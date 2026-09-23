package net.stho.photos.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.stho.photos.catalog.Album
import net.stho.photos.model.PhotoRow

/**
 * §11's viewer, with no shards, no packs and no library on disk: the list it shares with the
 * phone, the selection, the arrow keys and the photo open over the album pane.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopModelTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stopModels(): Unit = scopes.forEach(CoroutineScope::cancel)

    // ------------------------------------------------------------------------------- the list

    @Test
    fun theListIsReadOnceTheCatalogHasBeenRebuilt() = runTest {
        val library = Library(album("Rome", 2024), album("Lisbon", 2022))
        val model = model(this, library)
        assertTrue(model.state.value.loading, "loading until the first rebuild")

        model.start()

        assertFalse(model.state.value.loading)
        assertEquals(listOf("Rome", "Lisbon"), model.names())
        assertEquals("2 albums · ${AlbumSort.DateNewest.label}", model.state.value.albumsSubtitle)
    }

    @Test
    fun aSearchNarrowsTheListAndLeavesTheSelectionAlone() = runTest {
        val rome = album("Rome", 2024)
        val model = model(this, Library(rome, album("Lisbon", 2022))).started()
        model.select(rome)

        model.search("lis")

        assertEquals(listOf("Lisbon"), model.names())
        assertEquals("1 matching", model.state.value.albumsSubtitle)
        assertEquals(rome.id, model.state.value.selected?.id)
    }

    /** A new order has no place to return to: the sidebar starts again at the top, as the phone's does. */
    @Test
    fun aNewOrderForgetsWhereTheSidebarWas() = runTest {
        val model = model(this, Library(album("Rome", 2024), album("Lisbon", 2022))).started()
        model.listScrolled(key = "x", index = 1, offset = 0)

        model.cycleSort()

        assertNull(model.state.value.listScroll)
    }

    @Test
    fun aRangeWithNoPhotosIsRefused() = runTest {
        val rome = album("Rome", 2024)
        val library = Library(rome).taken(rome, "2024-03-05T10:00:00Z")
        val model = model(this, library).started()

        assertFalse(model.applyRange(DateRange(Day.of(2023, 1, 1), Day.of(2023, 1, 2))))
        assertNull(model.state.value.range)
        assertTrue(model.applyRange(DateRange(Day.of(2024, 3, 1), Day.of(2024, 3, 31))))
        assertEquals(listOf("Rome"), model.names())
    }

    // -------------------------------------------------------------------------- the selection

    /** §11: the album pane always shows one album, so a container's header selects nothing. */
    @Test
    fun aContainersHeaderIsNotSelectable() = runTest {
        val trips = album("Trips", null).copy(photoCount = 0)
        val rome = album("Rome", 2024).copy(parent = trips.id)
        val model = model(this, Library(trips, rome)).started()

        model.select(trips)
        assertNull(model.state.value.selected)

        model.select(rome)
        assertEquals(rome.id, model.state.value.selected?.id)
    }

    @Test
    fun selectingAnAlbumShowsItsPhotosAndThumbnails() = runTest {
        val rome = album("Rome", 2024)
        val library = Library(rome).photos(rome, "a.jpg", "b.jpg")
        val model = model(this, library).started()

        model.select(rome)

        val ui = model.state.value
        assertEquals(listOf("a.jpg", "b.jpg"), ui.photos.map { it.filename })
        assertEquals(ui.photos.map { it.id }.toSet(), ui.thumbnails.keys)
        assertEquals("2 photos · 1 Jan – 31 Dec 2024", ui.photosSubtitle)
    }

    /** ↑/↓ in the sidebar walk the albums and step over the headers between them. */
    @Test
    fun theArrowKeysInTheSidebarSkipHeaders() = runTest {
        val trips = album("Trips", null).copy(photoCount = 0)
        val rome = album("Rome", 2024).copy(parent = trips.id)
        val lisbon = album("Lisbon", 2022)
        val model = model(this, Library(trips, rome, lisbon)).started()
        assertEquals(listOf("Trips", "Rome", "Lisbon"), model.names())

        model.selectAdjacent(1)
        assertEquals("Rome", model.state.value.selected?.name, "the first press lands on the first album")
        model.selectAdjacent(1)
        assertEquals("Lisbon", model.state.value.selected?.name)
        // Row 3: Trips, Rome, the line closing Trips' group, then Lisbon.
        assertEquals(3, model.state.value.listScroll?.reveal, "and the list is asked to show its row")
        model.selectAdjacent(1)
        assertEquals("Lisbon", model.state.value.selected?.name, "and stops at the end")
        model.selectAdjacent(-1)
        assertEquals("Rome", model.state.value.selected?.name, "never on the header above it")
        model.selectAdjacent(-1)
        assertEquals("Rome", model.state.value.selected?.name)
    }

    // ------------------------------------------------------------------------------- the grid

    @Test
    fun theArrowKeysMoveTheFocusAndAskTheGridToShowIt() = runTest {
        val rome = album("Rome", 2024)
        val model = model(this, Library(rome).photos(rome, "a", "b", "c", "d", "e")).started()
        model.select(rome)

        model.moveFocus(1)
        assertEquals(0, model.state.value.focus, "the first press lands on the first tile")
        model.moveFocus(3)
        assertEquals(3, model.state.value.focus)
        assertEquals(3, model.state.value.scroll?.reveal)
        val moves = model.state.value.scroll?.moves ?: 0
        model.moveFocus(3)
        assertEquals(4, model.state.value.focus, "clamped to the last photo")
        assertEquals(moves + 1, model.state.value.scroll?.moves)
    }

    @Test
    fun theTileSizeStepsAndStopsAtEitherEnd() = runTest {
        val model = model(this, Library()).started()
        assertEquals(TileSize.Medium, model.state.value.tile)

        model.zoom(closer = true)
        model.zoom(closer = true)
        assertEquals(TileSize.Large, model.state.value.tile)
        model.zoom(closer = false)
        model.zoom(closer = false)
        model.zoom(closer = false)
        assertEquals(TileSize.Small, model.state.value.tile)
    }

    // ----------------------------------------------------------------------------- the viewer

    @Test
    fun aPhotoOpensOverTheAlbumAndPagesThroughIt() = runTest {
        val rome = album("Rome", 2024)
        val library = Library(rome).photos(rome, "a.jpg", "b.jpg", "c.jpg")
        val model = model(this, library).started()
        model.select(rome)

        model.openPhoto(1)
        assertEquals(1, model.state.value.open)
        assertEquals("2 of 3 · b.jpg · 5 Mar 2024 14:02", model.state.value.viewerSubtitle)

        model.step(forward = true)
        assertEquals(2, model.state.value.open)
        model.step(forward = true)
        assertEquals(2, model.state.value.open, "no further than the last photo")

        model.closePhoto()
        val ui = model.state.value
        assertNull(ui.open)
        assertEquals(2, ui.focus, "back on the grid, on the photo the viewer was left on")
        assertEquals(2, ui.scroll?.reveal)
    }

    @Test
    fun enterOnTheGridOpensTheFocusedPhoto() = runTest {
        val rome = album("Rome", 2024)
        val model = model(this, Library(rome).photos(rome, "a", "b", "c")).started()
        model.select(rome)
        model.moveFocus(1)
        model.moveFocus(1)

        model.openFocused()

        assertEquals(1, model.state.value.open)
    }

    @Test
    fun anotherAlbumClosesTheViewer() = runTest {
        val rome = album("Rome", 2024)
        val lisbon = album("Lisbon", 2022)
        val library = Library(rome, lisbon).photos(rome, "a").photos(lisbon, "b")
        val model = model(this, library).started()
        model.select(rome)
        model.openPhoto(0)

        model.select(lisbon)

        assertNull(model.state.value.open)
        assertEquals(listOf("b"), model.state.value.photos.map { it.filename })
    }

    // ---------------------------------------------------------------------------- the refresh

    /** An hourly sync that changed another album must not throw away what is on screen. */
    @Test
    fun aRefreshKeepsTheSelectionAndTheOpenPhoto() = runTest {
        val rome = album("Rome", 2024)
        val library = Library(rome).photos(rome, "a", "b")
        val model = model(this, library).started()
        model.select(rome)
        model.openPhoto(1)

        library.albums += album("Lisbon", 2022)
        model.refresh()

        assertEquals(listOf("Rome", "Lisbon"), model.names())
        assertEquals(rome.id, model.state.value.selected?.id)
        assertEquals(1, model.state.value.open)
    }

    @Test
    fun aRefreshDropsAnAlbumThatIsGone() = runTest {
        val rome = album("Rome", 2024)
        val library = Library(rome).photos(rome, "a")
        val model = model(this, library).started()
        model.select(rome)

        library.albums -= rome
        model.refresh()

        assertNull(model.state.value.selected)
        assertEquals(emptyList(), model.state.value.photos)
    }

    /** A rebuild that fails says why, and the list it had stays on screen. */
    @Test
    fun aFailedRebuildSaysWhyAndKeepsTheList() = runTest {
        val library = Library(album("Rome", 2024))
        val model = model(this, library).started()

        library.failure = "no shards in /nowhere"
        model.refresh()

        assertFalse(model.state.value.loading)
        assertEquals("no shards in /nowhere", model.state.value.failure)
        assertEquals(listOf("Rome"), model.names())
    }

    // -------------------------------------------------------------------------------- fixtures

    private fun model(scope: TestScope, library: Library): DesktopModel {
        val own = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler)).also { scopes += it }
        return DesktopModel(library, library, library, NoPreviews, NoVideos, own)
    }

    private fun DesktopModel.started(): DesktopModel = also { start() }

    private fun DesktopModel.names(): List<String> = state.value.albums.map { it.name }

    /** A catalog, its rebuild and its packs, all in memory and all changeable mid-test. */
    private class Library(vararg albums: Album) : Catalog, Rebuilder, Thumbnails {
        var albums: List<Album> = albums.toList()
        private val photos = mutableMapOf<Uuid, List<PhotoRow>>()
        private val taken = mutableMapOf<Uuid, List<Instant>>()
        var failure: String? = null

        fun photos(album: Album, vararg names: String) = also {
            photos[album.id] = names.map {
                PhotoRow(id = Uuid.random(), filename = it, takenAt = Instant.parse("2024-03-05T14:02:00Z"))
            }
        }

        fun taken(album: Album, vararg at: String) = also { taken[album.id] = at.map(Instant::parse) }

        override fun rebuild(): Rebuilt {
            failure?.let { throw IllegalStateException(it) }
            return Rebuilt(albums.size, photos.values.sumOf { it.size }, skipped = 0)
        }

        override fun albums(under: Uuid?): List<Album> = albums.filter { it.parent == under }
        override fun search(text: String): List<Album> = albums.filter { it.nameFolded.contains(text.lowercase()) }
        override fun photos(inAlbum: Uuid): List<PhotoRow> = photos[inAlbum].orEmpty()
        override fun album(id: Uuid): Album? = albums.firstOrNull { it.id == id }
        override fun photosPerDay(): Map<Day, Int> = taken.values.flatten().groupingBy { Day.of(it) }.eachCount()
        override fun photosIn(range: DateRange): Map<Uuid, Int> =
            taken.mapValues { (_, at) -> at.count { Day.of(it) in range } }.filterValues { it > 0 }
        override fun totals(): Totals = Totals(albums.size, albums.sumOf { it.photoCount })
        override fun blobs(): Map<Uuid, List<BlobRef>> = emptyMap()

        override fun has(album: Album): Boolean = true
        override fun cover(album: Album): ByteArray? = null
        override fun all(album: Album): Map<Uuid, ByteArray> = photos[album.id].orEmpty().associate { it.id to ByteArray(1) }
        override fun prioritise(album: Album) = Unit
        override val arrivals = MutableStateFlow(0)
        override val outstanding = MutableStateFlow(0)
    }

    private object NoPreviews : Previews {
        override fun cached(photo: PhotoRow): Preview? = null
        override suspend fun load(photo: PhotoRow): Preview? = null
        override fun prefetch(photos: List<PhotoRow>, index: Int) = Unit
        override fun cancelPrefetch() = Unit
    }

    private object NoVideos : Videos {
        override suspend fun localFile(photo: PhotoRow): String? = null
        override suspend fun livePair(photo: PhotoRow): LivePair? = null
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
}
