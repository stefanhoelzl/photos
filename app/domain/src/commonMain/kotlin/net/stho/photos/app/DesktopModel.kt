package net.stho.photos.app

import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.stho.photos.catalog.Album
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
 * No back stack: the sidebar is always there, the album pane shows one album, and the only thing
 * that opens over it is a photo. Nothing here is remembered between launches.
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
) {
    /** A name search or a date range is narrowing the list. */
    public val filtering: Boolean get() = query.isNotEmpty() || range != null

    /** Every album on the sidebar, containers included, in the order they are drawn. */
    public val albums: List<Album> get() = rows.mapNotNull { (it as? ListEntry.Row)?.album }

    /** The sidebar's second line: a count, then the sort, so no menu has to name it (§6). */
    public val albumsSubtitle: String
        get() = if (filtering) "$matched matching" else "${albums.size} albums · ${sort.label}"

    /** The album pane's second line: how many photos, and when they were taken. */
    public val photosSubtitle: String
        get() {
            val album = selected ?: return ""
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
 * The desktop viewer's model (§11): the album list, one selected album, and a photo open over it.
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
) {
    private val listing = AlbumListing(catalog)

    private val _state = MutableStateFlow(DesktopUi())
    public val state: StateFlow<DesktopUi> = _state.asStateFlow()

    private var rebuilding: Job? = null
    private var loadingThumbnails: Job? = null
    private var decodingNeighbours: Job? = null

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
                ).listed()
            }
            selected?.let(::loadThumbnails)
        }
    }

    // ---------------------------------------------------------------------------- the list

    /** A new order: the sidebar's rows move, and the selection stays what it was. */
    public fun cycleSort(): Unit = _state.update { it.copy(sort = it.sort.next(), listScroll = null).listed() }

    /** Typing into the field. One filter at a time: text replaces a date range. */
    public fun search(text: String): Unit = _state.update {
        it.copy(query = text, range = if (text.isEmpty()) it.range else null, listScroll = null).listed()
    }

    public fun openCalendar(): Unit = _state.update { it.copy(calendar = listing.calendar()) }

    public fun closeCalendar(): Unit = _state.update { it.copy(calendar = null) }

    /** The sheet's Apply. Refused — false, and nothing changes — for a range with no photos in it. */
    public fun applyRange(range: DateRange): Boolean {
        if (!listing.hasPhotosIn(range)) return false
        _state.update { it.copy(range = range, query = "", calendar = null, listScroll = null).listed() }
        return true
    }

    public fun clearRange(): Unit = _state.update { it.copy(range = null, listScroll = null).listed() }

    /** Where a scroll left the sidebar: remembered, never scrolled to — it is already there. */
    public fun listScrolled(key: String?, index: Int, offset: Int) {
        _state.update { ui ->
            val was = ui.listScroll ?: Scroll()
            val now = was.copy(key = key, index = index, offset = offset, reveal = null)
            if (now == was) ui else ui.copy(listScroll = now)
        }
    }

    private fun DesktopUi.listed(): DesktopUi {
        val listed = listing.rows(sort, query, range)
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
        _state.update {
            it.copy(
                selected = album, photos = photos, thumbnails = emptyMap(),
                focus = null, scroll = null, open = null, preview = null, nearby = emptyMap(),
                videoPath = null, livePair = null,
            )
        }
        loadThumbnails(album)
    }

    /** ↑/↓ in the sidebar: the next album up or down the list, skipping headers. */
    public fun selectAdjacent(step: Int) {
        val ui = _state.value
        val albums = ui.rows.filterIsInstance<ListEntry.Row>().filterNot { it.header }.map { it.album }
        if (albums.isEmpty()) return
        val at = albums.indexOfFirst { it.id == ui.selected?.id }
        val next = when {
            at < 0 -> if (step > 0) 0 else albums.lastIndex
            else -> (at + step).coerceIn(0, albums.lastIndex)
        }
        select(albums[next])
        // A key moved the selection, so the list follows it: a click's row is already in view.
        _state.update { ui ->
            val row = ui.rows.indexOfFirst { it is ListEntry.Row && it.album.id == albums[next].id }
            if (row < 0) return@update ui
            val was = ui.listScroll ?: Scroll()
            ui.copy(listScroll = was.copy(reveal = row, moves = was.moves + 1))
        }
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

    // --------------------------------------------------------------------------- the grid

    public fun tile(size: TileSize): Unit = _state.update { it.copy(tile = size) }

    /** Ctrl+scroll and Ctrl±: one step closer, or one step further away. */
    public fun zoom(closer: Boolean): Unit = _state.update { it.copy(tile = it.tile.step(closer)) }

    /**
     * An arrow key on the grid: the focus moves by [delta] tiles — ±1 across, ±columns down — and
     * the grid scrolls the least that shows it. The first press lands on the first tile.
     */
    public fun moveFocus(delta: Int) {
        _state.update { ui ->
            if (ui.photos.isEmpty()) return@update ui
            val next = ui.focus?.let { (it + delta).coerceIn(0, ui.photos.lastIndex) } ?: 0
            ui.copy(focus = next, scroll = ui.revealing(next))
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
        return was.copy(reveal = index, moves = was.moves + 1)
    }

    // ------------------------------------------------------------------------- the viewer

    /** A click on a tile, or Enter on the focused one: the photo opens over the album pane. */
    public fun openPhoto(index: Int) {
        if (index !in _state.value.photos.indices) return
        _state.update { it.copy(open = index, focus = index, preview = null, videoPath = null, livePair = null) }
        load(index)
    }

    /** Enter on the grid: the focused photo, or the first when nothing is focused yet. */
    public fun openFocused(): Unit = openPhoto(_state.value.focus ?: 0)

    /** A swipe, or ←/→: another photo in the same album. */
    public fun showPhoto(index: Int) {
        val ui = _state.value
        if (ui.open == null || index == ui.open || index !in ui.photos.indices) return
        _state.update { it.copy(open = index, focus = index, preview = null, videoPath = null, livePair = null) }
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
        _state.update { ui ->
            val index = ui.open ?: return@update ui
            ui.copy(
                open = null, preview = null, nearby = emptyMap(), videoPath = null, livePair = null,
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
}
