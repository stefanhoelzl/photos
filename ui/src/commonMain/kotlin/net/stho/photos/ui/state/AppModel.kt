package net.stho.photos.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow

/**
 * Everything on screen, as one immutable snapshot.
 *
 * `StateFlow` rather than Compose snapshot state: this package imports no UI framework, so the
 * part that can be wrong is testable with `runTest` and nothing else — and the same flow would
 * serve a different renderer unchanged.
 */
public data class AppUi(
    val stack: BackStack = BackStack(),
    val sort: AlbumSort = AlbumSort.DateNewest,
    val query: String = "",
    val albums: List<Album> = emptyList(),
    /** The open album's photos, in §3's order. Empty on every screen that is not a grid. */
    val photos: List<PhotoRow> = emptyList(),
    /**
     * False while this album's pack is still downloading.
     *
     * The grid draws `photos.size` placeholders then — which is exactly what tells it apart
     * from §10's zero-photo album, where there are no tiles at all.
     */
    val packReady: Boolean = true,
    /**
     * Grid density (§6's pinch): 4 columns, or 2 for a closer look.
     *
     * Both densities read the same 256px pack thumbs — the 2-column tile is simply upscaled.
     * §5 left this open and handed it to §6: fetching a 385 KB preview per tile would make the
     * grid the one screen that needs the network, a loading state and a prefetch pipeline.
     * Softness is the price, and the grid stays a pure local read.
     */
    val columns: Int = 4,
    /** The decoded preview for the open photo, once it has been fetched (§6's browse-to-cache). */
    val preview: Preview? = null,
    /** The open photo's transcode, once fetched — null for a still, or while it downloads. */
    val videoPath: String? = null,
    /** The open album's thumbnails, keyed by `PhotoRow.id`. Loaded off the UI thread. */
    val thumbnails: Map<Uuid, ByteArray> = emptyMap(),
    val sync: SyncStatus = SyncStatus.Never,
    val notice: Notice? = null,
    /** The library's size, straight from the catalog — not from whatever the last sync did. */
    val totals: Totals = Totals(0, 0),
    val packsDone: Int = 0,
    val packsOutstanding: Int = 0,
) {
    val screen: Screen get() = stack.current

    /** The nav bar's second line: a count, then the sort state, so no menu has to name it (§6). */
    public val subtitle: String
        get() = when {
            query.isNotEmpty() -> "${albums.size} matching"
            // While packs are still arriving the line says so, exactly as the mockup does:
            // the covers filling in one by one otherwise look like something going wrong.
            packsOutstanding > 0 -> "${albums.size} albums · fetching thumbnails $packsDone/${packsDone + packsOutstanding}"
            else -> "${albums.size} albums · ${sort.label}"
        }

    /** True on a first run, when there is nothing to show yet and something is on its way. */
    public val loading: Boolean get() = albums.isEmpty() && sync is SyncStatus.Running
}

/**
 * The album list, its sort, its search, and the sync that feeds it.
 *
 * One model rather than one per screen, because every E.1 screen reads the same catalog and
 * §6's chrome is shared: the sort icon and the search field belong to the album list *and* to
 * a container, which is the same list one level down.
 */
