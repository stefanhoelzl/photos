package net.stho.photos.desktop

import kotlin.uuid.Uuid
import net.stho.photos.catalog.ObjectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.Album
import net.stho.photos.catalog.CatalogReader
import net.stho.photos.catalog.ThumbPack
import net.stho.photos.catalog.blobKey
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.storage.S3Client
import net.stho.photos.ui.state.Thumbnails

/**
 * Every album's thumbnail pack, fetched in the background and kept for ever (§6).
 *
 * §4's sync loop fetches shards; this is the step it gains for the app, and the one LIST it
 * already does is still enough to see a pack change — a pack is a blob referenced by
 * `album_info.thumbs_id`, so nothing can change without that shard's ETag moving.
 *
 * Packs live in their own directory, not in `blobs/`: policy is what the directory expresses.
 * These are always kept, while everything in `blobs/` is browse-to-cache and, from E.2,
 * clearable.
 *
 * The queue is ordered by what the person is looking at. Tapping an album promotes it, which is
 * what makes §6's "nothing is ever disabled while loading" bearable: the album opens on
 * placeholders and fills a second later rather than after the other 287.
 */
public class PackFetcher(
    private val s3: S3Client,
    private val cacheRoot: Path,
    private val drivers: SqlDrivers,
    private val mergedPath: Path,
    private val scope: CoroutineScope,
) : Thumbnails {

    private val directory = Path(cacheRoot, "packs")
    private val urgent = Channel<Album>(Channel.UNLIMITED)
    private val queued = Channel<Album>(Channel.UNLIMITED)
    private val _arrivals = MutableStateFlow(0)
    override val arrivals: StateFlow<Int> = _arrivals.asStateFlow()
    private val _outstanding = MutableStateFlow(0)
    override val outstanding: StateFlow<Int> = _outstanding.asStateFlow()

    init {
        SystemFileSystem.createDirectories(directory)
        scope.launch { drain() }
    }

    override fun has(album: Album): Boolean =
        album.thumbsId?.let { SystemFileSystem.exists(pathFor(it)) } == true

    override fun cover(album: Album): ByteArray? {
        val thumbs = album.thumbsId ?: return null
        val path = pathFor(thumbs)
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
        val path = pathFor(thumbs)
        if (!SystemFileSystem.exists(path)) return emptyMap()
        return runCatching { ThumbPack(path, drivers).unpack() }.getOrDefault(emptyMap())
    }

    override fun prioritise(album: Album) {
        if (album.thumbsId != null && !has(album)) {
            urgent.trySend(album)
            _outstanding.update { it + 1 }
        }
    }

    /** Everything the catalog knows about, queued behind whatever was tapped. */
    public fun fetchAll(albums: List<Album>) {
        val wanted = albums.filter { it.thumbsId != null && !has(it) }
        _outstanding.update { it + wanted.size }
        wanted.forEach { queued.trySend(it) }
    }

    private suspend fun drain() {
        while (true) {
            // Urgent first, and only then the background queue: `tryReceive` is what makes the
            // ordering a priority rather than a race.
            val next = urgent.tryReceive().getOrNull()
                ?: queued.tryReceive().getOrNull()
                ?: urgent.receive()
            fetch(next)
        }
    }

    private suspend fun fetch(album: Album) {
        val thumbs = album.thumbsId ?: return
        val path = pathFor(thumbs)
        if (SystemFileSystem.exists(path)) return
        // A scratch name per attempt, so two fetches of one pack cannot rename each other's
        // file out from under themselves.
        val partial = Path(directory, "$thumbs.${Uuid.random()}.part")
        try {
            s3.download(thumbs.blobKey, to = partial)
            SystemFileSystem.atomicMove(partial, path)
            _arrivals.update { it + 1 }
        } catch (cancelled: CancellationException) {
            // Closing the window cancels the queue by design. Swallowing cancellation would
            // break structured concurrency, and reporting it buries the lines that mean
            // something under one per queued album.
            SystemFileSystem.delete(partial, mustExist = false)
            throw cancelled
        } catch (failure: Exception) {
            // A pack that will not download is not fatal: the album still opens, on
            // placeholders. It is tried again on the next launch.
            SystemFileSystem.delete(partial, mustExist = false)
            System.err.println("pack ${album.name}: $failure")
        } finally {
            _outstanding.update { (it - 1).coerceAtLeast(0) }
        }
    }

    private fun pathFor(thumbs: ObjectId) = Path(directory, "$thumbs.db")
}
