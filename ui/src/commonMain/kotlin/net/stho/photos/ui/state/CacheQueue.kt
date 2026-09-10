package net.stho.photos.ui.state

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.stho.photos.catalog.ObjectId

/**
 * One blob the queue may fetch, with everything the ladder needs to place it.
 *
 * [bytes] comes from `photo.bytes` in the merged DB, which §3 defines as the size of the blob a
 * tap actually fetches — so the size class below is decided without a single HEAD request. A
 * thumbnail pack has no stored size and is estimated from its photo count; see `packBytes`.
 */
public data class BlobRef(
    public val id: ObjectId,
    public val bytes: Long,
    /** The album this belongs to, so leaving one can drop its work and clearing one can find it. */
    public val album: Uuid,
    public val kind: BlobKind = BlobKind.Media,
)

/**
 * Which of the two on-device directories a blob belongs in, which *is* its eviction policy.
 *
 * §4's layout keeps packs apart from `blobs/` deliberately: a pack is always kept, so that every
 * grid opens instantly and offline, while media is what a clear removes. Putting the distinction
 * in the type means a clear cannot delete a pack by accident.
 */
public enum class BlobKind { Pack, Media }

/**
 * §6's priority ladder, highest first.
 *
 * The order of these constants *is* the ladder: `compareTo` on the enum is what the queue sorts
 * by, so there is no separate priority table that could disagree with the documented order.
 */
public enum class Tier {
    /** The photo on screen, and its video/live blobs. Nothing outranks it. */
    OpenPhoto,

    /** The viewer's ±3, so a swipe lands on something already fetched. */
    Neighbour,

    /** Packs for the album rows currently on screen. */
    VisiblePack,

    /** The open album's images, grid order outward from the visible range. */
    OpenAlbum,

    /** Every remaining pack, in the background. ~0.45 GB across the library. */
    PackSweep,

    /** Explicitly requested albums, in request order. Survives navigation. */
    Requested,
}

/**
 * What the queue needs from a platform, and nothing more.
 *
 * Deliberately four small methods rather than a downloader: the ladder, the worker roles and
 * the retry policy are the part that can be wrong, and they live above this line where a test
 * with no zone and no filesystem can reach them.
 */
public interface BlobStore {
    /** Is this blob already on disk? */
    public fun has(id: ObjectId): Boolean

    /** Fetch it, or throw. Cancellable: the immediate worker relies on that. */
    public suspend fun fetch(id: ObjectId)

    /** Remove it. Used by a clear, which also stops the album's download. */
    public fun delete(id: ObjectId)

    /**
     * Every blob currently on disk, as one directory read.
     *
     * Measured at ~12.5 ms for 34,607 names, against ~119 ms to `stat` them all — which is why
     * sizes come from the catalog and this answers only presence.
     */
    public fun present(): Set<ObjectId>
}

/**
 * The download scheduler: what is fetched, in what order, and by which worker.
 *
 * Three worker roles rather than one undifferentiated pool, because the two size classes have
 * opposite bottlenecks — measured against the live zone, from a domestic link to Frankfurt:
 *
 * | concurrency | small blobs | large blobs |
 * |---|---|---|
 * | 1 | 2.33 MB/s, 168 ms each | 6.36 MB/s |
 * | 2 | 3.88 MB/s | 7.49 MB/s |
 * | 4 | 5.17 MB/s | 7.12 MB/s, 3.7 s each |
 * | 8 | 7.84 MB/s | — |
 *
 * A small blob spends 100 ms of its 168 ms waiting for the first byte, so concurrency is what
 * reaches link rate; a large one saturates on one or two streams and only grows its own latency
 * after that. So [immediate] serves what is on screen and may be cancelled, [small] is a pool
 * for the 99.6% of blobs under [largeThreshold], and [general] is the single lane that may take
 * a large one — which is what stops four video transcodes from occupying every worker at once.
 *
 * The measured spread that makes this necessary: 0.37% of blobs are >= 4 MiB, but they cluster.
 * One album in the sample holds seven of them totalling 214 MiB.
 */
