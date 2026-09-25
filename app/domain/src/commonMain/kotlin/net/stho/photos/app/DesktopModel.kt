package net.stho.photos.app

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.stho.photos.catalog.Album
import net.stho.photos.catalog.foldedForSearch
import net.stho.photos.faces.FaceBox
import net.stho.photos.faces.Labels
import net.stho.photos.faces.Person
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow

/**
 * The grid's three tile sizes (§11): the bar's S/M/L, and the steps Ctrl+scroll and Ctrl± take.
 * A target, not a width — the column count is whatever fits the pane at this size.
 */
public enum class TileSize(public val dp: Int) {
    Small(96),
    Medium(160),
    Large(256),
    ;

    public fun step(closer: Boolean): TileSize =
        entries[(ordinal + if (closer) 1 else -1).coerceIn(0, entries.lastIndex)]
}

/**
 * Everything the desktop viewer shows, as one immutable snapshot (§11).
 *
 * No back stack: the sidebar is always there, and the album pane shows the library's map until an
 * album is selected, then that album — as a grid or on its own map — and the only thing that opens
 * over it is a photo. Nothing here is remembered between launches.
 */
public data class DesktopUi(
    /** The catalog is being replayed from the CLI's shards: at launch, and on every refresh. */
    val loading: Boolean = true,
    /** Why the last rebuild failed, when it did. The list keeps what it had. */
    val failure: String? = null,
    /** Shards the last rebuild could not read — too new for this build, or damaged. */
    val skipped: Int = 0,
    val sort: AlbumSort = AlbumSort.DateNewest,
    val query: String = "",
    /** One filter at a time, as on the phone: applying a range clears [query], and typing clears it. */
    val range: DateRange? = null,
    /** The calendar sheet's days and months while it is open. */
    val calendar: CalendarUi? = null,
    /** The sidebar: every level of the album list (§6). */
    val rows: List<ListEntry> = emptyList(),
    /** Where the sidebar stands, and the row ↑/↓ moved the selection to, to bring into view. */
    val listScroll: Scroll? = null,
    /** How many albums a search or a date range matched; zero with neither. */
    val matched: Int = 0,
    /** The album in the album pane. Never a container: a header is not selectable. */
    val selected: Album? = null,
    /** The selected album's photos, in §3's order. */
    val photos: List<PhotoRow> = emptyList(),
    /** Its thumbnails, keyed by `PhotoRow.id`, from the CLI's packs. */
    val thumbnails: Map<Uuid, ByteArray> = emptyMap(),
    val tile: TileSize = TileSize.Medium,
    /** The tile the arrow keys are on, outlined; null until they are used. */
    val focus: Int? = null,
    /** Where the grid stands, and the tile it should bring into view next. */
    val scroll: Scroll? = null,
    /** The photo open over the album pane, by index into [photos]; null on the grid. */
    val open: Int? = null,
    /** The open photo, decoded from its original. */
    val preview: Preview? = null,
    /** The photos either side, decoded, so a swipe drags in a picture rather than a placeholder. */
    val nearby: Map<Uuid, Preview> = emptyMap(),
    /** The open video's file in the library. */
    val videoPath: String? = null,
    /** The open Live Photo's still and MOV in the library. */
    val livePair: LivePair? = null,
    /**
     * The library's map, which the album pane shows while no album is selected (§11). Kept while
     * one is, so Esc comes back to the same place.
     */
    val libraryView: MapView = MapView(),
    /** Its pins: every located album that owns photos, narrowed by a search or a range. */
    val libraryMap: MapUi? = null,
    /**
     * What the list is narrowed to: the library map's view when its camera last settled after
     * somebody moved it. Null until then — the automatic frame narrows nothing — and again once
     * the chip clears it.
     */
    val inView: MapWindow? = null,
    /** The album pane shows the selected album's photos on a map rather than as a grid; kept across albums. */
    val photoMap: Boolean = false,
    /** The selected album's map, framed afresh each time an album is selected. */
    val albumView: MapView = MapView(),
    val albumMap: MapUi? = null,
    /** §12's people: the last sync's index with the labels file as it is now laid over it. */
    val people: PeopleSnapshot = PeopleSnapshot.EMPTY,
    /** The sidebar's "Unknown" row, expanded to its groups. Collapsed at launch. */
    val unknownOpen: Boolean = false,
    /** A person or an unknown group in the album pane, instead of an album. */
    val showing: Showing? = null,
    /** Its faces, in the order they are drawn: a person's suggestions first, then confirmed. */
    val faces: List<Face> = emptyList(),
    /** The face the arrow keys are on. */
    val faceFocus: Int? = null,
    /** Where a Shift+arrow or Shift-click range starts. */
    val faceAnchor: Int? = null,
    val faceSelection: Set<Uuid> = emptySet(),
    /** Face crops, JPEG, by face id — for the grids and the sidebar's avatars. */
    val crops: Map<Uuid, ByteArray> = emptyMap(),
    /** The name being typed for an unknown group's selection; null while the field is closed. */
    val naming: String? = null,
    /** The open photo's face boxes are drawn (F). */
    val faceBoxes: Boolean = false,
    /** Where the viewer goes back to when a photo was opened from a person or a group. */
    val returnTo: Showing? = null,
    /** The face whose naming menu is open — a right-click, or Ctrl+Enter on the focused face. */
    val faceMenu: Int? = null,
    /** The sidebar's PEOPLE section, unfolded; a click on its heading folds it. */
    val peopleOpen: Boolean = true,
    /** The sidebar's ALBUMS section, unfolded. */
    val albumsOpen: Boolean = true,
    /** D in the viewer: a drag over the photo marks a face the detector missed (§12). */
    val drawing: Boolean = false,
    /** The box just drawn, waiting for its name; the naming menu is open over it. */
    val drawnBox: FaceBox? = null,
) {
    /** How many of [faces] are a person's suggestions, which come first. */
    public val suggestedCount: Int get() = faces.count { it.state == FaceState.SUGGESTED }

    /**
     * The people the sidebar lists: all of them, or — while the search field has text — those
     * whose name holds it, folded as album names are (§6), so "jose" finds José. "Ignored" and
     * "Unknown" are not names, and step aside while searching.
     */
    public val listedPeople: List<PersonSummary>
        get() {
            if (query.isBlank()) return people.people
            val folded = query.trim().foldedForSearch()
            return people.people.filter { it.person.name.foldedForSearch().contains(folded) }
        }

    /** The person in the pane, when it is one. */
    public val person: PersonSummary?
        get() = (showing as? Showing.Person)?.let { people.person(it.id) }

    /** People whose name starts with what is being typed — the name field's completions. */
    public val completions: List<Person>
        get() {
            val typed = naming?.trim()?.lowercase().orEmpty()
            if (typed.isEmpty()) return emptyList()
            return people.people.map { it.person }.filter { it.name.lowercase().startsWith(typed) }
        }

    /** The open photo's faces, for its boxes. */
    public val openFaces: List<Face>
        get() = open?.let { photos.getOrNull(it) }?.let { people.facesOn(it.id) }.orEmpty()

    /** A name search or a date range is narrowing the list. */
    public val filtering: Boolean get() = query.isNotEmpty() || range != null

    /** No album is selected, so the album pane is the library's map. */
    public val showingLibraryMap: Boolean get() = selected == null && showing == null

    /** The selected album is on its map, with no photo open over it. */
    public val showingAlbumMap: Boolean get() = selected != null && photoMap && open == null

    /** Every album on the sidebar, containers included, in the order they are drawn. */
    public val albums: List<Album> get() = rows.mapNotNull { (it as? ListEntry.Row)?.album }

    /** The sidebar's second line: a count, then the sort, so no menu has to name it (§6). */
    public val albumsSubtitle: String
        get() = when {
            inView != null && filtering -> "$matched matching · in map view"
            inView != null -> "$matched in map view"
            filtering -> "$matched matching"
            else -> "${albums.size} albums · ${sort.label}"
        }

    /** The library map's second line: what is placed, against what could be (§6's wording). */
    public val librarySubtitle: String
        get() {
            val map = libraryMap ?: return ""
            return "${map.pins.size} of ${map.total} ${if (filtering) "matching" else "albums"} on the map"
        }

    /** The album pane's second line: how many photos, and when they were taken. */
    public val photosSubtitle: String
        get() {
            val album = selected ?: return ""
            if (photoMap) albumMap?.let { return "${it.pins.size} of ${it.total} photos on the map" }
            val count = "${photos.size} photos"
            val first = album.dateMin ?: return count
            val last = album.dateMax ?: return count
            return "$count · ${DateRange(Day.of(first), Day.of(last)).label}"
        }

    /** The viewer's line: where this photo is in the album, its file, and when it was taken. */
    public val viewerSubtitle: String
        get() {
            val index = open ?: return ""
            val photo = photos.getOrNull(index) ?: return ""
            return listOfNotNull(
                "${index + 1} of ${photos.size}",
                photo.diskFilename,
                photo.takenAt?.let(::takenLabel),
            ).joinToString(" · ")
        }
}

