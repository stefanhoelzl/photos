package net.stho.photos.desktop

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.Album
import net.stho.photos.catalog.CatalogReader
import net.stho.photos.catalog.ThumbPack
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.ui.state.BlobKind
import net.stho.photos.ui.state.BlobRef
import net.stho.photos.ui.state.CacheQueue
import net.stho.photos.ui.state.Thumbnails

/**
 * Every album's thumbnail pack: how one is *read*, and how the sweep asks for the rest.
 *
 * It used to own a queue of its own — an urgent channel, a background channel and a drain loop.
 * That queue is gone: ordering downloads is one problem, not one per asset type, and the ladder
 * that decides it now lives in `:ui/state` where a test can reach it. What is left here is the
 * part that really is about packs — resolving a cover, unpacking a pack — plus the two counters
 * the nav bar reads.
 *
 * Packs are still always kept and never evicted (§6): that is what makes the grid open instantly
 * and offline, and it is expressed by [BlobKind.Pack], which a clear skips.
 */
public class PackFetcher(
    cacheRoot: Path,
    private val drivers: SqlDrivers,
    private val mergedPath: Path,
    private val queue: CacheQueue,
    private val store: FileBlobStore,
) : Thumbnails {

    private val directory = Path(cacheRoot, "packs")
    private val _arrivals = MutableStateFlow(0)
    override val arrivals: StateFlow<Int> = _arrivals.asStateFlow()
    private val _outstanding = MutableStateFlow(0)
    override val outstanding: StateFlow<Int> = _outstanding.asStateFlow()

    init {
        SystemFileSystem.createDirectories(directory)
    }

    override fun has(album: Album): Boolean =
        album.thumbsId?.let { SystemFileSystem.exists(store.packPath(it)) } == true

    override fun cover(album: Album): ByteArray? {
        val thumbs = album.thumbsId ?: return null
        val path = store.packPath(thumbs)
        if (!SystemFileSystem.exists(path)) return null
        // §3 resolves a cover by descending into children until a photo is found, unless one
        // was set explicitly -- which is the reader's rule, not something to re-derive here.
        val photo = runCatching {
            CatalogReader(mergedPath, drivers).use { it.coverPhoto(of = album.id) }
        }.getOrNull() ?: return null
        return runCatching { ThumbPack(path, drivers).thumbnail(photo.id) }.getOrNull()
    }

    override fun all(album: Album): Map<Uuid, ByteArray> {
        val thumbs = album.thumbsId ?: return emptyMap()
        val path = store.packPath(thumbs)
        if (!SystemFileSystem.exists(path)) return emptyMap()
        return runCatching { ThumbPack(path, drivers).unpack() }.getOrDefault(emptyMap())
    }

    /**
     * Move this album's pack to the head of the queue.
     *
     * §6: nothing is ever disabled while loading, so opening an album with no pack yet is
     * allowed — and this is what makes that bearable rather than a minute of placeholders.
     */
    override fun prioritise(album: Album) {
        val ref = album.packRef() ?: return
        store.expectPack(ref.id)
        queue.visiblePacks(listOf(ref))
    }

    /**
     * The background sweep: every album's pack, behind whatever is on screen.
     *
     * ~0.45 GB across the library, about a minute at the measured link rate — which is what
     * makes it acceptable for an explicit download to sit below it on the ladder.
     */
    public fun sweep(albums: List<Album>) {
        val wanted = albums.mapNotNull { it.packRef() }.filterNot { store.has(it.id) }
        wanted.forEach { store.expectPack(it.id) }
        _outstanding.value = wanted.size
        queue.sweepPacks(wanted)
    }

    /** Called by the composition root as packs land, so the nav bar can count them down. */
    public fun noteArrivals(held: Set<net.stho.photos.catalog.ObjectId>, albums: List<Album>) {
        val packs = albums.mapNotNull { it.thumbsId }
        val landed = packs.count { it in held }
        _arrivals.value = landed
        _outstanding.value = (packs.size - landed).coerceAtLeast(0)
    }

    /**
     * A pack's size is not stored anywhere — it is a blob, not a LISTed shard — so it is
     * estimated from the album's photo count at §5's measured 14.0 KiB per thumbnail. Validated
     * against ten real packs: 9.7–16.6 KiB each, median 14.0.
     */
    private fun Album.packRef(): BlobRef? =
        thumbsId?.let { BlobRef(it, CacheQueue.packBytes(photoCount), id, BlobKind.Pack) }
}
