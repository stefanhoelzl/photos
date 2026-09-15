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

/** The gallery picker and the name dialog (§8), as one snapshot. */
public data class PickerUi(
    val parent: Uuid? = null,
    val parentName: String = "",
    /** Null until the platform has answered. */
    val access: GalleryAccess? = null,
    val albums: List<GalleryAlbum> = emptyList(),
    /** Every asset, for picking loose photos. */
    val assets: List<GalleryAsset> = emptyList(),
    val selected: Set<String> = emptySet(),
    val thumbnails: Map<String, ByteArray> = emptyMap(),
    /** The name dialog, while it is up. */
    val naming: Naming? = null,
    /** Why the library could not be read — §1's rule: a picker that stays empty says so. */
    val failure: String? = null,
)

public data class Naming(
    val name: String,
    val assetIds: List<String>,
    /** The gallery album chosen whole, which deleting takes too; null for loose photos. */
    val galleryAlbum: String? = null,
    /** Decided up front rather than asked afterwards (§8). */
    val deleteFromGallery: Boolean = false,
) {
    val count: Int get() = assetIds.size
}

/**
 * What the upload screens call.
 *
 * Separate from [AppModel] because nothing else in the app reads the gallery, and the uploads
 * outlive every screen: the pill stays while browsing continues.
 */
public class UploadModel(
    private val gallery: Gallery,
    private val uploads: Uploads,
    private val scope: CoroutineScope,
) {
    private val _picker = MutableStateFlow(PickerUi())
    public val picker: StateFlow<PickerUi> = _picker.asStateFlow()

    public val statuses: StateFlow<List<UploadStatus>> get() = uploads.statuses

    private var loading: Job? = null

    /** The upload icon. [parent] is where the new album goes. */
    public fun open(parent: Uuid?, parentName: String) {
        loading?.cancel()
        _picker.value = PickerUi(parent = parent, parentName = parentName)
        loading = scope.launch {
            try {
                val access = gallery.requestAccess()
                _picker.update { it.copy(access = access) }
                if (access != GalleryAccess.Full) return@launch
                val albums = gallery.albums()
                val assets = gallery.assets(null)
                _picker.update { it.copy(albums = albums, assets = assets) }
                for (asset in assets) {
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

    /** A gallery album: the whole album, its name prefilled. */
    public suspend fun chooseAlbum(album: GalleryAlbum) {
        val ids = gallery.assets(album).map(GalleryAsset::id)
        _picker.update { it.copy(naming = Naming(album.name, ids, galleryAlbum = album.id)) }
    }

    /** Loose photos, in the library's order rather than the order they were tapped. */
    public fun chooseSelected(): Unit = _picker.update { picker ->
        picker.copy(naming = Naming("", picker.assets.map(GalleryAsset::id).filter { it in picker.selected }))
    }

    public fun rename(name: String): Unit = _picker.update { it.copy(naming = it.naming?.copy(name = name)) }

    public fun deleteAfterUpload(delete: Boolean): Unit =
        _picker.update { it.copy(naming = it.naming?.copy(deleteFromGallery = delete)) }

    public fun dismissNaming(): Unit = _picker.update { it.copy(naming = null) }

    /** Upload. The new album's id, or null when there is no name or nothing to send. */
    public fun confirm(): Uuid? {
        val picker = _picker.value
        val naming = picker.naming ?: return null
        if (naming.name.isBlank() || naming.assetIds.isEmpty()) return null
        close()
        return uploads.start(
            UploadRequest(naming.name.trim(), picker.parent, naming.assetIds, naming.galleryAlbum, naming.deleteFromGallery),
        )
    }

    public fun close() {
        loading?.cancel()
        _picker.value = PickerUi()
    }

    public fun cancel(albumId: Uuid): Unit = uploads.cancel(albumId)

    public fun retry(albumId: Uuid): Unit = uploads.retry(albumId)

    public fun openSettings(): Unit = gallery.openSettings()
}