/**
 * The desktop viewer's model (§11): the album list, the library's map or one selected album, and a
 * photo open over it.
 *
 * Built on the same [AlbumListing] as the phone's, so the list sorts, searches and filters by date
 * exactly as the phone's does. What it does not have is everything the phone needs a network for:
 * no sync, no queue, no cache. [rebuilder] replays the CLI's shards; that is all a refresh is.
 */
public class DesktopModel(
    private val catalog: Catalog,
    private val rebuilder: Rebuilder,
    private val thumbnails: Thumbnails,
    private val previews: Previews,
    private val videos: Videos,
    private val scope: CoroutineScope,
    /** §12's people. The default has none, for a viewer over a library `sync` has not indexed. */
    private val people: People = NoPeople,
    private val crops: FaceCrops = FaceCrops { null },
) {
    private val listing = AlbumListing(catalog)

    private val _state = MutableStateFlow(DesktopUi())
    public val state: StateFlow<DesktopUi> = _state.asStateFlow()

    private var rebuilding: Job? = null
    private var loadingThumbnails: Job? = null
    private var decodingNeighbours: Job? = null
    private var mappingLibrary: Job? = null
    private var mappingAlbum: Job? = null

    /**
     * The album pane's size in dp, which a frame fits and the list's map window is measured in.
     * Until the pane reports one, a laptop's proportions stand in — which is what a test gets.
     */
    private var viewport: Pair<Double, Double> = DEFAULT_VIEWPORT

    /** The first rebuild. The window is up and says it is loading while this runs. */
    public fun start(): Unit = refresh()

    /**
     * F5, or the sidebar's ⟳: replay the CLI's shards and re-read everything drawn from them (§11).
     *
     * The selection survives by id, and so does an open photo while its album still holds it —
     * an hourly sync that changed some other album should not throw away what is on screen.
     */
    public fun refresh() {
        if (rebuilding?.isActive == true) return
        _state.update { it.copy(loading = true) }
        rebuilding = scope.launch {
            val rebuilt = try {
                rebuilder.rebuild()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update { it.copy(loading = false, failure = failure.message ?: failure.toString()) }
                return@launch
            }
            listing.refresh()
            val snapshot = readPeople()
            val selected = _state.value.selected?.id?.let(catalog::album)
            val photos = selected?.let { catalog.photos(it.id) }.orEmpty()
            _state.update { current ->
                val open = current.open?.takeIf { index ->
                    photos.getOrNull(index)?.id == current.photos.getOrNull(index)?.id
                }
                current.copy(
                    loading = false,
                    failure = null,
                    skipped = rebuilt.skipped,
                    calendar = current.calendar?.let { listing.calendar() },
                    selected = selected,
                    photos = photos,
                    focus = current.focus?.takeIf { it in photos.indices },
                    open = open,
                    preview = current.preview.takeIf { open != null },
                    nearby = if (open != null) current.nearby else emptyMap(),
                ).withPeople(snapshot).listed()
            }
            selected?.let(::loadThumbnails)
            refreshLibraryMap()
            refreshAlbumMap()
            loadCrops()
        }
    }

    // ---------------------------------------------------------------------------- the list

    /** A new order: the sidebar's rows move, and the selection stays what it was. */
    public fun cycleSort(): Unit = _state.update { it.copy(sort = it.sort.next(), listScroll = null).listed() }

    /**
     * Typing into the field. One filter at a time: text replaces a date range. The map's pins
     * narrow with the list, and its camera stays where it is.
     */
    public fun search(text: String) {
        _state.update {
            it.copy(query = text, range = if (text.isEmpty()) it.range else null, listScroll = null).listed()
        }
        refreshLibraryMap()
    }

    public fun openCalendar(): Unit = _state.update { it.copy(calendar = listing.calendar()) }

    public fun closeCalendar(): Unit = _state.update { it.copy(calendar = null) }

    /** The sheet's Apply. Refused — false, and nothing changes — for a range with no photos in it. */
    public fun applyRange(range: DateRange): Boolean {
        if (!listing.hasPhotosIn(range)) return false
        _state.update { it.copy(range = range, query = "", calendar = null, listScroll = null).listed() }
        refreshLibraryMap()
        return true
    }

    public fun clearRange() {
        _state.update { it.copy(range = null, listScroll = null).listed() }
        refreshLibraryMap()
    }

    /** Where a scroll left the sidebar: remembered, never scrolled to — it is already there. */
    public fun listScrolled(key: String?, index: Int, offset: Int) {
        _state.update { ui ->
            val was = ui.listScroll ?: Scroll()
            val now = was.copy(key = key, index = index, offset = offset, reveal = null)
            if (now == was) ui else ui.copy(listScroll = now)
        }
    }

    /**
     * The rows for the sort, the search or range, and the map's window, which all narrow at once:
     * a match off the map is hidden as a non-match is. An album with no location is never in the
     * window, so while there is one, it is not listed (§11).
     */
    private fun DesktopUi.listed(): DesktopUi {
        val within = inView?.let { window ->
            located(listing.tree).filter { (_, point) -> point in window }.mapTo(mutableSetOf()) { it.first.id }
        }
        val listed = listing.rows(sort, query, range, within = within)
        return copy(rows = listed.rows, matched = listed.matched)
    }

    // ---------------------------------------------------------------------- the selection

    /**
     * A click on a sidebar row. A container's header is not selectable (§11): the album pane
     * always shows one album, and a container has no photos of its own to show (§2).
     */
    public fun select(album: Album) {
        if (isContainer(album)) return
        if (album.id == _state.value.selected?.id && _state.value.open == null) return
        previews.cancelPrefetch()
        decodingNeighbours?.cancel()
        val photos = catalog.photos(album.id)
        mappingAlbum?.cancel()
        _state.update {
            it.copy(
                selected = album, photos = photos, thumbnails = emptyMap(),
                focus = null, scroll = null, open = null, preview = null, nearby = emptyMap(),
                videoPath = null, livePair = null,
                albumView = MapView(), albumMap = null,
                showing = null, faces = emptyList(), faceFocus = null, faceAnchor = null,
                faceSelection = emptySet(), naming = null, returnTo = null,
            )
        }
        loadThumbnails(album)
        refreshAlbumMap()
    }

    /**
     * Esc from an album, or the sidebar's map icon: no album selected, so the album pane is the
     * library's map again — its camera, and the list's window, where they were left.
     */
    public fun showLibrary() {
        // From an album, or from a person or a group (§12): either leaves the map behind it.
        if (_state.value.selected == null && _state.value.showing == null) return
        previews.cancelPrefetch()
        decodingNeighbours?.cancel()
        loadingThumbnails?.cancel()
        mappingAlbum?.cancel()
        _state.update {
            it.copy(
                selected = null, photos = emptyList(), thumbnails = emptyMap(),
                focus = null, scroll = null, open = null, preview = null, nearby = emptyMap(),
                videoPath = null, livePair = null,
                albumView = MapView(), albumMap = null,
                showing = null, faces = emptyList(), faceFocus = null, faceAnchor = null,
                faceSelection = emptySet(), naming = null, returnTo = null, faceMenu = null,
            )
        }
    }

    /** ↑/↓ in the sidebar: the next album up or down the list, skipping headers. */
    public fun selectAdjacent(step: Int) {
        val ui = _state.value
        val albums = if (!ui.albumsOpen) emptyList() else ui.rows.filterIsInstance<ListEntry.Row>().filterNot { it.header }.map { it.album }
        // The sidebar top to bottom (§12): people, the unknown groups when open, then albums —
        // whichever sections are unfolded.
        val order: List<Any> = (if (ui.peopleOpen) ui.sidebarPeople() else emptyList()) + albums
        if (order.isEmpty()) return
        val at = order.indexOfFirst { item ->
            when (item) {
                is Album -> item.id == ui.selected?.id && ui.showing == null
                else -> item == ui.showing
            }
        }
        val nextAt = when {
            at < 0 -> if (step > 0) 0 else order.lastIndex
            else -> (at + step).coerceIn(0, order.lastIndex)
        }
        when (val item = order[nextAt]) {
            is Showing -> {
                show(item)
                return
            }
        }
        val next = albums.indexOf(order[nextAt] as Album)
        select(albums[next])
        // A key moved the selection, so the list follows it: a click's row is already in view.
        _state.update { ui ->
            val row = ui.rows.indexOfFirst { it is ListEntry.Row && it.album.id == albums[next].id }
            if (row < 0) return@update ui
            val was = ui.listScroll ?: Scroll()
            ui.copy(listScroll = was.copy(reveal = row, moves = was.moves + 1))
        }
    }

    /** The people half of the sidebar, as ↑/↓ walk it. */
    private fun DesktopUi.sidebarPeople(): List<Showing> =
        listedPeople.map { Showing.Person(it.person.id) } +
            if (query.isNotBlank()) {
                emptyList()
            } else {
                (if (people.ignored.isNotEmpty()) listOf(Showing.Ignored) else emptyList()) +
                    if (unknownOpen) people.groups.map { Showing.Group(it.id) } else emptyList()
            }

    /** §2: sub-albums XOR photos — a row with no photos and something beneath it is a container. */
    private fun isContainer(album: Album): Boolean =
        album.photoCount == 0 && listing.tree.any { it.parent == album.id }

    private fun loadThumbnails(album: Album) {
        loadingThumbnails?.cancel()
        loadingThumbnails = scope.launch {
            val loaded = thumbnails.all(album)
            _state.update { if (it.selected?.id == album.id) it.copy(thumbnails = loaded) else it }
        }
    }

    // ---------------------------------------------------------------------------- the maps

    /**
     * The album bar's toggle: the selected album's grid or its map. Kept for the next album too,
     * so ↑/↓ walk the list on whichever the pane is showing.
     */
    public fun togglePhotoMap() {
        _state.update { it.copy(photoMap = !it.photoMap) }
        refreshAlbumMap()
    }

    /**
     * The pane reports the map's size, so frames fit what is actually visible.
     *
     * A frame nobody has moved is fitted again to the size that is real. A camera somebody has
     * moved keeps its centre and zoom, and the list follows the new edges — but only while the
     * library map is on screen: a resize behind an open album must not reshuffle the list under it.
     */
    public fun mapViewport(width: Double, height: Double) {
        if (width <= 0 || height <= 0) return
        viewport = width to height
        _state.update { ui ->
            var next = ui
            ui.libraryView.refitted(width, height)?.let { next = next.copy(libraryView = it) }
            ui.albumView.refitted(width, height)?.let { next = next.copy(albumView = it) }
            val window = ui.inView
            val camera = ui.libraryView.camera
            if (ui.showingLibraryMap && window != null && camera != null && (window.width != width || window.height != height)) {
                next = next.copy(inView = MapWindow(camera, width, height), listScroll = null).listed()
            }
            next
        }
    }

    private fun MapView.refitted(width: Double, height: Double): MapView? {
        val framing = framing?.takeIf { it.width != width || it.height != height } ?: return null
        val refit = framing.copy(width = width, height = height)
        return copy(camera = refit.cameraFor(width, height), moves = moves + 1, framing = refit)
    }

    /**
     * Where the map on screen came to rest after a drag, a wheel step or an animated move:
     * remembered, never animated to — the basemap is already there.
     *
     * On the library map, this is what narrows the list. A report of the camera the model itself
     * set — the basemap's first frame, or the end of a move it was told to make — changes nothing,
     * so the automatic frame never narrows anything.
     */
    public fun cameraMoved(camera: MapCamera): Unit = updateCamera(camera, moved = false)

    /** Moves the map on screen and has the basemap follow: a cluster click, or the control server. */
    public fun moveCamera(camera: MapCamera): Unit = updateCamera(camera, moved = true)

    private fun updateCamera(camera: MapCamera, moved: Boolean) {
        val limits = MapLimits.MIN_ZOOM.toDouble()..MapLimits.MAX_ZOOM.toDouble()
        val clamped = camera.copy(zoom = camera.zoom.coerceIn(limits))
        _state.update { ui ->
            when {
                ui.showingLibraryMap -> {
                    val view = ui.libraryView
                    val was = view.camera ?: return@update ui
                    if (!moved && was.sameView(clamped)) return@update ui
                    val (width, height) = viewport
                    ui.copy(
                        // Moved, so no longer the automatic frame: a later resize must not undo it.
                        libraryView = view.copy(camera = clamped, moves = view.moves + if (moved) 1 else 0, framing = null),
                        inView = MapWindow(clamped, width, height),
                        listScroll = null,
                    ).listed()
                }
                ui.showingAlbumMap -> {
                    val view = ui.albumView
                    val was = view.camera ?: return@update ui
                    if (!moved && was.sameView(clamped)) return@update ui
                    ui.copy(albumView = view.copy(camera = clamped, moves = view.moves + if (moved) 1 else 0, framing = null))
                }
                else -> ui
            }
        }
    }

    /**
     * The chip's ✕: the list is the whole library again, and the map frames it as it did at launch
     * — a frame nobody has moved, so it narrows nothing.
     */
    public fun clearInView() {
        _state.update { ui ->
            if (ui.inView == null) return@update ui
            val (width, height) = viewport
            val map = ui.libraryMap
            val view = if (map == null) {
                ui.libraryView
            } else {
                val framing = Framing(map.pins.map { it.point }, width, height)
                ui.libraryView.copy(camera = framing.cameraFor(width, height), moves = ui.libraryView.moves + 1, framing = framing)
            }
            ui.copy(inView = null, libraryView = view, listScroll = null).listed()
        }
    }

    /**
     * A click on the map on screen (§6, §11).
     *
     * An album's pin selects that album and opens it on its own map; a photo's opens the viewer at
     * it. A cluster zooms until it splits. One that never does — several trips to one town — zooms
     * all the way in, and the list, narrowed to the view, names its albums: the desktop needs no
     * sheet for them. Its photos open the viewer at the earliest, as on the phone.
     */
    public fun tapMap(cluster: Cluster) {
        val ui = _state.value
        val (map, camera) = when {
            ui.showingLibraryMap -> ui.libraryMap to ui.libraryView.camera
            ui.showingAlbumMap -> ui.albumMap to ui.albumView.camera
            else -> return
        }
        if (map == null || camera == null) return
        val pins = cluster.members.map { map.pins.getOrNull(it) ?: return }
        if (pins.isEmpty()) return
        if (cluster.isPin) {
            when (val pin = pins.single()) {
                is MapPin.OfAlbum -> {
                    _state.update { it.copy(photoMap = true) }
                    select(pin.album)
                }
                is MapPin.OfPhoto -> openPhoto(pin.index)
            }
            return
        }
        val splits = map.clusters.expansion(cluster, map.clusters.level(camera.zoom))
        if (splits == null) {
            val photos = pins.filterIsInstance<MapPin.OfPhoto>()
            if (photos.isNotEmpty()) {
                openPhoto(photos.minOf { it.index })
            } else {
                moveCamera(Mercator.camera(cluster.center, MapLimits.MAX_ZOOM.toDouble()))
            }
            return
        }
        val fitted = frame(pins.map { it.point }, viewport.first, viewport.second)
        // At least the level at which it splits: the members' box alone can fit at a zoom where
        // they are still one circle, and the click would look like it did nothing.
        moveCamera(fitted.copy(zoom = max(fitted.zoom, splits.toDouble())))
    }

    /**
     * The library map's pins: every located album that owns photos, whatever its level — a
     * container's centroid lands between its albums (§6) — narrowed by a search or a range, never
     * by the map's own window. Clustered off the caller's thread; the first frame is made once
     * they are.
     */
    private fun refreshLibraryMap() {
        val ui = _state.value
        mappingLibrary?.cancel()
        mappingLibrary = scope.launch {
            val matching = listing.matching(ui.query, ui.range)
            val owning = listing.tree.filter { it.photoCount > 0 && (matching == null || it.id in matching) }
            val pins = located(owning).map { (album, point) -> MapPin.OfAlbum(album, point) }
            val built = ui.libraryMap?.takeIf { it.pins == pins }?.copy(total = owning.size)
                ?: MapUi(pins, ClusterIndex(pins.map { it.point }), owning.size)
            _state.update { current ->
                if (current.query != ui.query || current.range != ui.range) return@update current
                current.copy(libraryMap = built, libraryView = current.libraryView.framedOn(pins))
            }
        }
    }

    /** The selected album's photos where they were taken, while its map is what the pane shows. */
    private fun refreshAlbumMap() {
        val ui = _state.value
        val album = ui.selected ?: return
        if (!ui.photoMap) return
        mappingAlbum?.cancel()
        mappingAlbum = scope.launch {
            val pins = ui.photos.mapIndexedNotNull { index, photo ->
                placed(photo.latitude, photo.longitude)?.let { MapPin.OfPhoto(photo, index, it) }
            }
            val built = ui.albumMap?.takeIf { it.pins == pins }?.copy(total = ui.photos.size)
                ?: MapUi(pins, ClusterIndex(pins.map { it.point }), ui.photos.size)
            _state.update { current ->
                if (current.selected?.id != album.id || current.photos != ui.photos) return@update current
                current.copy(albumMap = built, albumView = current.albumView.framedOn(pins))
            }
        }
    }

    /** A map not framed yet, framed on [pins]; one that is keeps its camera. */
    private fun MapView.framedOn(pins: List<MapPin>): MapView {
        if (camera != null) return this
        val (width, height) = viewport
        val framing = Framing(pins.map { it.point }, width, height)
        return copy(camera = framing.cameraFor(width, height), moves = moves + 1, framing = framing)
    }

    private fun located(albums: List<Album>): List<Pair<Album, WorldPoint>> =
        albums.filter { it.photoCount > 0 }.mapNotNull { album -> placed(album.latitude, album.longitude)?.let { album to it } }

    private fun placed(latitude: Double?, longitude: Double?): WorldPoint? =
        if (latitude != null && longitude != null) Mercator.project(latitude, longitude) else null

    // --------------------------------------------------------------------------- the grid

    public fun tile(size: TileSize): Unit = _state.update { it.copy(tile = size) }

    /** Ctrl+scroll and Ctrl±: one step closer, or one step further away. */
    public fun zoom(closer: Boolean): Unit = _state.update { it.copy(tile = it.tile.step(closer)) }

    /**
     * An arrow key on the grid: the focus moves by [delta] tiles — ±1 across, ±columns down — and
     * the grid scrolls the least that shows it and the row beyond it, [columns] wide, so the page
     * turns a row before the focus reaches its edge. The first press lands on the first tile.
     */
    public fun moveFocus(delta: Int, columns: Int = 0) {
        _state.update { ui ->
            if (ui.photos.isEmpty()) return@update ui
            val next = ui.focus?.let { (it + delta).coerceIn(0, ui.photos.lastIndex) } ?: 0
            val ahead = if (columns > 0 && delta != 0) (next + if (delta > 0) columns else -columns).coerceIn(0, ui.photos.lastIndex) else null
            ui.copy(focus = next, scroll = ui.revealing(next).copy(ahead = ahead))
        }
    }

    /** Where a scroll left the grid: remembered, never scrolled to — the grid is already there. */
    public fun scrolled(key: String?, index: Int, offset: Int) {
        _state.update { ui ->
            val was = ui.scroll ?: Scroll()
            val now = was.copy(key = key, index = index, offset = offset, reveal = null)
            if (now == was) ui else ui.copy(scroll = now)
        }
    }

    /** The grid's scroll as it stands, asked to bring [index] into view. */
    private fun DesktopUi.revealing(index: Int): Scroll {
        val was = scroll ?: Scroll()
        return was.copy(reveal = index, ahead = null, moves = was.moves + 1)
    }

    // ------------------------------------------------------------------------- the viewer

    /** A click on a tile, or Enter on the focused one: the photo opens over the album pane. */
    public fun openPhoto(index: Int) {
        if (index !in _state.value.photos.indices) return
        _state.update { it.copy(open = index, focus = index, preview = null, videoPath = null, livePair = null, drawnBox = null) }
        load(index)
    }

    /** Enter on the grid: the focused photo, or the first when nothing is focused yet. */
    public fun openFocused(): Unit = openPhoto(_state.value.focus ?: 0)

    /** A swipe, or ←/→: another photo in the same album. */
    public fun showPhoto(index: Int) {
        val ui = _state.value
        if (ui.open == null || index == ui.open || index !in ui.photos.indices) return
        _state.update { it.copy(open = index, focus = index, preview = null, videoPath = null, livePair = null, drawnBox = null) }
        load(index)
    }

    public fun step(forward: Boolean) {
        val open = _state.value.open ?: return
        showPhoto(open + if (forward) 1 else -1)
    }

    /**
     * Esc, or the bar's back: the grid again, scrolled the least that shows the photo the viewer
     * was on — a swipe can carry it far from the tiles that were on screen (§6).
     */
    public fun closePhoto() {
        decodingNeighbours?.cancel()
        _state.value.returnTo?.let { from ->
            val face = _state.value.faces.getOrNull(_state.value.faceFocus ?: -1)?.id
            val selection = _state.value.faceSelection
            show(from)
            // Back on the face the photo was opened from, with the selection as it was left.
            _state.update { ui ->
                val at = ui.faces.indexOfFirst { it.id == face }.takeIf { it >= 0 }
                ui.copy(faceFocus = at, faceAnchor = at, faceSelection = selection.filterTo(mutableSetOf()) { id -> ui.faces.any { it.id == id } })
            }
            return
        }
        _state.update { ui ->
            val index = ui.open ?: return@update ui
            ui.copy(
                open = null, preview = null, nearby = emptyMap(), videoPath = null, livePair = null,
                drawing = false, drawnBox = null,
                focus = index, scroll = ui.revealing(index),
            )
        }
    }

    private fun load(index: Int) {
        val photos = _state.value.photos
        val photo = photos.getOrNull(index) ?: return
        loadMotion(photo)
        val cached = previews.cached(photo)
        if (cached != null) {
            _state.update { if (it.isOpen(photo)) it.copy(preview = cached) else it }
            decodeNeighbours(index, photos)
            return
        }
        scope.launch {
            val loaded = previews.load(photo)
            _state.update { if (it.isOpen(photo)) it.copy(preview = loaded) else it }
            previews.prefetch(photos, index)
            decodeNeighbours(index, photos)
        }
    }

    /** The photo either side, decoded into [DesktopUi.nearby] once the open one is showing. */
    private fun decodeNeighbours(index: Int, photos: List<PhotoRow>) {
        val keep = ((index - 1)..(index + 1)).mapNotNull { photos.getOrNull(it)?.id }.toSet()
        decodingNeighbours?.cancel()
        decodingNeighbours = scope.launch {
            for (i in listOf(index + 1, index - 1)) {
                val photo = photos.getOrNull(i) ?: continue
                val decoded = previews.cached(photo) ?: previews.load(photo) ?: continue
                _state.update { ui ->
                    if (ui.open == null) ui
                    else ui.copy(nearby = ui.nearby.filterKeys { it in keep } + (photo.id to decoded))
                }
            }
        }
    }

    /** A video's file, or a Live Photo's pair: both already on disk, so only looked up. */
    private fun loadMotion(photo: PhotoRow) {
        when (photo.mediaType) {
            MediaType.VIDEO -> scope.launch {
                val path = videos.localFile(photo)
                _state.update { if (it.isOpen(photo)) it.copy(videoPath = path) else it }
            }
            MediaType.LIVE_PHOTO -> scope.launch {
                val pair = videos.livePair(photo)
                _state.update { if (it.isOpen(photo)) it.copy(livePair = pair) else it }
            }
            MediaType.PHOTO -> Unit
        }
    }

    private fun DesktopUi.isOpen(photo: PhotoRow): Boolean =
        open?.let { photos.getOrNull(it)?.id } == photo.id

    // ------------------------------------------------------------------------- people (§12)

    /** A click on a person or an unknown group in the sidebar: its faces in the album pane. */
    public fun show(what: Showing) {
        previews.cancelPrefetch()
        decodingNeighbours?.cancel()
        _state.update { ui ->
            val faces = ui.people.facesFor(what)
            ui.copy(
                showing = what, faces = faces,
                selected = null, photos = emptyList(), thumbnails = emptyMap(),
                focus = null, scroll = null, open = null, preview = null, nearby = emptyMap(),
                videoPath = null, livePair = null, returnTo = null, naming = null,
                faceFocus = null, faceAnchor = null, faceMenu = null,
                // A group opens with every face selected: a group is mostly one person (§12).
                faceSelection = if (what is Showing.Group) faces.mapTo(mutableSetOf(), Face::id) else emptySet(),
            )
        }
        loadCrops()
    }

    /** → and ← on "Unknown", or a click on its chevron. */
    /** A click on the sidebar's PEOPLE heading: the section folds away, or unfolds. */
    public fun togglePeople(): Unit = _state.update { it.copy(peopleOpen = !it.peopleOpen) }

    /** A click on the sidebar's ALBUMS heading. */
    public fun toggleAlbums(): Unit = _state.update { it.copy(albumsOpen = !it.albumsOpen) }

    public fun expandUnknown(open: Boolean) {
        _state.update { it.copy(unknownOpen = open) }
        if (open) loadCrops()
    }

    /**
     * An arrow key on a face grid. With [extend] — Shift held — the selection runs from where it
     * was anchored to the new focus; without, the focus moves alone.
     */
    public fun moveFaceFocus(delta: Int, extend: Boolean) {
        _state.update { ui ->
            if (ui.faces.isEmpty()) return@update ui
            val from = ui.faceFocus
            val next = from?.let { (it + delta).coerceIn(0, ui.faces.lastIndex) } ?: 0
            if (!extend) return@update ui.copy(faceFocus = next, faceAnchor = next)
            val anchor = ui.faceAnchor ?: from ?: next
            ui.copy(faceFocus = next, faceAnchor = anchor, faceSelection = ui.range(anchor, next))
        }
    }

    /** A click on a face: alone, as a range from the anchor (Shift), or toggled (Ctrl). */
    public fun clickFace(index: Int, range: Boolean, toggle: Boolean) {
        _state.update { ui ->
            val face = ui.faces.getOrNull(index) ?: return@update ui
            when {
                range -> ui.copy(faceFocus = index, faceSelection = ui.range(ui.faceAnchor ?: index, index))
                toggle -> ui.copy(faceFocus = index, faceAnchor = index, faceSelection = ui.faceSelection.toggled(face.id))
                else -> ui.copy(faceFocus = index, faceAnchor = index, faceSelection = setOf(face.id))
            }
        }
    }

    public fun selectAllFaces(): Unit = _state.update { ui -> ui.copy(faceSelection = ui.faces.mapTo(mutableSetOf(), Face::id)) }

    /** Esc on a face grid, before it leaves the grid. False when there was nothing to clear. */
    public fun clearFaceSelection(): Boolean {
        if (_state.value.faceSelection.isEmpty()) return false
        _state.update { it.copy(faceSelection = emptySet()) }
        return true
    }

    /**
     * Enter on a person: the selection is confirmed as them — or, when all of it already is, the
     * confirmations are withdrawn.
     */
    public fun confirmChosen() {
        val ui = _state.value
        // On "Ignored", Enter takes the ignore back: the faces go to whatever the next sync makes of them.
        if (ui.showing == Showing.Ignored) {
            val chosen = ui.chosenFaces()
            if (chosen.isNotEmpty()) decide { people.clear(chosen) }
            return
        }
        val person = (ui.showing as? Showing.Person)?.id ?: return
        val chosen = ui.chosenFaces()
        if (chosen.isEmpty()) return
        if (chosen.all { it.state == FaceState.CONFIRMED }) decide { people.clear(chosen) }
        else decide { people.confirm(chosen.filter { it.state != FaceState.CONFIRMED }, person) }
    }

    /**
     * A right-click on a face: the menu is about the selection when the face is in it, and about
     * that face alone — now selected — when it is not, the way a file manager's is.
     */
    public fun contextFace(index: Int? = null) {
        _state.update { ui ->
            val at = index ?: ui.faceFocus ?: return@update ui
            val face = ui.faces.getOrNull(at) ?: return@update ui
            if (face.id in ui.faceSelection) ui.copy(faceFocus = at, faceMenu = at)
            else ui.copy(faceFocus = at, faceAnchor = at, faceSelection = setOf(face.id), faceMenu = at)
        }
    }

    public fun closeFaceMenu(): Unit = _state.update { it.copy(faceMenu = null) }

    /** The right-click menu's name: the selection is [person] — or a new person called [name]. */
    public fun nameChosen(person: Uuid?, name: String? = null) {
        val chosen = _state.value.chosenFaces()
        if (chosen.isEmpty()) return
        val typed = name?.trim().orEmpty()
        if (person == null && typed.isEmpty()) return
        decide {
            val existing = person ?: _state.value.people.people.map { it.person }
                .firstOrNull { it.name.equals(typed, ignoreCase = true) }?.id
            people.confirm(chosen, existing ?: people.createPerson(typed).id)
        }
    }

    /** Space on a group: the focused face goes in or out of the selection the name is given to. */
    public fun toggleFocused(): Unit = _state.update { now ->
        val face = now.faces.getOrNull(now.faceFocus ?: return@update now) ?: return@update now
        now.copy(faceSelection = now.faceSelection.toggled(face.id))
    }

    /** N on a person: the selection is not them. */
    public fun rejectChosen() {
        val ui = _state.value
        val person = (ui.showing as? Showing.Person)?.id ?: return
        val chosen = ui.chosenFaces()
        if (chosen.isNotEmpty()) decide { people.reject(chosen, person) }
    }

    /** I: nobody worth naming. */
    public fun ignoreChosen() {
        val chosen = _state.value.chosenFaces()
        if (chosen.isNotEmpty()) decide { people.ignore(chosen) }
    }

    /** Typing on a group's grid opens the name field with what was typed. */
    public fun editName(text: String?): Unit = _state.update { it.copy(naming = text) }

    /**
     * Enter in the name field: the selected faces are [name] — the person of that name when there
     * is one, ignoring case, or a new one. The faces left out stay in the group.
     */
    public fun submitName(name: String? = null) {
        val ui = _state.value
        val typed = (name ?: ui.naming)?.trim().orEmpty()
        val chosen = ui.faces.filter { it.id in ui.faceSelection }
        if (typed.isEmpty() || chosen.isEmpty()) return
        _state.update { it.copy(naming = null) }
        decide {
            val existing = ui.people.people.map { it.person }.firstOrNull { it.name.equals(typed, ignoreCase = true) }
            people.confirm(chosen, (existing ?: people.createPerson(typed)).id)
        }
    }

    public fun renamePerson(person: Uuid, name: String) {
        if (name.isBlank()) return
        decide { people.rename(person, name) }
    }

    /** Merge into…: [from]'s faces become [into]'s, and [from] is gone — so is its pane. */
    public fun mergePerson(from: Uuid, into: Uuid) {
        decide { people.merge(from, into) }
        show(Showing.Person(into))
    }

    /** Enter on a face grid: its photo, opened in its album; Esc comes back here. */
    public fun openFace(index: Int? = null) {
        val ui = _state.value
        val at = index ?: ui.faceFocus ?: return
        val face = ui.faces.getOrNull(at) ?: return
        val album = catalog.album(face.albumId) ?: return
        val from = ui.showing
        val photos = catalog.photos(album.id)
        val photo = photos.indexOfFirst { it.id == face.photoId }.takeIf { it >= 0 } ?: return
        mappingAlbum?.cancel()
        _state.update {
            it.copy(
                selected = album, photos = photos, thumbnails = emptyMap(), scroll = null,
                albumView = MapView(), albumMap = null,
                faceFocus = at, returnTo = from, faceBoxes = true,
            )
        }
        loadThumbnails(album)
        refreshAlbumMap()
        openPhoto(photo)
    }

    /** F in the viewer: the open photo's face boxes, on or off. */
    public fun toggleFaceBoxes(): Unit = _state.update { it.copy(faceBoxes = !it.faceBoxes, drawing = false, drawnBox = null) }

    /**
     * D in the viewer: drawing a face the detector missed, on or off. The boxes come on with it —
     * the faces already found are what not to draw over.
     */
    public fun toggleDrawing(): Unit = _state.update { ui ->
        if (ui.open == null) ui
        else ui.copy(drawing = !ui.drawing, faceBoxes = ui.faceBoxes || !ui.drawing, drawnBox = null)
    }

    /** A drag let go over the photo: [box], as fractions of it, waits for a name. */
    public fun drawn(box: FaceBox): Unit = _state.update { if (it.drawing) it.copy(drawnBox = box) else it }

    /** Esc, or a click away from the naming menu: the drawn box is dropped. */
    public fun cancelDrawn(): Unit = _state.update { it.copy(drawnBox = null) }

    /**
     * The drawn box is [person] — or a new person called [name]. A confirmation like any other: it
     * shows at once, Ctrl+Z takes it back, and the next sync looks inside it for the face (§12).
     */
    public fun nameDrawn(person: Uuid?, name: String? = null) {
        val ui = _state.value
        val box = ui.drawnBox ?: return
        val photo = ui.open?.let { ui.photos.getOrNull(it) } ?: return
        val album = ui.selected?.id ?: return
        val typed = name?.trim().orEmpty()
        if (person == null && typed.isEmpty()) return
        val face = Face(Uuid.random(), album, photo.id, box, 0f, FaceState.UNKNOWN, null, null, null, drawn = true)
        _state.update { it.copy(drawnBox = null) }
        decide {
            val existing = person ?: ui.people.people.map { it.person }
                .firstOrNull { it.name.equals(typed, ignoreCase = true) }?.id
            people.confirm(listOf(face), existing ?: people.createPerson(typed).id)
        }
    }

    /** A choice from a face box's menu in the viewer. */
    public fun decideOnPhoto(face: Face, choice: FaceChoice) {
        decide {
            when (choice) {
                is FaceChoice.Is -> people.confirm(listOf(face), choice.person)
                is FaceChoice.IsNot -> people.reject(listOf(face), choice.person)
                is FaceChoice.Named -> {
                    val typed = choice.name.trim()
                    if (typed.isEmpty()) return@decide null
                    val existing = _state.value.people.people.map { it.person }.firstOrNull { it.name.equals(typed, ignoreCase = true) }
                    people.confirm(listOf(face), (existing ?: people.createPerson(typed)).id)
                }
                FaceChoice.Ignore -> people.ignore(listOf(face))
                FaceChoice.Clear -> people.clear(listOf(face))
            }
        }
    }

    /**
     * A decision, written to the labels file and then read back — so what is drawn is always the
     * file, never a guess at what the write did. The selection keeps whichever faces are still in
     * the pane, and the focus stays on its face where that face still is.
     */
    private fun decide(write: () -> Any?) {
        try {
            val change = write()
            if (change is Labels.Change && !change.isEmpty) history.addLast(change)
        } catch (failure: Exception) {
            _state.update { it.copy(failure = "Could not save: ${failure.message ?: failure}") }
            return
        }
        val snapshot = readPeople()
        // The decision is made: the selection it was about is done with, and the focus moves on to
        // the face that came after it — the next suggestion to look at — rather than following a
        // confirmed face down to the confirmed ones.
        _state.update { ui ->
            val decided = ui.chosenFaces().mapTo(mutableSetOf(), Face::id)
            val last = ui.faces.indexOfLast { it.id in decided }
            val after = ui.faces.drop(last + 1).firstOrNull { it.id !in decided }?.id
            val next = ui.withPeople(snapshot)
            val at = next.faces.indexOfFirst { it.id == after }.takeIf { it >= 0 }
                ?: ui.faceFocus?.coerceAtMost(next.faces.lastIndex)?.takeIf { it >= 0 }
            next.copy(
                failure = null,
                faceSelection = if (ui.showing is Showing.Group) next.faceSelection else emptySet(),
                faceFocus = at ?: next.faceFocus,
                faceAnchor = at ?: next.faceAnchor,
            )
        }
        loadCrops()
    }

    private fun readPeople(): PeopleSnapshot =
        try {
            people.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PeopleSnapshot.EMPTY
        }

    /** [snapshot] in, and the pane's faces re-read from it, keeping focus and selection by id. */
    private fun DesktopUi.withPeople(snapshot: PeopleSnapshot): DesktopUi {
        val what = showing?.takeIf { it !is Showing.Person || snapshot.person(it.id) != null }
        val faces = what?.let { snapshot.facesFor(it) }.orEmpty()
        val focused = faceFocus?.let { this.faces.getOrNull(it)?.id }
        val ids = faces.mapTo(mutableSetOf(), Face::id)
        val focus = faces.indexOfFirst { it.id == focused }.takeIf { it >= 0 }
            ?: faceFocus?.coerceAtMost(faces.lastIndex)?.takeIf { it >= 0 }
        return copy(
            people = snapshot,
            showing = what,
            faces = faces,
            faceFocus = focus,
            faceAnchor = focus,
            faceSelection = faceSelection.filterTo(mutableSetOf()) { it in ids },
        )
    }

    private fun PeopleSnapshot.facesFor(what: Showing): List<Face> = when (what) {
        is Showing.Person -> facesOf(what.id)
        is Showing.Group -> facesIn(what.id)
        Showing.Ignored -> ignored
    }

    /** The selection, or the focused face when nothing is selected. */
    private fun DesktopUi.chosenFaces(): List<Face> {
        val selected = faces.filter { it.id in faceSelection }
        if (selected.isNotEmpty()) return selected
        return listOfNotNull(faceFocus?.let { faces.getOrNull(it) })
    }

    private fun DesktopUi.range(from: Int, to: Int): Set<Uuid> =
        (minOf(from, to)..maxOf(from, to)).mapNotNullTo(mutableSetOf()) { faces.getOrNull(it)?.id }

    private fun Set<Uuid>.toggled(id: Uuid): Set<Uuid> = if (id in this) this - id else this + id

    /** The pane's crops and the sidebar's avatars, cut once each and kept. */
    /**
     * The pane's faces first, in the order they are drawn, then the sidebar's avatars — and the
     * unknown groups' only while "Unknown" is open: a large library has hundreds of groups, and
     * their samples queued ahead of the pane left it grey. [CROP_JOBS] at a time, each shown the
     * moment it is cut.
     */
    private fun loadCrops() {
        loadingCrops?.cancel()
        loadingCrops = scope.launch {
            val ui = _state.value
            val samples = if (ui.unknownOpen) ui.people.groups.map { it.sample } else emptyList()
            val wanted = (ui.faces + ui.people.people.mapNotNull { it.avatar } + samples)
                .distinctBy(Face::id).filter { it.id !in ui.crops }
            val queue = Channel<Face>(Channel.UNLIMITED)
            wanted.forEach(queue::trySend)
            queue.close()
            coroutineScope {
                repeat(CROP_JOBS) {
                    launch {
                        for (face in queue) {
                            val jpeg = crops.crop(face) ?: continue
                            _state.update { it.copy(crops = it.crops + (face.id to jpeg)) }
                        }
                    }
                }
            }
        }
    }

    private var loadingCrops: Job? = null

    /** Every decision made since the viewer started, newest last — what Ctrl+Z walks back. */
    private val history = ArrayDeque<Labels.Change>()

    /**
     * Ctrl+Z: the last decision taken back — a confirmation, a "not them", an ignore, a withdrawal.
     * The focus goes to the first face it was about. People created and names changed stay: a
     * person created by mistake is merged away (§12).
     */
    public fun undo() {
        val change = history.removeLastOrNull() ?: return
        try {
            people.revert(change)
        } catch (failure: Exception) {
            _state.update { it.copy(failure = "Could not undo: ${failure.message ?: failure}") }
            return
        }
        val snapshot = readPeople()
        val about = (change.added + change.removed).map { it.photoId to it.box }
        _state.update { ui ->
            val next = ui.withPeople(snapshot)
            val at = next.faces.indexOfFirst { face -> about.any { (photo, box) -> face.photoId == photo && face.box.overlap(box) >= Labels.SAME_FACE_OVERLAP } }
                .takeIf { it >= 0 }
            next.copy(failure = null, faceFocus = at ?: next.faceFocus, faceAnchor = at ?: next.faceAnchor, faceSelection = emptySet())
        }
        loadCrops()
    }

    private companion object {
        /** A laptop's album pane, in dp, until the real one reports. */
        val DEFAULT_VIEWPORT = 1100.0 to 760.0

        /** Crops cut at once: each is a decode of an original, and they no longer wait on each other. */
        const val CROP_JOBS = 4
    }
}

