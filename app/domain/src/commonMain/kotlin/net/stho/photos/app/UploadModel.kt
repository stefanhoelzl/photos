package net.stho.photos.app

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.stho.photos.catalog.Album

/** The gallery picker and the album dialog (§8), as one snapshot. */
public data class PickerUi(
    /** The container the upload was started from; null at the root, or inside an album. */
    val parent: Uuid? = null,
    /** The album the upload was started inside, which the dialog pre-selects. */
    val addTo: Uuid? = null,
    /** Null until the platform has answered. */
    val access: GalleryAccess? = null,
    val albums: List<GalleryAlbum> = emptyList(),
    /** Every asset, for picking loose photos — oldest first, opened at the end (§8). */
    val assets: List<GalleryAsset> = emptyList(),
    val selected: Set<String> = emptySet(),
    val thumbnails: Map<String, ByteArray> = emptyMap(),
    /** The album dialog, while it is up. */
    val naming: Naming? = null,
    /** Why the library could not be read — §1's rule: a picker that stays empty says so. */
    val failure: String? = null,
)

public data class Naming(
    /** The album field's text: a path from the library root, `Trips / Italy`. */
    val text: String,
    /**
     * Oldest first, as the picker shows them (§8) — so a clashing filename is the newer photo's
     * ` (2)`, and a whole gallery album and a loose pick name their photos the same way.
     */
    val assetIds: List<String>,
    /** The albums the photos can go into, read when the dialog opened. */
    val targets: UploadTargets,
    /** New albums added with `+` while this dialog is up, newest first. Nothing creates them until Upload. */
    val added: List<UploadTarget> = emptyList(),
    /** The entry Upload sends to; null until one is picked, added, or typed exactly. */
    val selected: UploadTarget? = null,
    /** The gallery album chosen whole, which deleting takes too; null for loose photos. */
    val galleryAlbum: String? = null,
    /** Decided up front rather than asked afterwards, and ticked until unticked (§8). */
    val deleteFromGallery: Boolean = true,
) {
    val count: Int get() = assetIds.size

    /** Every entry the list offers: the added ones first, then the albums. */
    val entries: List<UploadTarget> get() = added + targets.entries

    /** What the text names right now. */
    val resolution: Resolution get() = targets.resolve(text, added)
}

/**
 * The key the picker's photo rows carry — the row's first asset — which a remembered [Scroll]
 * names, and which [UploadModel] reads the asset back out of to fetch that part of the library
 * first.
 *
 * Here rather than in the screen because both ends have to agree on it, the way `ListEntry.key`
 * does for the album list. Which photos share a row stays the screen's own: that is its layout.
 */
public fun photoRowKey(assetId: String): String = ROW_KEY + assetId

/** The asset [photoRowKey] names, or null for an album row or a section label. */
private fun assetOfRowKey(key: String?): String? = key?.takeIf { it.startsWith(ROW_KEY) }?.removePrefix(ROW_KEY)

private const val ROW_KEY = "row:"

/**
 * What the upload screens call.
 *
 * Separate from [AppModel] because nothing else in the app reads the gallery, and the uploads
 * outlive every screen: the pill stays while browsing continues.
 */
