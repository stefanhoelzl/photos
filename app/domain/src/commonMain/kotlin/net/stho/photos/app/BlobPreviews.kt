package net.stho.photos.app

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import net.stho.photos.model.PhotoRow

/**
 * §6's viewing-image tier: a blob the queue has landed, decoded and ready to draw.
 *
 * Nothing is downloaded ahead of time and nothing is auto-evicted: a blob fetched once stays in
 * `blobs/` until it is cleared. Previews live there rather than beside the packs precisely
 * because the policies differ — packs are always kept, blobs are not.
 *
 * Decoded images are held in a small cache so that swiping back and forth does not re-decode: a
 * 2048px frame is ~16 MB of pixels, so this is deliberately shallow.
 */
public class BlobPreviews(
    cacheRoot: Path,
    private val decoder: PreviewDecoder,
    private val queue: CacheQueue,
    private val scope: CoroutineScope,
) : Previews, Videos {

    private val media = MediaFiles(cacheRoot)

    /**
     * The decoded cache, as an immutable map behind a `StateFlow`.
     *
     * It is read from [cached] on every frame of a composition and written from a download
     * worker, so the two need to not tear. A lock is unavailable — [cached] cannot suspend —
     * and `update` is a lock-free compare-and-set, which is exactly the shape this needs.
     *
     * **Eviction is by insertion rather than by access**, which is the one thing that changed
     * when this moved out of the JVM: an access-ordered `LinkedHashMap` would have to be
     * rewritten on every *read*, and a read here happens once per frame per tile. At a depth of
     * eight against §6's ±3 prefetch the two orders keep the same entries for any swipe that
     * stays inside the window, which is the case the cache exists for.
     */
    private val decoded = MutableStateFlow<Map<Uuid, Preview>>(emptyMap())
    private var prefetching: Job? = null

    /**
     * One download per blob, however many callers want it.
     *
     * Without this, opening a photo and prefetching its neighbours both start the *same*
     * fetch, both write the same scratch file, and whichever renames it second fails on a file
     * that is no longer there. Sharing one `Deferred` is also the only way the second caller
     * gets the picture rather than an error.
     */
    private val inFlight = mutableMapOf<Uuid, Deferred<Preview?>>()
    private val guard = Mutex()

    override fun cached(photo: PhotoRow): Preview? = decoded.value[photo.id]

    override suspend fun load(photo: PhotoRow): Preview? {
        decoded.value[photo.id]?.let { return it }
        if (photo.imageId == null) return null
        val shared = guard.withLock {
            inFlight.getOrPut(photo.id) { scope.async { fetchAndDecode(photo) } }
        }
        return try {
            shared.await()
        } finally {
            guard.withLock { if (inFlight[photo.id] === shared && shared.isCompleted) inFlight -= photo.id }
        }
    }

    /**
     * Wait for the queue to land the blob, then decode it.
     *
     * The queue is the only thing that fetches, so the viewer cannot race the ladder for a
     * second copy of what a worker is already pulling — and the open photo is tier 0, so
     * waiting is the fastest route rather than a concession.
     */
    private suspend fun fetchAndDecode(photo: PhotoRow): Preview? {
        val blob = photo.imageId ?: return null
        queue.awaitHeld(blob)
        // Null only when a clear removed it between landing and here.
        val image = decoder.decode(media.find(blob) ?: return null) ?: return null
        return Preview(photo.id, image).also { preview -> remember(photo.id, preview) }
    }

    /** Add to the cache, dropping the oldest once it is over depth. */
    private fun remember(id: Uuid, preview: Preview) {
        decoded.update { held ->
            val next = held + (id to preview)
            if (next.size <= LRU) next else next - next.keys.first()
        }
    }

    /**
     * Nothing, deliberately.
     *
     * §6's ±3 is tier 1 on the ladder now, and the model places it — so a second prefetch here
     * would only queue the same blobs a second time, from a component that cannot see what the
     * person is looking at.
     */
    override fun prefetch(photos: List<PhotoRow>, index: Int): Unit = Unit

    /**
     * §5 keeps video *originals* on the laptop, so what the zone holds — and what this
     * fetches — is the transcode. Same cache and same policy as a preview.
     */
    override suspend fun localFile(photo: PhotoRow): String? {
        val blob = photo.videoId ?: return null
        queue.awaitHeld(blob)
        return media.find(blob)?.toString()
    }

    /**
     * Both halves of a Live Photo, once the queue holds both. They are already tier 0 — the open
     * photo's `objectIds` include them — so this only waits, exactly as [localFile] does. The
     * paths are the blobs themselves, whose extensions are what `PHLivePhoto` pairs by (§6).
     */
    override suspend fun livePair(photo: PhotoRow): LivePair? {
        val still = photo.liveStillId ?: return null
        val video = photo.liveVideoId ?: return null
        queue.awaitHeld(still)
        queue.awaitHeld(video)
        return LivePair(media.find(still)?.toString() ?: return null, media.find(video)?.toString() ?: return null)
    }

    /** Leaving the album abandons the queue *and* whatever it had already started. */
    override fun cancelPrefetch() {
        prefetching?.cancel()
        prefetching = null
        scope.launch { guard.withLock { inFlight.values.forEach { it.cancel() }; inFlight.clear() } }
    }

    private companion object {
        const val LRU = 8
    }
}

/**
 * Bytes on disk to pixels.
 *
 * The one part of a preview that no shared code can do. §5's viewing tier is **HEIC**, chosen
 * because it is 15% smaller than JPEG and hardware-decoded on the phone — and each platform
 * reaches a HEIC decoder its own way: `CGImageSource` on iOS, libheif behind the C shim on
 * Linux. So this is a port, satisfied by the composition root, and until one is installed a
 * preview simply does not appear.
 */
public fun interface PreviewDecoder {
    public fun decode(file: Path): ImageBitmap?
}