/** What the album pane shows instead of an album (§12). */
public sealed interface Showing {
    public data class Person(val id: Uuid) : Showing
    public data class Group(val id: Int) : Showing

    /** Every ignored face, for review: at the foot of the people (§12). */
    public data object Ignored : Showing
}

/** A face box's menu in the viewer (§12). */
public sealed interface FaceChoice {
    public data class Is(val person: Uuid) : FaceChoice
    public data class IsNot(val person: Uuid) : FaceChoice
    public data class Named(val name: String) : FaceChoice
    public data object Ignore : FaceChoice
    /** Withdraw a confirmation. */
    public data object Clear : FaceChoice
}

/** A library `sync` has not indexed yet: nobody, and nothing to decide about. */
private object NoPeople : People {
    private val nothing = Labels.Change(emptyList(), emptyList())
    override fun read(): PeopleSnapshot = PeopleSnapshot.EMPTY
    override fun createPerson(name: String): Person = error("no people in this viewer")
    override fun rename(person: Uuid, name: String): Unit = Unit
    override fun merge(from: Uuid, into: Uuid): Unit = Unit
    override fun confirm(faces: List<Face>, person: Uuid): Labels.Change = nothing
    override fun reject(faces: List<Face>, person: Uuid): Labels.Change = nothing
    override fun ignore(faces: List<Face>): Labels.Change = nothing
    override fun clear(faces: List<Face>): Labels.Change = nothing
    override fun revert(change: Labels.Change): Unit = Unit
}