public class CacheQueue(
    private val store: BlobStore,
    private val scope: CoroutineScope,
    /** Workers that may take only blobs at or under [largeThreshold]. */
    smallWorkers: Int = SMALL_WORKERS,
    /**
     * The size class boundary. 4 MiB rather than 1 MiB from measurement: 1 MiB would class 9.6%
     * of blobs large — mostly harmless 1–2 MiB stills — while 4 MiB classes 0.37%, which is very
     * nearly the exact set of blobs that can stall anything.
     */
    private val largeThreshold: Long = LARGE_THRESHOLD,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    /** Overridden by tests so a retry does not spend real time. */
    private val backoff: suspend (attempt: Int) -> Unit = { delay(BACKOFF_MS shl it) },
) {
    private val guard = Mutex()
    private val pending = mutableMapOf<ObjectId, Entry>()
    private val inFlight = mutableSetOf<ObjectId>()

    /**
     * Blobs that failed [maxAttempts] times, skipped for the rest of the session.
     *
     * Not retried in a loop: a blob that will not download would otherwise keep a worker busy
     * for ever, and the row shows an error rather than a gauge frozen at 97%. The next launch
     * tries again, because this set does not survive it.
     */
    private val failed = mutableSetOf<ObjectId>()
    private var sequence = 0L

    private val _held = MutableStateFlow(store.present())

    /** Everything on disk. The album rows join this against `photo.bytes` to fill their strip. */
    public val held: StateFlow<Set<ObjectId>> = _held.asStateFlow()

    private val _active = MutableStateFlow(emptySet<ObjectId>())

    /**
     * What a worker is fetching *right now* — the only thing that makes anything pulse.
     *
     * Motion means one thing everywhere in the app: bytes are moving for this item. A blob that
     * is merely queued does not pulse, which is what keeps a pulsing row worth looking at.
     */
    public val active: StateFlow<Set<ObjectId>> = _active.asStateFlow()

    private val _wanted = MutableStateFlow(emptySet<Uuid>())

    /**
     * Albums explicitly asked for, cleared as each completes.
     *
     * Persisted by the composition root, so a request survives the app being killed: a relaunch
     * is the worst possible moment to silently drop a download started to prepare for a flight.
     */
    public val wanted: StateFlow<Set<Uuid>> = _wanted.asStateFlow()

    private val workers: List<Worker> = buildList {
        // Reserved, and deliberately idle when nothing is on screen: the guarantee that a tap is
        // served at once is worth more than the throughput of borrowing it out, and borrowing
        // would need a drop-and-requeue path that this does not.
        add(Worker(accepts = { it.tier <= Tier.Neighbour }, cancellable = true))
        repeat(smallWorkers) { add(Worker(accepts = { it.ref.bytes <= largeThreshold })) }
        add(Worker(accepts = { true }))
    }

    init {
        workers.forEach { worker -> scope.launch { worker.run() } }
    }

    // ------------------------------------------------------------------ what the screens say

    /**
     * The photo on screen and its neighbours — tiers 0 and 1 — replacing whatever was there.
     *
     * [open] is the open photo's own blobs first, then the ±3 either side. Anything the previous
     * call queued and this one does not is dropped, and if the immediate worker is mid-fetch on
     * one of those it is **cancelled**: that is the one place the never-cancel rule does not
     * apply, because it was justified by a blob being ~168 ms and a swiped-past video is 10 s.
     */
    public fun viewing(open: List<BlobRef>, neighbours: List<BlobRef> = emptyList()) {
        scope.launch {
            guard.withLock {
                pending.entries.removeAll { it.value.tier <= Tier.Neighbour }
                open.forEach { enqueueLocked(it, Tier.OpenPhoto) }
                neighbours.forEach { enqueueLocked(it, Tier.Neighbour) }
                val keep = (open + neighbours).map(BlobRef::id).toSet()
                workers.filter { it.cancellable }.forEach { worker ->
                    val target = worker.target
                    if (target != null && target !in keep) worker.abandon()
                }
            }
            wake()
        }
    }

    /** Packs for the album rows on screen (tier 2). */
    public fun visiblePacks(packs: List<BlobRef>): Unit = replaceTier(Tier.VisiblePack, packs)

    /**
     * The open album's images (tier 3), in the order the grid will want them.
     *
     * One of only two things that fetch images. The other is [request].
     */
    public fun openAlbum(blobs: List<BlobRef>): Unit = replaceTier(Tier.OpenAlbum, blobs)

    /**
     * Leaving the album stops its pending downloads, keeping whatever landed.
     *
     * What is missing is derived from the filesystem rather than remembered, so there is no
     * resume state to keep consistent — coming back re-queues exactly what is still absent.
     */
    public fun leaveAlbum(): Unit = replaceTier(Tier.OpenAlbum, emptyList())

    /** The background sweep over every album's pack (tier 4). */
    public fun sweepPacks(packs: List<BlobRef>): Unit = replaceTier(Tier.PackSweep, packs)

    /**
     * An explicit request: fetch this album and keep going whatever the person browses next.
     *
     * Lowest tier, because everything above it is something they are looking at right now — and
     * the whole pack sweep is ~0.45 GB, so being last costs a request about a minute.
     */
    public fun request(album: Uuid, blobs: List<BlobRef>) {
        _wanted.value = _wanted.value + album
        scope.launch {
            guard.withLock { blobs.forEach { enqueueLocked(it, Tier.Requested) } }
            wake()
            settleWanted(album)
        }
    }

    /** Stop a request, keeping what has already landed. */
    public fun stop(album: Uuid) {
        _wanted.value = _wanted.value - album
        scope.launch {
            guard.withLock {
                pending.entries.removeAll { it.value.tier == Tier.Requested && it.value.ref.album == album }
            }
        }
    }

    /**
     * Clear an album, which also stops it.
     *
     * Allowed at any time — including on the album currently open, where the blobs simply
     * re-fetch as browsing continues, which is the same path as never having had them. In-flight
     * fetches are left to finish and their bytes are removed on arrival rather than being
     * cancelled, so no partial file is left behind.
     */
    public fun clear(album: Uuid, blobs: List<BlobRef>) {
        stop(album)
        scope.launch {
            guard.withLock { pending.entries.removeAll { it.value.ref.album == album } }
            // Packs are never evicted (§6): clearing an album removes its images and video,
            // and leaves the grid working offline.
            blobs.filter { it.kind == BlobKind.Media }.forEach { store.delete(it.id) }
            _held.value = store.present()
        }
    }

    /**
     * Suspend until this blob is on disk.
     *
     * How the viewer waits: the queue is the only thing that downloads, so a decoder asks for
     * presence rather than fetching a second copy of what a worker is already pulling. Cancelled
     * with whatever scope is waiting — leaving the photo simply stops waiting for it.
     */
    public suspend fun awaitHeld(id: ObjectId) {
        if (id in _held.value) return
        _held.first { id in it }
    }

    /** Re-read the directory — on entering the screen that shows totals. */
    public fun refreshHeld() {
        scope.launch { _held.value = store.present() }
    }

    // ------------------------------------------------------------------------------ internals

    private fun replaceTier(tier: Tier, blobs: List<BlobRef>) {
        scope.launch {
            guard.withLock {
                pending.entries.removeAll { it.value.tier == tier }
                blobs.forEach { enqueueLocked(it, tier) }
            }
            wake()
        }
    }

    /** Caller holds [guard]. A blob already on disk, in flight, or given up on is not queued. */
    private fun enqueueLocked(ref: BlobRef, tier: Tier) {
        if (ref.id in failed || ref.id in inFlight) return
        if (ref.id in _held.value || store.has(ref.id)) return
        val existing = pending[ref.id]
        // A blob can be wanted by two tiers at once -- the open photo is also one of the open
        // album's images. It keeps the better claim, so opening a photo cannot demote it.
        if (existing != null && existing.tier <= tier) return
        pending[ref.id] = Entry(ref, tier, sequence++)
    }

    private fun wake(): Unit = workers.forEach { it.wake.trySend(Unit) }

    /** The highest-ladder item this worker may take, or null. */
    private fun claimLocked(worker: Worker): Entry? {
        val next = pending.values
            .filter { it.ref.id !in inFlight && worker.accepts(it) }
            .minWithOrNull(compareBy({ it.tier }, { it.sequence }))
            ?: return null
        pending.remove(next.ref.id)
        inFlight += next.ref.id
        return next
    }

    private suspend fun settleWanted(album: Uuid) {
        guard.withLock {
            val outstanding = pending.values.any { it.ref.album == album } ||
                inFlight.any { id -> pending[id]?.ref?.album == album }
            if (!outstanding) _wanted.value = _wanted.value - album
        }
    }

    private data class Entry(val ref: BlobRef, val tier: Tier, val sequence: Long)

    /**
     * One worker, defined by what it will accept rather than by a queue of its own.
     *
     * A single shared ladder with per-worker admission is what lets the small pool drain
     * everything while nothing large is queued, instead of idling beside a full large lane.
     */
    private inner class Worker(
        val accepts: (Entry) -> Boolean,
        val cancellable: Boolean = false,
    ) {
        val wake = Channel<Unit>(Channel.CONFLATED)

        /** What this worker is fetching, so a stale immediate target can be found and dropped. */
        var target: ObjectId? = null
            private set

        private var job: Job? = null

        fun abandon() {
            job?.cancel()
        }

        suspend fun run() {
            while (true) {
                val entry = guard.withLock { claimLocked(this) }
                if (entry == null) {
                    wake.receive()
                    continue
                }
                target = entry.ref.id
                _active.update { it + entry.ref.id }
                // The fetch runs as a child job rather than inline so that a cancellable worker
                // has something to cancel without tearing down its own loop.
                val running = scope.launch { fetch(entry) }
                job = running
                running.join()
                job = null
                target = null
                _active.update { it - entry.ref.id }
                guard.withLock { inFlight -= entry.ref.id }
                wake()
            }
        }

        private suspend fun fetch(entry: Entry) {
            var attempt = 0
            while (true) {
                try {
                    store.fetch(entry.ref.id)
                    _held.update { it + entry.ref.id }
                    settleWanted(entry.ref.album)
                    return
                } catch (cancelled: CancellationException) {
                    // Abandoning a swiped-past video is the point, not a failure. Rethrowing is
                    // what keeps structured concurrency intact.
                    throw cancelled
                } catch (failure: Exception) {
                    attempt++
                    if (attempt >= maxAttempts) {
                        guard.withLock { failed += entry.ref.id }
                        return
                    }
                    backoff(attempt)
                }
            }
        }
    }

    public companion object {
        /**
         * Four, measured. The small pool alone reaches 5.17 MB/s at 241 ms per object; past four,
         * each added connection buys ~0.5 MB/s and costs ~35 ms on *every* fetch, which is a bad
         * trade for a screen someone is looking at.
         */
        public const val SMALL_WORKERS: Int = 4

        /** 4 MiB. See [CacheQueue.largeThreshold]. */
        public const val LARGE_THRESHOLD: Long = 4L * 1024 * 1024

        public const val MAX_ATTEMPTS: Int = 3
        private const val BACKOFF_MS: Long = 250

        /**
         * A pack's size, which nothing stores: it is a blob, not a LISTed shard.
         *
         * 14.0 KiB per thumbnail, validated against ten real packs on disk (measured 9.7–16.6,
         * median 14.0) and matching §5's per-album median. Median pack lands at 0.46 MiB and only
         * 9% exceed the large threshold, so packs mostly classify small by themselves.
         */
        public fun packBytes(photoCount: Int): Long = photoCount * 14L * 1024
    }
}
