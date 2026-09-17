@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package net.stho.photos.app

import kotlin.concurrent.atomics.AtomicReference
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
import net.stho.photos.app.BlobKind
import net.stho.photos.app.BlobRef
import net.stho.photos.app.CacheQueue
import net.stho.photos.app.Thumbnails

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

    /**
     * Every album as the catalog last stood, which arrivals are counted against — null until the
     * launch read or the first sweep. Replaced on every sweep: a sync can give an album a pack it
     * did not have, an addition landing (§8), and a count against a list read at launch never saw
     * that pack arrive, so the grid it belongs to never reloaded.
     */
    private val albums = AtomicReference<List<Album>?>(null)

    init {
        SystemFileSystem.createDirectories(directory)
    }

    /** Whether every pack the album's grid reads — its own and its additions' (§8) — has landed. */
    override fun has(album: Album): Boolean =
        album.packs.isNotEmpty() && album.packs.all { SystemFileSystem.exists(store.packPath(it)) }

    override fun cover(album: Album): ByteArray? {
        // An album of photos whose own pack has not landed stops here, before opening the merged
        // DB. A container has no pack (§2) and always goes on.
        album.thumbsId?.let { if (!SystemFileSystem.exists(store.packPath(it))) return null }
        // §3 resolves a cover by descending into children until a photo is found, unless one
        // was set explicitly -- which is the reader's rule, not something to re-derive here. The
        // thumbnail is then in the pack of whichever album holds that photo: the album itself, or
        // for a container the descendant it came from. Reading only the album's own pack left
        // every container row on the placeholder.
        // A photo the phone added and the laptop has not merged is in one of the album's addition
        // packs rather than its own (§8), so each is asked in turn.
        val (photo, packs) = runCatching {
            CatalogReader(mergedPath, drivers).use { reader ->
                val photo = reader.coverPhoto(of = album.id)
                val packs = photo?.let { reader.albumOf(it.id)?.packs }.orEmpty()
                photo to packs
            }
        }.getOrNull() ?: return null
        if (photo == null) return null
        return packs.firstNotNullOfOrNull { pack ->
            val path = store.packPath(pack)
            if (!SystemFileSystem.exists(path)) null
            else runCatching { ThumbPack(path, drivers).thumbnail(photo.id) }.getOrNull()
        }
    }

    /** Every thumbnail the album's landed packs hold: its own, and its additions' (§8). */
    override fun all(album: Album): Map<Uuid, ByteArray> = buildMap {
        for (pack in album.packs) {
            val path = store.packPath(pack)
            if (!SystemFileSystem.exists(path)) continue
            putAll(runCatching { ThumbPack(path, drivers).unpack() }.getOrDefault(emptyMap()))
        }
    }

    /**
     * Move this album's pack to the head of the queue.
     *
     * §6: nothing is ever disabled while loading, so opening an album with no pack yet is
     * allowed — and this is what makes that bearable rather than a minute of placeholders.
     */
    override fun prioritise(album: Album) {
        val refs = album.packRefs()
        if (refs.isEmpty()) return
        refs.forEach { store.expectPack(it.id) }
        queue.visiblePacks(refs)
    }

    /**
     * The background sweep: every album's pack, behind whatever is on screen.
     *
     * ~0.45 GB across the library, about a minute at the measured link rate — which is what
     * makes it acceptable for an explicit download to sit below it on the ladder.
     */
    public fun sweep(albums: List<Album>) {
        this.albums.store(albums)
        val wanted = albums.flatMap { it.packRefs() }.filterNot { store.has(it.id) }
        wanted.forEach { store.expectPack(it.id) }
        queue.sweepPacks(wanted)
        // Counted now as well as on the next arrival: a pack this sync added may already be on
        // disk, and then no arrival is coming to count it.
        count(albums) { store.has(it) }
    }

    /**
     * The catalog as it stood at launch, so arrivals count before the first sync. Kept only while
     * no sweep has run: a sweep's list is newer than any launch read.
     */
    public fun seed(albums: List<Album>) {
        this.albums.compareAndSet(null, albums)
    }

    /** Called by the composition root as packs land, so the nav bar can count them down. */
    public fun noteArrivals(held: Set<net.stho.photos.catalog.ObjectId>) {
        count(albums.load().orEmpty()) { it in held }
    }

    private fun count(albums: List<Album>, landed: (net.stho.photos.catalog.ObjectId) -> Boolean) {
        val packs = albums.flatMap { it.packs }
        val done = packs.count(landed)
        _arrivals.value = done
        _outstanding.value = (packs.size - done).coerceAtLeast(0)
    }

    /**
     * A pack's size is not stored anywhere — it is a blob, not a LISTed shard — so it is
     * estimated from the album's photo count at §5's measured 14.0 KiB per thumbnail. Validated
     * against ten real packs: 9.7–16.6 KiB each, median 14.0. An album with additions spreads its
     * count over its packs, which is only an estimate either way.
     */
    private fun Album.packRefs(): List<BlobRef> =
        packs.map { BlobRef(it, CacheQueue.packBytes(photoCount / packs.size), id, BlobKind.Pack) }
}
