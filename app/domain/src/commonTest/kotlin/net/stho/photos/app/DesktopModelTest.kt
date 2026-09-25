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
import net.stho.photos.faces.FaceBox
import net.stho.photos.faces.IndexEntry
import net.stho.photos.faces.LabelSet
import net.stho.photos.faces.Labels
import net.stho.photos.faces.Person
import net.stho.photos.faces.Verdict
import net.stho.photos.faces.VerdictKind
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

    /** Down a row, and the grid is asked to show the row after it too: the page turns a row early. */
    @Test
    fun movingDownAsksForTheRowBeyondTheFocus() = runTest {
        val rome = album("Rome", 2024)
        val model = model(this, Library(rome).photos(rome, *Array(12) { "p$it" })).started()
        model.select(rome)
        model.moveFocus(1, columns = 3)
        model.moveFocus(3, columns = 3)
        assertEquals(3, model.state.value.focus)
        assertEquals(3, model.state.value.scroll?.reveal)
        assertEquals(6, model.state.value.scroll?.ahead)
        model.moveFocus(-3, columns = 3)
        assertEquals(0, model.state.value.scroll?.ahead, "up: the row above, clamped to the first")
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

    // ------------------------------------------------------------------------------- the maps

    /** §11: with no album selected the pane is the library's map, framed on every located album — and the list is whole. */
    @Test
    fun launchFramesTheLibraryAndListsEveryAlbum() = runTest {
        val model = model(this, Library(ROME, LISBON, CHRISTMAS)).started()

        val ui = model.state.value
        assertTrue(ui.showingLibraryMap)
        assertEquals(2, ui.libraryMap?.pins?.size)
        assertEquals("2 of 3 albums on the map", ui.librarySubtitle)
        assertTrue(ui.libraryView.camera != null, "framed once its pins are clustered")
        assertNull(ui.inView, "the automatic frame narrows nothing")
        assertEquals(listOf("Rome", "Christmas", "Lisbon"), model.names())
    }

    /** The basemap reports the camera it was handed, rounded; that is not somebody moving the map. */
    @Test
    fun theBasemapReportingTheFrameBackNarrowsNothing() = runTest {
        val model = model(this, Library(ROME, LISBON, CHRISTMAS)).started()
        val framed = requireNotNull(model.state.value.libraryView.camera)

        model.cameraMoved(framed.copy(latitude = framed.latitude + 1e-7, zoom = framed.zoom + 1e-5))

        assertNull(model.state.value.inView)
        assertEquals(3, model.names().size)
    }

    @Test
    fun aPanNarrowsTheListToWhatTheMapShows() = runTest {
        val trips = album("Trips", null).copy(photoCount = 0)
        val rome = ROME.copy(parent = trips.id)
        val model = model(this, Library(trips, rome, LISBON, CHRISTMAS)).started()

        model.cameraMoved(OVER_ROME)

        val ui = model.state.value
        assertEquals(listOf("Trips", "Rome"), model.names(), "Rome under its container's header; Lisbon off the map, Christmas nowhere on it")
        assertEquals("1 in map view", ui.albumsSubtitle)
        assertEquals(OVER_ROME, ui.libraryView.camera)
        assertNull(ui.libraryView.framing, "no longer the automatic frame")
    }

    /** Selecting from a narrowed list keeps it narrowed, and Esc comes back to the same view. */
    @Test
    fun anAlbumOpenedFromTheNarrowedListKeepsTheMapAsItWas() = runTest {
        val library = Library(ROME, LISBON, CHRISTMAS).photos(ROME, "a")
        val model = model(this, library).started()
        model.cameraMoved(OVER_ROME)

        model.select(ROME)
        assertFalse(model.state.value.showingLibraryMap)
        assertEquals(listOf("Rome"), model.names(), "no reshuffle under the click")
        model.cameraMoved(MapCamera(0.0, 0.0, 1.0))
        assertEquals(OVER_ROME, model.state.value.libraryView.camera, "a map not on screen is not moved")

        model.showLibrary()

        val ui = model.state.value
        assertTrue(ui.showingLibraryMap)
        assertNull(ui.selected)
        assertEquals(emptyList(), ui.photos)
        assertEquals(OVER_ROME, ui.libraryView.camera)
        assertEquals(listOf("Rome"), model.names())
    }

    /** A search and the map narrow together; the pins follow the search, and the camera stays. */
    @Test
    fun aSearchAndTheMapNarrowTogether() = runTest {
        val robin = located(album("Robin Hood's Bay", 2021), 54.43, -0.53)
        val model = model(this, Library(ROME, LISBON, robin)).started()
        model.cameraMoved(OVER_ROME)

        model.search("ro")

        val ui = model.state.value
        assertEquals(listOf("Rome"), model.names())
        assertEquals("1 matching · in map view", ui.albumsSubtitle)
        assertEquals("2 of 2 matching on the map", ui.librarySubtitle)
        assertEquals(OVER_ROME, ui.libraryView.camera)
    }

    /** Before the map has been moved, a search lists every match, placed or not. */
    @Test
    fun beforeTheMapIsMovedASearchListsEveryMatch() = runTest {
        val model = model(this, Library(ROME, LISBON, CHRISTMAS)).started()

        model.search("r")

        assertEquals(listOf("Rome", "Christmas"), model.names())
    }

    @Test
    fun theChipsCrossListsEveryAlbumAgainAndReframes() = runTest {
        val model = model(this, Library(ROME, LISBON, CHRISTMAS)).started()
        model.cameraMoved(OVER_ROME)
        val moves = model.state.value.libraryView.moves

        model.clearInView()

        val ui = model.state.value
        assertNull(ui.inView)
        assertEquals(3, model.names().size)
        assertEquals(moves + 1, ui.libraryView.moves, "the basemap is sent back to the whole library")
        assertTrue(ui.libraryView.framing != null)
    }

    /** A resize refits a frame nobody moved; a moved camera stays put, and the list follows the new edges. */
    @Test
    fun aResizeRefitsTheFrameOrNarrowsToTheNewEdges() = runTest {
        val model = model(this, Library(ROME, LISBON)).started()
        val framed = model.state.value.libraryView.camera

        model.mapViewport(500.0, 400.0)
        assertTrue(model.state.value.libraryView.camera != framed, "refitted")
        assertNull(model.state.value.inView)

        // Rome at the left edge of a wide view; a narrower one loses it.
        model.cameraMoved(MapCamera(41.9, 14.0, 8.0))
        model.mapViewport(1600.0, 400.0)
        assertEquals(listOf("Rome"), model.names())
        model.mapViewport(300.0, 400.0)
        assertEquals(emptyList(), model.names())
        assertEquals(MapCamera(41.9, 14.0, 8.0), model.state.value.libraryView.camera, "centre and zoom kept")
    }

    /** §6's rule, on the desktop: an album's pin opens it on its own map, framed on its photos. */
    @Test
    fun anAlbumsPinOpensItOnItsMap() = runTest {
        val library = Library(ROME, LISBON).photosAt(ROME, 41.90 to 12.50, 41.89 to 12.49, null)
        val model = model(this, library).started()

        model.tapMap(model.clusterOf(ROME))

        val ui = model.state.value
        assertEquals(ROME.id, ui.selected?.id)
        assertTrue(ui.showingAlbumMap)
        assertEquals(2, ui.albumMap?.pins?.size)
        assertEquals("2 of 3 photos on the map", ui.photosSubtitle)
        assertTrue(ui.albumView.camera != null)
        assertNull(ui.inView, "opening an album is not moving the library's map")
    }

    /** The pane keeps its view for the next album: ↑/↓ walk the list on the map, each framed afresh. */
    @Test
    fun theAlbumMapCarriesToTheNextAlbumFramedAfresh() = runTest {
        val library = Library(ROME, LISBON).photosAt(ROME, 41.9 to 12.5).photosAt(LISBON, 38.72 to -9.14)
        val model = model(this, library).started()
        model.select(ROME)
        model.togglePhotoMap()
        val onRome = model.state.value.albumView.camera

        model.selectAdjacent(1)

        val ui = model.state.value
        assertEquals(LISBON.id, ui.selected?.id)
        assertTrue(ui.showingAlbumMap)
        assertTrue(ui.albumView.camera != null && ui.albumView.camera != onRome)

        model.togglePhotoMap()
        assertFalse(model.state.value.showingAlbumMap)
        assertEquals("1 photos · 1 Jan – 31 Dec 2022", model.state.value.photosSubtitle)
    }

    /** One spot no zoom separates: all the way in, and the list — narrowed to it — names its albums. */
    @Test
    fun aClusterThatNeverSplitsZoomsAllTheWayIn() = runTest {
        val again = located(album("Rome again", 2025), 41.9, 12.5)
        val model = model(this, Library(ROME, again, LISBON)).started()
        val cluster = model.clusterOf(ROME)
        assertEquals(2, cluster.members.size)

        model.tapMap(cluster)

        val ui = model.state.value
        assertEquals(MapLimits.MAX_ZOOM.toDouble(), ui.libraryView.camera?.zoom)
        assertEquals(listOf("Rome again", "Rome"), model.names())
        assertNull(ui.selected)
    }

    /** On an album's map, photos in one spot open the viewer at the earliest, as on the phone. */
    @Test
    fun photosInOneSpotOpenTheViewerAtTheEarliest() = runTest {
        val library = Library(ROME, LISBON).photosAt(ROME, null, 41.9 to 12.5, 41.9 to 12.5)
        val model = model(this, library).started()
        model.tapMap(model.clusterOf(ROME))
        val view = requireNotNull(model.state.value.albumView.camera)
        val spot = requireNotNull(model.state.value.albumMap).clusters.at(view.zoom).single()

        model.tapMap(spot)

        assertEquals(1, model.state.value.open)
    }

    // ------------------------------------------------------------------------------ people (§12)

    /**
     * Enter on a selection of suggestions confirms them, and the focus goes on to the face after
     * the last of them — the next one to decide about — with nothing selected (§12).
     */
    @Test
    fun confirmingMovesTheFocusToTheFaceAfterTheSelection() = runTest {
        val people = MemoryPeople(suggestions = 5)
        val model = model(this, Library(album("Rome", 2024)), people).started()
        model.show(Showing.Person(people.anna.id))
        val before = model.state.value.faces.map { it.id }
        assertEquals(5, before.size)

        // The first two, selected with Shift+→; the third is the one after them.
        model.moveFaceFocus(0, extend = false)
        model.moveFaceFocus(1, extend = true)
        model.confirmChosen()

        val ui = model.state.value
        assertEquals(before[2], ui.faces[ui.faceFocus!!].id)
        assertTrue(ui.faceSelection.isEmpty())
        assertEquals(3, ui.suggestedCount)

        // Ctrl+Z: both back among the suggestions, and the focus on the first of them.
        model.undo()
        val undone = model.state.value
        assertEquals(5, undone.suggestedCount)
        assertEquals(before[0], undone.faces[undone.faceFocus!!].id)

        // Esc from a person is the library's map again, as it is from an album (§11).
        model.showLibrary()
        assertNull(model.state.value.showing)
        assertTrue(model.state.value.showingLibraryMap)
    }

    /** D, a drag, a name: the drawn box is confirmed as that person, and Ctrl+Z takes it back. */
    @Test
    fun aBoxDrawnOverAPhotoIsNamedAndCanBeUndone() = runTest {
        val rome = album("Rome", 2024)
        val people = MemoryPeople(suggestions = 0)
        val model = model(this, Library(rome).photos(rome, "a", "b"), people).started()
        model.select(rome)
        model.openPhoto(0)

        model.toggleDrawing()
        assertTrue(model.state.value.drawing)
        assertTrue(model.state.value.faceBoxes, "the faces already found are what not to draw over")
        val box = FaceBox(0.4f, 0.3f, 0.1f, 0.12f)
        model.drawn(box)
        assertEquals(box, model.state.value.drawnBox)

        model.nameDrawn(people.anna.id)
        assertNull(model.state.value.drawnBox)
        val verdict = people.decided.single()
        assertEquals(model.state.value.photos[0].id, verdict.photoId)
        assertEquals(box, verdict.box)
        assertEquals(people.anna.id, verdict.personId)

        model.undo()
        assertTrue(people.decided.isEmpty())

        // Paging drops a box not yet named; leaving the photo leaves drawing.
        model.drawn(box)
        model.step(forward = true)
        assertNull(model.state.value.drawnBox)
        model.closePhoto()
        assertFalse(model.state.value.drawing)
    }

    // -------------------------------------------------------------------------------- fixtures

    private fun model(scope: TestScope, library: Library, people: People? = null): DesktopModel {
        val own = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler)).also { scopes += it }
        return if (people == null) DesktopModel(library, library, library, NoPreviews, NoVideos, own)
        else DesktopModel(library, library, library, NoPreviews, NoVideos, own, people = people)
    }

    /** One person and [suggestions] faces suggested as them, decided in memory. */
    private class MemoryPeople(suggestions: Int) : People {
        val anna = Person(Uuid.random(), "Anna")
        private val box = FaceBox(0.1f, 0.1f, 0.2f, 0.2f)
        private val entries = List(suggestions) { n ->
            IndexEntry(Uuid.random(), Uuid.random(), Uuid.random(), box, 0.9f, null, anna.id, true, 0.9f - n * 0.01f, null)
        }
        private val verdicts = mutableListOf<Verdict>()
        val decided: List<Verdict> get() = verdicts.toList()
        private var clock = 0L

        override fun read(): PeopleSnapshot = PeopleSnapshot.of(entries, LabelSet(listOf(anna), verdicts.toList()))
        override fun confirm(faces: List<Face>, person: Uuid): Labels.Change {
            val added = faces.map { Verdict(Uuid.random(), it.photoId, it.box, VerdictKind.CONFIRMED, person, Instant.fromEpochSeconds(++clock)) }
            verdicts += added
            return Labels.Change(emptyList(), added)
        }
        override fun revert(change: Labels.Change) {
            verdicts -= change.added.toSet()
            verdicts += change.removed
        }
        private val nothing = Labels.Change(emptyList(), emptyList())
        override fun createPerson(name: String): Person = error("not here")
        override fun rename(person: Uuid, name: String) = Unit
        override fun merge(from: Uuid, into: Uuid) = Unit
        override fun reject(faces: List<Face>, person: Uuid) = nothing
        override fun ignore(faces: List<Face>) = nothing
        override fun clear(faces: List<Face>) = nothing
    }

    private fun DesktopModel.started(): DesktopModel = also { start() }

    private fun DesktopModel.names(): List<String> = state.value.albums.map { it.name }

    /** What the library map draws [album] in, at the zoom it is at. */
    private fun DesktopModel.clusterOf(album: Album): Cluster {
        val ui = state.value
        val map = requireNotNull(ui.libraryMap)
        val zoom = requireNotNull(ui.libraryView.camera).zoom
        return map.clusters.at(zoom).single { cluster ->
            cluster.members.any { (map.pins[it] as MapPin.OfAlbum).album.id == album.id }
        }
    }

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

        /** Photos taken where each pair says, or somewhere unrecorded for a null. */
        fun photosAt(album: Album, vararg at: Pair<Double, Double>?) = also {
            photos[album.id] = at.mapIndexed { index, place ->
                PhotoRow(
                    id = Uuid.random(), filename = "$index.jpg", takenAt = Instant.parse("2024-03-05T14:02:00Z"),
                    latitude = place?.first, longitude = place?.second,
                )
            }
        }

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

    private fun located(album: Album, latitude: Double, longitude: Double) = album.copy(latitude = latitude, longitude = longitude)

    private val ROME = located(album("Rome", 2024), 41.9, 12.5)
    private val LISBON = located(album("Lisbon", 2022), 38.72, -9.14)
    private val CHRISTMAS = album("Christmas", 2023)

    /** Rome and its surroundings, at a zoom that leaves Lisbon far off the edge. */
    private val OVER_ROME = MapCamera(41.9, 12.5, 7.0)
}