public class UploadModel(
    private val gallery: Gallery,
    private val uploads: Uploads,
    private val catalog: Catalog,
    private val scope: CoroutineScope,
) {
    private val _picker = MutableStateFlow(PickerUi())
    public val picker: StateFlow<PickerUi> = _picker.asStateFlow()

    public val statuses: StateFlow<List<UploadStatus>> get() = uploads.statuses

    /**
     * Where the picker was left, for the next upload: §8's one position, in memory only. Null is
     * the list's end — where the first open lands, and where a picker left there opens again, so
     * the photos taken since are in view rather than just below it.
     *
     * Outside [PickerUi] because [open] and [close] both replace that snapshot, and the whole
     * point of this is to outlive them — a second upload carries on through the library where the
     * first one stopped. Read once, when the picker restores; never scrolled to while it is up.
     */
    public var pickerScroll: Scroll? = null
        private set

    private var loading: Job? = null

    /** The upload icon: on the album list or in a container ([parent]), or inside the album [addTo]. */
    public fun open(parent: Uuid?, addTo: Uuid? = null) {
        loading?.cancel()
        _picker.value = PickerUi(parent = parent, addTo = addTo)
        loading = scope.launch {
            try {
                val access = gallery.requestAccess()
                _picker.update { it.copy(access = access) }
                if (access != GalleryAccess.Full) return@launch
                val albums = gallery.albums()
                val assets = gallery.assets(null)
                _picker.update { it.copy(albums = albums, assets = assets) }
                // From where the picker is about to stand rather than from the library's head:
                // thousands of photos from it, loading front to back leaves that screen grey until
                // the loop reaches it. The end, unless a row is remembered — a remembered album or
                // mark is at or near the end too, or one row's worth from it.
                val start = assetOfRowKey(pickerScroll?.key)
                    ?.let { id -> assets.indexOfFirst { it.id == id } }
                    ?.takeIf { it >= 0 }
                    ?: assets.lastIndex
                for (index in outwardFrom(start, assets.size)) {
                    val asset = assets[index]
                    val jpeg = gallery.thumbnail(asset) ?: continue
                    _picker.update { it.copy(thumbnails = it.thumbnails + (asset.id to jpeg)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Otherwise the coroutine dies quietly and the picker says "opening" for ever.
                _picker.update {
                    it.copy(failure = "The photo library could not be read: ${failure.message ?: failure::class.simpleName}")
                }
            }
        }
    }

    public fun toggle(assetId: String): Unit = _picker.update {
        it.copy(selected = if (assetId in it.selected) it.selected - assetId else it.selected + assetId)
    }

    /** Adds to the selection — what the control server's `/upload/select` does. */
    public fun select(assetIds: Collection<String>): Unit = _picker.update { it.copy(selected = it.selected + assetIds) }

    /**
     * The selection, whole: what a drag across the grid leaves it as. Whole rather than added to,
     * because dragging back over photos takes them out of the range again.
     */
    public fun setSelection(assetIds: Set<String>): Unit = _picker.update { it.copy(selected = assetIds) }

    /** A gallery album: the whole album, its name pre-filled after the container the upload started in. */
    public suspend fun chooseAlbum(album: GalleryAlbum) {
        val ids = gallery.assets(album).map(GalleryAsset::id)
        name(ids, album)
    }

    /** Loose photos, in the library's order rather than the order they were tapped. */
    public fun chooseSelected() {
        val picker = _picker.value
        name(picker.assets.map(GalleryAsset::id).filter { it in picker.selected }, galleryAlbum = null)
    }

    /**
     * The dialog for [assetIds], oldest first: the order the gallery lists them in (§8), and the
     * one an upload sends — so a loose pick and a whole gallery album name their photos alike.
     */
    private fun name(assetIds: List<String>, galleryAlbum: GalleryAlbum?) {
        val targets = targets()
        _picker.update { picker ->
            val text = targets.prefill(start = picker.parent, addTo = picker.addTo, galleryName = galleryAlbum?.name)
            picker.copy(naming = Naming(text, assetIds, targets, galleryAlbum = galleryAlbum?.id).typed(text))
        }
    }

    /** The album tree as it stands, and this phone's new albums still on their way up. */
    private fun targets(): UploadTargets {
        val albums = mutableListOf<Album>()
        val containers = mutableSetOf<Uuid>()
        fun walk(parent: Uuid?) {
            for (album in catalog.albums(under = parent)) {
                albums += album
                val before = albums.size
                walk(album.id)
                if (albums.size > before && album.photoCount == 0) containers += album.id
            }
        }
        walk(null)
        val uploading = uploads.statuses.value
            .filter { it.stage != UploadStage.Done }
            .map { UploadTarget(it.target, it.name, it.parent, it.path, UploadTarget.Kind.Uploading) }
        return UploadTargets(albums, containers, uploading)
    }

    /**
     * The album field's text, as typed. The selection follows it: an entry whose path it matches
     * exactly, and none otherwise — editing away from a picked album unpicks it.
     */
    public fun type(text: String): Unit = _picker.update { it.copy(naming = it.naming?.typed(text)) }

    /**
     * The `+` beside a path that names no album: adds it to the list as a new album and selects it.
     * Nothing is created — the album exists once an upload into it starts. False when the text names
     * no new album, and nothing changes.
     */
    public fun addNew(): Boolean {
        val naming = _picker.value.naming ?: return false
        val new = naming.resolution as? Resolution.New ?: return false
        val entry = UploadTarget(Uuid.random(), new.name, new.parent, new.path, UploadTarget.Kind.New)
        _picker.update { it.copy(naming = naming.copy(text = entry.path, added = listOf(entry) + naming.added, selected = entry)) }
        return true
    }

    /** An entry picked from the list. False when the dialog offers no entry with that id. */
    public fun target(id: Uuid): Boolean {
        val naming = _picker.value.naming ?: return false
        val entry = naming.entries.firstOrNull { it.id == id } ?: return false
        _picker.update { it.copy(naming = naming.copy(text = entry.path, selected = entry)) }
        return true
    }

    public fun deleteAfterUpload(delete: Boolean): Unit =
        _picker.update { it.copy(naming = it.naming?.copy(deleteFromGallery = delete)) }

    public fun dismissNaming(): Unit = _picker.update { it.copy(naming = null) }

    /**
     * Upload into the selected entry. The upload's id, or null when there is nothing to send or no
     * entry is selected.
     *
     * Every upload is an addition (§8), carrying the album's name and parent: they are what a new
     * album is made from, and what an addition to one that is gone stands as.
     */
    public fun confirm(): Uuid? {
        val naming = _picker.value.naming ?: return null
        val target = naming.selected ?: return null
        if (naming.assetIds.isEmpty()) return null
        close()
        return uploads.start(
            UploadRequest(
                name = target.name,
                parent = target.parent,
                path = target.path,
                assetIds = naming.assetIds,
                galleryAlbum = naming.galleryAlbum,
                deleteFromGallery = naming.deleteFromGallery,
                addTo = target.id,
            ),
        )
    }

    /**
     * Where a scroll came to rest in the picker: the first item on screen, by key and position —
     * or [atEnd], the list's end, which is remembered as that rather than as the item it put first.
     */
    public fun scrolled(key: String?, index: Int, offset: Int, atEnd: Boolean = false) {
        pickerScroll = if (atEnd) null else Scroll(key = key, index = index, offset = offset)
    }

    public fun close() {
        loading?.cancel()
        _picker.value = PickerUi()
    }

    public fun cancel(albumId: Uuid): Unit = uploads.cancel(albumId)

    public fun retry(albumId: Uuid): Unit = uploads.retry(albumId)

    public fun openSettings(): Unit = gallery.openSettings()
}

/**
 * Every index below [size], [start] first and then outward from it: the nearest on either side
 * next, so the rows around where the picker stands fill before the far ends of the library.
 */
internal fun outwardFrom(start: Int, size: Int): List<Int> = buildList {
    for (distance in 0 until size) {
        if (start + distance < size) add(start + distance)
        if (distance > 0 && start - distance >= 0) add(start - distance)
    }
}

/** [text] as the field now holds, with the selection following it (see [UploadModel.type]). */
private fun Naming.typed(text: String): Naming {
    val match = (targets.resolve(text, added) as? Resolution.Entry)?.target
    return copy(text = text, selected = match)
}
