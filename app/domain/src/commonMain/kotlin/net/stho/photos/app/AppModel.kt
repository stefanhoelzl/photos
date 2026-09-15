package net.stho.photos.app

import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
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
    /**
     * The album list on screen, every level of it: albums, the containers heading them, and the
     * lines closing each container's group (§6). Empty on every screen that is not a list.
     */
    val rows: List<ListEntry> = emptyList(),
    /** How many albums a search matched on this list; zero when there is no search. */
    val matched: Int = 0,
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
    /**
     * The decoded previews of the photos either side of the open one, keyed by `PhotoRow.id`.
     *
     * What a swipe drags into view before it settles. Without them the neighbour was a placeholder
     * until the pager came to rest, then a second wait while the model decoded it.
     */
    val nearby: Map<Uuid, Preview> = emptyMap(),
    /** The open photo's transcode, once fetched — null for a still, or while it downloads. */
    val videoPath: String? = null,
    /** The open Live Photo's still and MOV, once both are on disk — null otherwise. */
    val livePair: LivePair? = null,
    /** The open album's thumbnails, keyed by `PhotoRow.id`. Loaded off the UI thread. */
    val thumbnails: Map<Uuid, ByteArray> = emptyMap(),
    val sync: SyncStatus = SyncStatus.Never,
    val notice: Notice? = null,
    /** The library's size, straight from the catalog — not from whatever the last sync did. */
    val totals: Totals = Totals(0, 0),
    val packsDone: Int = 0,
    val packsOutstanding: Int = 0,
    /**
     * Per-album cache state, which is the whole of what an album row's strip draws.
     *
     * Containers aggregate their children, so a container row is as informative as a leaf and
     * its control means "all eight sub-albums" (§2 gives it no blobs of its own).
     */
    val cache: Map<Uuid, AlbumCache> = emptyMap(),
    /** Albums explicitly asked for, so a row knows to offer pause rather than download. */
    val wanted: Set<Uuid> = emptySet(),
    /** Each album's sub-album and photo counts and latest date, summed up the tree. */
    val summaries: Map<Uuid, AlbumSummary> = emptyMap(),
    /** What the device holds, for the one screen that talks about the device (§6). */
    val storage: StorageTotals = StorageTotals.none,
    /**
     * Whether a worker is fetching the open photo's own blob right now.
     *
     * The viewer draws a placeholder until the image arrives, and pulses it while bytes are
     * moving — the same rule the album strip uses, so motion means one thing on every surface.
     */
    val openPhotoMoving: Boolean = false,
    /** The showing map's pins and clusters; null while no map is on screen (§6). */
    val map: MapUi? = null,
) {
    /** Every album on the list, containers included, in the order they are drawn. */
    val albums: List<Album> get() = rows.mapNotNull { (it as? ListEntry.Row)?.album }

    /** What this album's row draws. Unknown albums read as holding nothing, never as complete. */
    public fun cacheOf(album: Album): AlbumCache = cache[album.id] ?: AlbumCache.nothing

    /**
     * What a list row's strip draws: its album's, or for a header a search has narrowed, the sum
     * of the matches beneath it — their rollups never overlap, so nothing is counted twice.
     */
    public fun cacheOf(row: ListEntry.Row): AlbumCache {
        row.covers.singleOrNull()?.let { return cache[it] ?: AlbumCache.nothing }
        var held = 0L
        var total = 0L
        var moving = false
        for (id in row.covers) {
            val each = cache[id] ?: continue
            held += each.heldBytes
            total += each.totalBytes
            moving = moving || each.moving
        }
        return AlbumCache(held, total, moving)
    }

    /** The actions a list row offers — pause while any album it covers is asked for. */
    public fun actionsOf(row: ListEntry.Row): List<CacheAction> =
        actionsFor(cacheOf(row), wanted = row.covers.any { it in wanted })

    /** Icon-only actions the row offers, which is a function of state and nothing else. */
    public fun actionsOf(album: Album): List<CacheAction> =
        actionsFor(cacheOf(album), wanted = album.id in wanted)

    /** What this album's row says it holds — a container's counted over its descendants. */
    public fun contentsOf(album: Album): String =
        (summaries[album.id] ?: AlbumSummary(0, album.photoCount, album.dateMax)).contents()

    val screen: Screen get() = stack.current

    /** The current level is drawn as its map rather than as its list or grid (§6). */
    public val showingMap: Boolean get() = stack.map?.showing == true

    /** The nav bar's second line: a count, then the sort state, so no menu has to name it (§6). */
    public val subtitle: String
        get() = when {
            // A map says how much of the list it can place. The rest has no location, and this
            // line is the only thing that says so. No sort: a map has no order to name.
            showingMap && map != null ->
                "${map.pins.size} of ${map.total} ${if (query.isNotEmpty()) "matching" else "albums"} on the map"
            query.isNotEmpty() -> "$matched matching"
            // While packs are still arriving the line says so, exactly as the mockup does: the
            // covers filling in one by one otherwise look like something going wrong. *After*
            // the sort, never instead of it -- a first sync drains 288 packs, and a line that
            // dropped the sort for that long left the sort icon changing nothing anyone could see.
            packsOutstanding > 0 ->
                "${albums.size} albums · ${sort.label} · thumbnails $packsDone/${packsDone + packsOutstanding}"
            else -> "${albums.size} albums · ${sort.label}"
        }

    /** An album's second line: its photos, or on its map how many of them it can place. */
    public val photosSubtitle: String
        get() = if (showingMap && map != null) "${map.pins.size} of ${map.total} photos on the map"
        else "${photos.size} photos"

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
    private val queue: CacheQueue,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    /**
     * Every album's blobs, re-read when the catalog changes rather than per redraw.
     *
     * 34,607 rows is one query and a few MB; doing it per visible row would put the merged DB
     * in the draw path of the app's densest screen.
     */
    private var blobs: Map<Uuid, List<BlobRef>> = emptyMap()

    /**
     * Every album, flat, cached alongside [blobs].
     *
     * Re-walking the hierarchy meant one SQLite open per album, and the cache view is recomputed
     * whenever a blob lands — so on a first run that was ~120 database opens several hundred
     * times over, on the same dispatcher the download workers use. The workers starved and the
     * queue stopped draining entirely. The tree only changes when the catalog does.
     */
    private var albumTree: List<Album> = emptyList()

    private val _state = MutableStateFlow(AppUi())
    public val state: StateFlow<AppUi> = _state.asStateFlow()

    /** Reads what is already on disk, then syncs. The catalog is local, so nothing waits. */
    public fun start() {
        refreshCatalogView()
        reload()
        refreshCache()
        refresh()
        // A pack landing changes what is already on screen -- a placeholder cover becomes a
        // photograph -- so the model reloads rather than leaving the UI to poll.
        scope.launch {
            thumbnails.arrivals.collect { done ->
                _state.update { it.copy(packsDone = done, packsOutstanding = thumbnails.outstanding.value) }
                reload()
            }
        }
        // What is on disk and what is moving both change without anything on screen being
        // touched, so the rows follow the queue rather than being pushed by whoever changed it.
        //
        // Conflated and coalesced, because these fire twice per blob and a first run lands
        // hundreds: recomputing per event put the rollup on the workers' own dispatcher often
        // enough to stall the queue. `conflate` drops superseded values and the delay bounds
        // how often the rollup runs; the last value always arrives, so nothing is missed.
        scope.launch {
            combine(queue.held, queue.active) { held, active -> held to active }
                .conflate()
                .collect { (held, active) ->
                    refreshCache(held, active)
                    delay(COALESCE_MS)
                }
        }
        scope.launch { queue.wanted.collect { wanted -> _state.update { it.copy(wanted = wanted) } } }
    }

    /** Re-derive every row's strip from the queue's two sets and the catalog's sizes. */
    private fun refreshCache(
        held: Set<net.stho.photos.catalog.ObjectId> = queue.held.value,
        active: Set<net.stho.photos.catalog.ObjectId> = queue.active.value,
    ) {
        // Every album, not just the level on screen: a root container's strip is the sum of
        // descendants that are nowhere near the current list. Read from the cached tree rather
        // than re-walked -- see [albumTree].
        val albums = albumTree
        val cache = cacheByAlbum(albums, blobs, held, active)
        // The same join the strips use, summed: one directory read against the catalog's sizes,
        // and no `stat` anywhere.
        var media = 0L
        var packs = 0L
        for (refs in blobs.values) {
            for (ref in refs) {
                if (ref.id !in held) continue
                if (ref.kind == BlobKind.Pack) packs += ref.bytes else media += ref.bytes
            }
        }
        val holding = albums.count { (cache[it.id]?.heldBytes ?: 0L) > 0L }
        _state.update { current ->
            val open = (current.screen as? Screen.Photo)
                ?.let { current.photos.getOrNull(it.index) }
                ?.objectIds.orEmpty()
            current.copy(
                cache = cache,
                storage = StorageTotals(media, packs, holding),
                openPhotoMoving = open.any { id -> id in active },
            )
        }
    }

    /** The two things a rebuild can change, read once rather than per queue event. */
    private fun refreshCatalogView() {
        blobs = catalog.blobs()
        albumTree = allAlbums()
        // Before any reload reads them: the list sorts containers by these dates.
        val summaries = summariesByAlbum(albumTree)
        _state.update { it.copy(summaries = summaries) }
        refreshCache()
    }

    private fun allAlbums(): List<Album> {
        val out = mutableListOf<Album>()
        fun walk(parent: Uuid?) {
            for (album in catalog.albums(under = parent)) {
                out += album
                walk(album.id)
            }
        }
        walk(null)
        return out
    }

    // ------------------------------------------------------------------ the cache controls

    /**
     * Ask for an album, which is one of only two things that fetch images (§6).
     *
     * Lowest tier, and it survives navigating away — that is exactly what distinguishes it from
     * opening the album, which stops when you leave.
     */
    public fun download(album: Album) {
        queue.request(album.id, blobsUnder(album))
    }

    /** Stop a request, keeping every byte that landed. */
    public fun pause(album: Album) {
        queue.stop(album.id)
    }

    /**
     * Clear an album, which also stops it.
     *
     * No confirmation: re-downloading restores it and nothing in the zone is touched. Allowed on
     * the album currently open, where the blobs simply re-fetch as browsing continues.
     */
    public fun clearCache(album: Album) {
        queue.clear(album.id, blobsUnder(album))
    }

    /** One entry point for the row's controls, so the screen names an action and nothing else. */
    public fun act(album: Album, action: CacheAction) {
        when (action) {
            CacheAction.Download -> download(album)
            CacheAction.Pause -> pause(album)
            CacheAction.Clear -> clearCache(album)
        }
    }

    /**
     * A list row's controls, applied to every album it covers: the container itself, whose blobs
     * are all of its descendants' — or, under a search, only the matches its header kept.
     */
    public fun act(row: ListEntry.Row, action: CacheAction) {
        val byId = albumTree.associateBy { it.id }
        row.covers.mapNotNull { byId[it] }.forEach { act(it, action) }
    }

    /**
     * An album's blobs, plus every descendant's — so a container's control means all of them.
     *
     * Walked over the cached tree, not the database: this runs on the control server's thread
     * as well as on a tap, and hitting SQLite once per descendant made that request hang.
     */
    private fun blobsUnder(album: Album): List<BlobRef> {
        val children = albumTree.groupBy { it.parent }
        val out = mutableListOf<BlobRef>()
        fun walk(id: Uuid) {
            out += blobs[id].orEmpty()
            children[id].orEmpty().forEach { walk(it.id) }
        }
        walk(album.id)
        return out
    }

    public fun refresh() {
        scope.launch {
            _state.update { it.copy(sync = SyncStatus.Running(0, 0)) }
            val outcome = syncer.sync { fetched, total ->
                _state.update { it.copy(sync = SyncStatus.Running(fetched, total)) }
            }
            when (outcome) {
                is SyncOutcome.Succeeded -> {
                    // A rebuild can add, remove or re-encode blobs, so the sizes the rows draw
                    // from are re-read here rather than being assumed stable for the session.
                    refreshCatalogView()
                    // One update, not two. Setting the status and *then* reloading leaves a
                    // window where the sync reads as finished while the list is still empty --
                    // which is exactly what a screen, or a test, would sample and believe.
                    val status = SyncStatus.Succeeded(clock.now(), outcome.albums, outcome.photos)
                    _state.update { it.copy(sync = status).reloaded() }
                    refreshMap()
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
        album.thumbsId?.let { pack ->
            queue.visiblePacks(listOf(BlobRef(pack, CacheQueue.packBytes(album.photoCount), album.id)))
        }
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

    /**
     * Opening a photo from the grid. The album's order is what [index] indexes.
     *
     * Queues the photo itself at tier 0 and its neighbours at tier 1, exactly as a swipe does. It
     * used not to: the open photo was fetched only as one of the album's images, and those start
     * only once the album's thumbnail pack has landed — so a photo opened before that was fetched by
     * nothing until the next swipe. The iOS suite found it, as a Live Photo that never downloaded.
     */
    public fun openPhoto(index: Int) {
        val screen = state.value.screen
        if (screen !is Screen.Grid) return
        navigate { it.push(Screen.Photo(screen.albumId, screen.name, index)) }
        viewing(index, state.value.photos)
    }

    /** Swiping, or tapping the filmstrip. */
    public fun showPhoto(index: Int) {
        val screen = state.value.screen
        if (screen !is Screen.Photo || index !in state.value.photos.indices) return
        _state.update {
            it.copy(stack = it.stack.replace(screen.copy(index = index)), preview = null, videoPath = null, livePair = null)
        }
        loadPreview(index)
        viewing(index, state.value.photos)
    }

    public fun back(): Unit = navigate { it.pop() }

    public fun openSettings(): Unit = navigate { it.push(Screen.Settings) }

    // ------------------------------------------------------------------------------ the map

    /**
     * The viewport the map was last laid out in, in dp. A frame needs it and only the screen
     * knows it; until one reports, a phone's proportions stand in — which is what a scenario gets.
     */
    private var viewport: Pair<Double, Double> = DEFAULT_VIEWPORT

    /**
     * The icon showing the other representation: map ↔ list, or map ↔ grid (§6).
     *
     * Toggled on the level rather than pushed, so the title, the back button and the search stay
     * what they were — and the camera outlives toggling away and back.
     */
    public fun toggleMap() {
        _state.update { ui ->
            val screen = ui.screen
            if (screen !is Screen.Albums && screen !is Screen.Container && screen !is Screen.Grid) return@update ui
            val view = ui.stack.map ?: MapView()
            ui.copy(stack = ui.stack.withMap(view.copy(showing = !view.showing)))
        }
        refreshMap()
    }

    /**
     * The screen reports the map's size, so frames fit what is actually visible.
     *
     * The first frame is made before this arrives. While the camera is still that frame, the
     * same points are fitted again to the size that is real; once a gesture or a tap has taken
     * the camera over, a resize leaves it where the person put it.
     */
    public fun mapViewport(width: Double, height: Double) {
        if (width <= 0 || height <= 0) return
        viewport = width to height
        _state.update { ui ->
            val view = ui.stack.map?.takeIf { it.showing } ?: return@update ui
            val framing = view.framing?.takeIf { it.width != width || it.height != height } ?: return@update ui
            val refit = framing.copy(width = width, height = height)
            ui.copy(
                stack = ui.stack.withMap(
                    view.copy(camera = refit.cameraFor(width, height), moves = view.moves + 1, framing = refit),
                ),
            )
        }
    }

    /** Where a gesture left the camera: remembered, never animated to — the renderer is already there. */
    public fun cameraMoved(camera: MapCamera): Unit = updateCamera(camera, moved = false)

    /** Moves the camera and has the renderer follow: a cluster tap, or the control server. */
    public fun moveCamera(camera: MapCamera): Unit = updateCamera(camera, moved = true)

    private fun updateCamera(camera: MapCamera, moved: Boolean) {
        val limits = MapLimits.MIN_ZOOM.toDouble()..MapLimits.MAX_ZOOM.toDouble()
        val clamped = camera.copy(zoom = camera.zoom.coerceIn(limits))
        _state.update { ui ->
            val view = ui.stack.map?.takeIf { it.showing } ?: return@update ui
            if (!moved && view.camera == clamped) return@update ui
            // Moved, so no longer the automatic frame: a later resize must not undo it.
            ui.copy(
                stack = ui.stack.withMap(
                    view.copy(camera = clamped, moves = view.moves + if (moved) 1 else 0, framing = null),
                ),
            )
        }
    }

    /**
     * A tap on the map (§6).
     *
     * A pin opens what it stands for: an album on its own map — its photos where they were
     * taken, the grid a toggle away — or the viewer at that photo. A cluster zooms until it
     * splits. One whose members share a spot never splits, so its albums are listed instead —
     * and its photos open the viewer at the earliest, since paging walks through the rest anyway.
     */
    public fun tapMap(cluster: Cluster) {
        val ui = _state.value
        val map = ui.map ?: return
        val pins = cluster.members.map { map.pins.getOrNull(it) ?: return }
        if (pins.isEmpty()) return
        if (cluster.isPin) {
            when (val pin = pins.single()) {
                is MapPin.OfAlbum -> openOnMap(pin.album)
                is MapPin.OfPhoto -> openPhoto(pin.index)
            }
            return
        }
        val zoom = ui.stack.map?.camera?.zoom ?: return
        val splits = map.clusters.expansion(cluster, map.clusters.level(zoom))
        if (splits == null) {
            val albums = pins.filterIsInstance<MapPin.OfAlbum>().map { it.album }
            if (albums.isNotEmpty()) {
                _state.update { it.copy(map = it.map?.copy(sheet = albums)) }
            } else {
                pins.filterIsInstance<MapPin.OfPhoto>().minOfOrNull { it.index }?.let(::openPhoto)
            }
            return
        }
        val fitted = frame(pins.map { it.point }, viewport.first, viewport.second)
        // At least the level at which it splits: the members' box alone can fit at a zoom where
        // they are still one circle, and the tap would look like it did nothing.
        moveCamera(fitted.copy(zoom = max(fitted.zoom, splits.toDouble())))
    }

    /** A row in the list of albums that share one spot: opened on its map, as its pin would be. */
    public fun openFromSheet(album: Album): Unit = openOnMap(album)

    /**
     * An album reached from a map stays on the map, one level down (§6).
     *
     * Its map is a new level's, so it frames the album's own photos; Back pops it and the album
     * list's map is exactly where it was left.
     */
    private fun openOnMap(album: Album) {
        open(album)
        val ui = _state.value
        if (ui.screen is Screen.Grid && !ui.showingMap) toggleMap()
    }

    public fun dismissSheet(): Unit = _state.update { it.copy(map = it.map?.copy(sheet = null)) }

    private var mapping: Job? = null

    /**
     * The showing map's pins and clusters, rebuilt when what it shows has changed.
     *
     * Launched rather than done in place: clustering is a pass over every point at every zoom,
     * and a tap or a search keystroke should not wait on it. Unchanged points keep the index
     * already built, so a pack landing — which reloads the list — reclusters nothing.
     */
    private fun refreshMap() {
        val ui = _state.value
        if (!ui.showingMap) {
            if (ui.map != null) _state.update { if (it.showingMap) it else it.copy(map = null) }
            return
        }
        mapping?.cancel()
        mapping = scope.launch {
            val (pins, total) = pinsOf(ui)
            val built = ui.map?.takeIf { it.pins == pins }?.copy(total = total)
                ?: MapUi(pins, ClusterIndex(pins.map { it.point }), total)
            _state.update { current ->
                if (current.screen != ui.screen || !current.showingMap) return@update current
                val view = requireNotNull(current.stack.map)
                val stack = if (view.camera != null) {
                    current.stack
                } else {
                    val (width, height) = viewport
                    val framing = Framing(framedPoints(current, pins), width, height)
                    current.stack.withMap(
                        view.copy(camera = framing.cameraFor(width, height), moves = view.moves + 1, framing = framing),
                    )
                }
                current.copy(stack = stack, map = built.copy(sheet = current.map?.takeIf { it.pins == pins }?.sheet))
            }
        }
    }

    /**
     * What a map places, and what the subtitle counts it against.
     *
     * The album list's map is flat — every located album that owns photos, whatever the level —
     * because a container's centroid lands between its albums, somewhere nobody went (§6). A
     * search narrows it to the albums that match, exactly as it narrows the list.
     */
    private fun pinsOf(ui: AppUi): Pair<List<MapPin>, Int> = when (ui.screen) {
        is Screen.Grid -> ui.photos.mapIndexedNotNull { index, photo ->
            placed(photo.latitude, photo.longitude)?.let { MapPin.OfPhoto(photo, index, it) }
        } to ui.photos.size

        else -> {
            val owning = (if (ui.query.isNotBlank()) ui.albums else albumTree).filter { it.photoCount > 0 }
            owning.mapNotNull { album ->
                placed(album.latitude, album.longitude)?.let { MapPin.OfAlbum(album, it) }
            } to owning.size
        }
    }

    private fun placed(latitude: Double?, longitude: Double?): WorldPoint? =
        if (latitude != null && longitude != null) Mercator.project(latitude, longitude) else null

    /**
     * What a map first frames: this level's albums — a container's own, with every other pin
     * still around them — or the whole library when the level has none placed.
     */
    private fun framedPoints(ui: AppUi, pins: List<MapPin>): List<WorldPoint> {
        val container = (ui.screen as? Screen.Container)?.takeIf { ui.query.isBlank() }
        val beneath = container?.let { descendantsOf(it.albumId) }
        val here = beneath?.let { ids -> pins.filter { it is MapPin.OfAlbum && it.album.id in ids } }
        val framed = here?.takeIf { it.isNotEmpty() } ?: pins
        return framed.map { it.point }
    }

    private fun descendantsOf(id: Uuid): Set<Uuid> {
        val children = albumTree.groupBy { it.parent }
        val out = mutableSetOf<Uuid>()
        fun walk(parent: Uuid) {
            children[parent].orEmpty().forEach { if (out.add(it.id)) walk(it.id) }
        }
        walk(id)
        return out
    }

    // --------------------------------------------------------------------------- the upload

    /**
     * The upload icon. The list on screen becomes the new album's parent (§8), so only the album
     * list and a container offer it: an album of photos cannot hold a sub-album (§2). Null anywhere
     * else, and nothing moves.
     */
    public fun openUpload(): Screen.Upload? {
        val upload = when (val here = state.value.screen) {
            Screen.Albums -> Screen.Upload(parent = null, parentName = "Albums")
            is Screen.Container -> Screen.Upload(parent = here.albumId, parentName = here.name)
            else -> return null
        }
        navigate { it.push(upload) }
        return upload
    }

    /** Used by the control server's `POST /nav` as well as by the UI. */
    public fun navigate(change: (BackStack) -> BackStack) {
        val leaving = _state.value.screen
        _state.update {
            it.copy(
                stack = change(it.stack), query = "", preview = null, videoPath = null, livePair = null,
                map = it.map?.copy(sheet = null),
            )
        }
        // Leaving the album abandons the prefetch queue; staying inside it (grid ↔ photo)
        // keeps the pack and whatever has already been fetched.
        val arrived = _state.value.screen.albumOf()
        if (arrived != leaving.albumOf()) {
            previews.cancelPrefetch()
            _state.update { it.copy(thumbnails = emptyMap(), nearby = emptyMap()) }
            // Leaving an album stops its pending downloads, keeping whatever landed (§6). What
            // is still missing is derived from disk when you come back, so nothing is remembered.
            queue.leaveAlbum()
            if (arrived != null) startAlbumImages(arrived)
        }
        reload()
    }

    /**
     * An opened album's images, queued the moment it opens.
     *
     * With no wait for anything: not for the background sweep of every pack, and not for this
     * album's own pack either. Ordering is the ladder's job, and it already has it — [open] queues
     * the album's pack as a visible pack, a tier above these images, so the pack is fetched first
     * without being waited on. Waiting was worse than redundant: it was built as "only if the pack
     * is already there", nothing retried when the pack arrived, and an album opened before then
     * never fetched its images at all.
     */
    private fun startAlbumImages(albumId: Uuid) {
        val album = catalog.album(albumId) ?: return
        if (album.photoCount == 0) return
        queue.openAlbum(blobs[albumId].orEmpty())
    }

    /**
     * Tier 0 and tier 1: the open photo, then the ±3 either side.
     *
     * The immediate worker serves both and is the only one that cancels, so swiping past a
     * 73.9 MiB video abandons it rather than holding a worker for ten seconds on something
     * already off screen.
     */
    private fun viewing(index: Int, photos: List<PhotoRow>) {
        val albumId = (state.value.screen as? Screen.Photo)?.albumId ?: return
        val known = blobs[albumId].orEmpty().associateBy { it.id }
        fun refs(photo: PhotoRow?) = photo?.objectIds.orEmpty().mapNotNull { known[it] }
        queue.viewing(
            open = refs(photos.getOrNull(index)),
            neighbours = ((index - NEIGHBOURS)..(index + NEIGHBOURS))
                .filter { it != index }
                .flatMap { refs(photos.getOrNull(it)) },
        )
    }

    private fun Screen.albumOf(): Uuid? = when (this) {
        is Screen.Grid -> albumId
        is Screen.Photo -> albumId
        is Screen.Container -> albumId
        else -> null
    }

    public fun dismissNotice(): Unit = _state.update { it.copy(notice = null) }

    private fun reload() {
        _state.update { it.reloaded() }
        refreshMap()
    }

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
                    current.copy(rows = emptyList(), photos = photos, packReady = true)
                }

                is Screen.Grid -> {
                    val album = catalog.album(screen.albumId)
                    val ready = album != null && this@AppModel.thumbnails.has(album)
                    if (ready && current.thumbnails.isEmpty()) loadThumbnails(album)
                    current.copy(
                        rows = emptyList(),
                        photos = catalog.photos(screen.albumId),
                        packReady = ready,
                    )
                }

                else -> {
                    // Built from the cached tree, not re-queried per level: every level is on the
                    // list now, and the tree is re-read whenever the catalog changes anyway.
                    val matches = current.query.takeIf { it.isNotBlank() }
                        ?.let { query -> catalog.search(query).mapTo(mutableSetOf()) { it.id } }
                    val rows = albumRows(albumTree, screen.parentAlbum(), current.sort, current.summaries, matches)
                    current.copy(
                        rows = rows,
                        matched = matches?.let { ids -> rows.count { it is ListEntry.Row && it.album.id in ids } } ?: 0,
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
            loadMotion(photo)
            // Launched, not set in place: [reloaded] calls this from inside a state update, and an
            // update made in there is overwritten by that update's own result -- so reopening a
            // photo already decoded left the viewer on its placeholder.
            scope.launch { _state.update { it.copy(preview = cached) } }
            previews.prefetch(photos, index)
            decodeNeighbours(index, photos)
            return
        }
        loadMotion(photo)
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
            decodeNeighbours(index, photos)
        }
    }

    private var decodingNeighbours: kotlinx.coroutines.Job? = null

    /**
     * Decode the photo either side of [index] into [AppUi.nearby], once the open one is showing.
     *
     * One each way rather than §6's ±3: a swipe drags in exactly one neighbour, and a decoded
     * 3200px frame is tens of megabytes. The ±3 *blobs* are still queued at tier 1 (see
     * [viewing]), so this mostly decodes what is already on disk. A newer swipe cancels it.
     */
    private fun decodeNeighbours(index: Int, photos: List<PhotoRow>) {
        val keep = ((index - 1)..(index + 1)).mapNotNull { photos.getOrNull(it)?.id }.toSet()
        decodingNeighbours?.cancel()
        decodingNeighbours = scope.launch {
            for (i in listOf(index + 1, index - 1)) {
                val photo = photos.getOrNull(i) ?: continue
                val decoded = previews.cached(photo) ?: previews.load(photo) ?: continue
                _state.update { current ->
                    if (current.screen !is Screen.Photo) current
                    else current.copy(nearby = current.nearby.filterKeys { it in keep } + (photo.id to decoded))
                }
            }
        }
    }

    /**
     * Whatever moves: a video's transcode or a Live Photo's pair, fetched on demand while the
     * still is already showing. A plain photo has nothing to wait for.
     */
    private fun loadMotion(photo: PhotoRow) {
        when (photo.mediaType) {
            MediaType.VIDEO -> scope.launch {
                val path = videos.localFile(photo)
                updateIfStillOpen(photo) { it.copy(videoPath = path) }
            }
            MediaType.LIVE_PHOTO -> scope.launch {
                val pair = videos.livePair(photo)
                updateIfStillOpen(photo) { it.copy(livePair = pair) }
            }
            else -> Unit
        }
    }

    /** A swipe may have moved on while the download ran; an answer for a photo left behind is dropped. */
    private fun updateIfStillOpen(photo: PhotoRow, change: (AppUi) -> AppUi) {
        _state.update { current ->
            val screen = current.screen
            val open = (screen as? Screen.Photo)?.let { current.photos.getOrNull(it.index) }
            if (open?.id == photo.id) change(current) else current
        }
    }

    private fun Screen.parentAlbum(): Uuid? = when (this) {
        is Screen.Container -> albumId
        else -> null
    }

    private companion object {
        /** §6's ±3 either side, which is what a swipe lands on. */
        const val NEIGHBOURS = 3

        /** How long the rollup waits before recomputing again, to coalesce a burst of arrivals. */
        const val COALESCE_MS = 150L

        /** A phone's map area in dp, until the screen reports its own. */
        val DEFAULT_VIEWPORT = 390.0 to 640.0
    }
}
