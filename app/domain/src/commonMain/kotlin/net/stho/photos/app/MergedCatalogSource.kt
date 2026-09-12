package net.stho.photos.app

import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album
import net.stho.photos.catalog.CatalogReader
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.model.PhotoRow
import net.stho.photos.ports.SqlDrivers

/**
 * The `Catalog` port over the merged database.
 *
 * A reader is opened per call rather than held: the rebuild takes a single write transaction
 * for 1–3 s (§4), WAL lets readers see the pre-transaction snapshot for its whole duration, and
 * a fresh reader is how the next read picks up the committed one.
 */
public class MergedCatalogSource(
    private val sync: CatalogSync,
    private val drivers: SqlDrivers,
) : Catalog {
    override fun albums(under: Uuid?): List<Album> = read(emptyList()) { it.albums(under) }

    override fun search(text: String): List<Album> = read(emptyList()) { it.searchAlbums(text) }

    override fun photos(inAlbum: Uuid): List<PhotoRow> = read(emptyList()) { it.photos(inAlbum) }

    override fun album(id: Uuid): Album? = read(null) { it.album(id) }

    override fun totals(): Totals =
        read(Totals(0, 0)) { Totals(albums = it.allAlbums().size, photos = it.photoCount()) }

    /** Every album, flat — what the pack queue works from. */
    public fun everyAlbum(): List<Album> = read(emptyList()) { it.allAlbums() }

    /**
     * Every blob each album owns, with what fetching it will cost.
     *
     * Read in one pass and held by the model rather than re-queried per redraw: the album list
     * is the app's densest screen and its strip is drawn from this on every frame.
     *
     * A photo row states which objects it owns through its four id columns, so this does not
     * infer anything from `media_type`. `photo.bytes` is §3's size of the blob a tap fetches —
     * exact for that one, and the only figure the zone gives us — so the companions a Live Photo
     * adds are charged the same. They are ~2.7 MB against a 424 KiB still, which the strip
     * would otherwise under-report; erring towards the larger number keeps a row from reading
     * complete while something is still missing.
     */
    override fun blobs(): Map<Uuid, List<BlobRef>> = read(emptyMap()) { reader ->
        reader.allAlbums().associate { album ->
            val pack = album.thumbsId?.let {
                BlobRef(it, CacheQueue.packBytes(album.photoCount), album.id, BlobKind.Pack)
            }
            val media = reader.photos(album.id).flatMap { photo ->
                photo.objectIds.map { BlobRef(it, photo.bytes ?: 0L, album.id, BlobKind.Media) }
            }
            album.id to (listOfNotNull(pack) + media)
        }
    }

    /**
     * Before the first sync there is no merged DB at all, and a screen asking for albums then
     * is not an error -- the list renders empty and the toast says why. [absent] is what each
     * question's "nothing yet" looks like, since the types differ.
     */
    private fun <T> read(absent: T, block: (CatalogReader) -> T): T =
        runCatching { CatalogReader(sync.mergedPath, drivers).use(block) }.getOrDefault(absent)
}
