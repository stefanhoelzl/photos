package net.stho.photos.desktop

import io.ktor.client.plugins.ResponseException
import java.io.IOException
import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album
import net.stho.photos.catalog.CatalogReader
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.model.PhotoRow
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.ui.state.BlobKind
import net.stho.photos.ui.state.BlobRef
import net.stho.photos.ui.state.CacheQueue
import net.stho.photos.ui.state.Catalog
import net.stho.photos.ui.state.Notice
import net.stho.photos.ui.state.SyncOutcome
import net.stho.photos.ui.state.Totals
import net.stho.photos.ui.state.Syncer

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

/**
 * The `Syncer` port, and the single place §1's "name the status and the cause" rule is applied.
 *
 * The split is the one that decides both the toast's colour and its duration: a status a person
 * has to act on is an error, and anything the network will fix by itself is informational.
 */
public class CatalogSyncer(
    private val sync: CatalogSync,
    /**
     * §4 gains a step for the app: after the shards land, fetch every pack whose `thumbs_id`
     * moved. It runs here rather than inside the domain because queueing downloads is the
     * app's business, and the CLI writes packs rather than collecting them.
     */
    private val thenFetchPacks: () -> Unit,
) : Syncer {
    override suspend fun sync(onProgress: (fetched: Int, total: Int) -> Unit): SyncOutcome =
        try {
            val report = sync.sync { fetched, total -> onProgress(fetched, total) }
            thenFetchPacks()
            SyncOutcome.Succeeded(albums = report.albums, photos = report.photos)
        } catch (failure: ResponseException) {
            SyncOutcome.Failed(failure.notice())
        } catch (failure: IOException) {
            SyncOutcome.Failed(offline)
        } catch (failure: kotlinx.io.IOException) {
            // The domain reads and writes through kotlinx-io, whose IOException is its own type
            // on every target -- catching only java.io's would let a dropped connection escape.
            SyncOutcome.Failed(offline)
        } catch (failure: Exception) {
            // §1 forbids opaque errors, and an unmapped exception is the most opaque outcome
            // there is: without this the coroutine dies silently and the screen says "running"
            // for ever. Naming the type is the least this can do while still being honest that
            // it was not anticipated.
            System.err.println("sync failed: $failure")
            SyncOutcome.Failed(
                Notice.error("Sync failed", failure.message ?: failure::class.simpleName ?: "unknown error"),
            )
        }

    private val offline = Notice.info("No network", "Showing the catalog as it was at the last sync.")

    private fun ResponseException.notice(): Notice {
        val status = response.status
        val remedy = when (status.value) {
            401, 403 -> "Log out and check the password."
            404 -> "The zone name in the storage URL does not exist."
            else -> "The storage zone rejected the request."
        }
        return Notice.error("Sync failed: ${status.value} ${status.description}", remedy)
    }
}