public class AppModel(
    private val catalog: Catalog,
    private val syncer: Syncer,
    private val thumbnails: Thumbnails,
    private val previews: Previews,
    private val videos: Videos,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow(AppUi())
    public val state: StateFlow<AppUi> = _state.asStateFlow()

    /** Reads what is already on disk, then syncs. The catalog is local, so nothing waits. */
    public fun start() {
        reload()
        refresh()
        // A pack landing changes what is already on screen -- a placeholder cover becomes a
        // photograph -- so the model reloads rather than leaving the UI to poll.
        scope.launch {
            thumbnails.arrivals.collect { done ->
                _state.update { it.copy(packsDone = done, packsOutstanding = thumbnails.outstanding.value) }
                reload()
            }
        }
    }

    public fun refresh() {
        scope.launch {
            _state.update { it.copy(sync = SyncStatus.Running(0, 0)) }
            val outcome = syncer.sync { fetched, total ->
                _state.update { it.copy(sync = SyncStatus.Running(fetched, total)) }
            }
            when (outcome) {
                is SyncOutcome.Succeeded -> {
                    // One update, not two. Setting the status and *then* reloading leaves a
                    // window where the sync reads as finished while the list is still empty --
                    // which is exactly what a screen, or a test, would sample and believe.
                    val status = SyncStatus.Succeeded(clock.now(), outcome.albums, outcome.photos)
                    _state.update { it.copy(sync = status).reloaded() }
                }
                is SyncOutcome.Failed -> _state.update {
                    // Never blocks: the catalog and every thumbnail are already on disk, so a
                    // failed sync must not stop you browsing (§6).
                    it.copy(sync = SyncStatus.Failed(clock.now(), outcome.notice), notice = outcome.notice)
                }
            }
        }
    }

    /**
     * Pinch on iOS, ctrl+scroll on the desktop — one gesture, two bindings.
     *
     * §6 said this could not be exercised on the desktop at all. It can: ctrl+scroll is what
     * every desktop app uses as a pinch, so the hardest drawn layout in §6 is reviewable here
     * rather than being written blind for a device nobody can run.
     */
    public fun density(closer: Boolean) {
        _state.update { it.copy(columns = if (closer) 2 else 4) }
    }

    public fun cycleSort() {
        _state.update { it.copy(sort = it.sort.next()) }
        reload()
    }

    public fun search(text: String) {
        _state.update { it.copy(query = text) }
        reload()
    }

    public fun open(album: Album) {
        // Tapping is what promotes an album's pack: the queue's order becomes what the person
        // is actually looking at (§6).
        thumbnails.prioritise(album)
        val screen =
            // §2: an album has sub-albums XOR photos, never both -- so the row it was tapped on
            // already says which screen this is, and no probe is needed.
            if (album.photoCount == 0 && catalog.albums(album.id).isNotEmpty()) {
                Screen.Container(album.id, album.name)
            } else {
                Screen.Grid(album.id, album.name)
            }
        navigate { it.push(screen) }
    }

    /** Opening a photo from the grid. The album's order is what [index] indexes. */
    public fun openPhoto(index: Int) {
        val screen = state.value.screen
        if (screen !is Screen.Grid) return
        navigate { it.push(Screen.Photo(screen.albumId, screen.name, index)) }
    }

    /** Swiping, or tapping the filmstrip. */
    public fun showPhoto(index: Int) {
        val screen = state.value.screen
        if (screen !is Screen.Photo || index !in state.value.photos.indices) return
        _state.update {
            it.copy(stack = it.stack.replace(screen.copy(index = index)), preview = null, videoPath = null)
        }
        loadPreview(index)
    }

    public fun back(): Unit = navigate { it.pop() }

    public fun openSettings(): Unit = navigate { it.push(Screen.Settings) }

    /** Used by the control server's `POST /nav` as well as by the UI. */
    public fun navigate(change: (BackStack) -> BackStack) {
        val leaving = _state.value.screen
        _state.update { it.copy(stack = change(it.stack), query = "", preview = null, videoPath = null) }
        // Leaving the album abandons the prefetch queue; staying inside it (grid ↔ photo)
        // keeps the pack and whatever has already been fetched.
        if (_state.value.screen.albumOf() != leaving.albumOf()) {
            previews.cancelPrefetch()
            _state.update { it.copy(thumbnails = emptyMap()) }
        }
        reload()
    }

    private fun Screen.albumOf(): Uuid? = when (this) {
        is Screen.Grid -> albumId
        is Screen.Photo -> albumId
        is Screen.Container -> albumId
        else -> null
    }

    public fun dismissNotice(): Unit = _state.update { it.copy(notice = null) }

    private fun reload(): Unit = _state.update { it.reloaded() }

    /** What this state becomes once the catalog is re-read. Pure enough to fold into an update. */
    private fun AppUi.reloaded(): AppUi {
        val current = this
        return run {
            when (val screen = current.screen) {
                is Screen.Photo -> {
                    val photos = catalog.photos(screen.albumId)
                    val album = catalog.album(screen.albumId)
                    if (album != null && this@AppModel.thumbnails.has(album) && current.thumbnails.isEmpty()) {
                        loadThumbnails(album)
                    }
                    if (current.preview == null) loadPreview(screen.index, photos)
                    current.copy(albums = emptyList(), photos = photos, packReady = true)
                }

                is Screen.Grid -> {
                    val album = catalog.album(screen.albumId)
                    val ready = album != null && this@AppModel.thumbnails.has(album)
                    if (ready && current.thumbnails.isEmpty()) loadThumbnails(album)
                    current.copy(
                        albums = emptyList(),
                        photos = catalog.photos(screen.albumId),
                        packReady = ready,
                    )
                }

                else -> {
                    val albums = when {
                        current.query.isNotBlank() -> catalog.search(current.query)
                        else -> catalog.albums(under = screen.parentAlbum())
                    }
                    current.copy(
                        albums = current.sort.sorted(albums),
                        photos = emptyList(),
                        thumbnails = emptyMap(),
                        packReady = true,
                        totals = catalog.totals(),
                    )
                }
            }
        }
    }

    /**
     * A pack is up to ~20 MB (§3), so reading one is a file read and not a lookup — it belongs
     * off the thread that is drawing. The grid renders placeholders until this lands, which is
     * the same state it shows while the pack is still downloading.
     */
    private fun loadThumbnails(album: net.stho.photos.catalog.Album) {
        scope.launch {
            val loaded = thumbnails.all(album)
            _state.update { if (it.screen is Screen.Grid) it.copy(thumbnails = loaded) else it }
        }
    }

    /**
     * The preview a swipe shows, plus §6's ±3 either side.
     *
     * Cancelled when the album is left rather than when the photo changes: a fast scrub back
     * and forth would otherwise abandon exactly the fetches it is about to need again.
     */
    private fun loadPreview(index: Int, photos: List<PhotoRow> = state.value.photos) {
        val photo = photos.getOrNull(index) ?: return
        previews.cached(photo)?.let { cached ->
            if (photo.mediaType == MediaType.VIDEO) loadVideo(photo)
            _state.update { it.copy(preview = cached) }
            previews.prefetch(photos, index)
            return
        }
        if (photo.mediaType == MediaType.VIDEO) loadVideo(photo)
        scope.launch {
            val loaded = previews.load(photo)
            _state.update { current ->
                val screen = current.screen
                if (screen is Screen.Photo && photos.getOrNull(screen.index)?.id == photo.id) {
                    current.copy(preview = loaded)
                } else {
                    current
                }
            }
            previews.prefetch(photos, index)
        }
    }

    /** §5's transcode, fetched on demand: the poster is already showing while this arrives. */
    private fun loadVideo(photo: PhotoRow) {
        scope.launch {
            val path = videos.localFile(photo)
            _state.update { current ->
                val screen = current.screen
                val open = (screen as? Screen.Photo)?.let { current.photos.getOrNull(it.index) }
                if (open?.id == photo.id) current.copy(videoPath = path) else current
            }
        }
    }

    private fun Screen.parentAlbum(): Uuid? = when (this) {
        is Screen.Container -> albumId
        else -> null
    }
}
