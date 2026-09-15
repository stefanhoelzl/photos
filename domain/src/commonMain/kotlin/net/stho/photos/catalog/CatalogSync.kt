package net.stho.photos.catalog

import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.ShardFailure
import net.stho.photos.ShardUnavailableFailure
import net.stho.photos.storage.Body
import net.stho.photos.storage.ETag
import net.stho.photos.storage.PutResult
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.list

/**
 * What a sync did, and what it could not make sense of.
 *
 * The design's stance is that conflicts are **reported, not resolved** (§4): every field below
 * the first four is a condition deliberately left for a person to decide about, and it is named
 * on *every* run until someone deals with it.
 */
public data class SyncReport(
    public val fetchedShards: List<Uuid> = emptyList(),
    public val deletedAlbums: List<Uuid> = emptyList(),
    public val bytesFetched: Long = 0,
    /** False when the LIST matched what was already on disk, so nothing was replayed. */
    public val rebuilt: Boolean = false,
    public val albums: Int = 0,
    public val photos: Int = 0,
    /**
     * Shards written by a newer schema than this build understands. Skipped.
     *
     * A caller reconciling a local library against the zone must treat these as **unreadable,
     * not absent** — an absent album gets re-uploaded as a new one, and with UUID keys nothing
     * collides to stop the duplicate (§3).
     */
    public val unreadableShards: List<ShardProbe> = emptyList(),
    /** Albums whose `parent` named a shard that is not present. Surfaced at the root. */
    public val orphanedAlbums: List<Uuid> = emptyList(),
    /** Names appearing more than once under one parent. Shown, never merged (§2). */
    public val duplicateNames: List<String> = emptyList(),
    /**
     * LIST entries that were not shards: the `meta/` directory marker, or a key whose name is not
     * a uuid.
     */
    public val ignoredKeys: List<String> = emptyList(),
) {
    /** True when a person should look at something. */
    public val hasAnomalies: Boolean
        get() = unreadableShards.isNotEmpty() ||
            orphanedAlbums.isNotEmpty() ||
            duplicateNames.isNotEmpty()
}

/** What [CatalogSync.refresh] found: every shard now on disk, plus what the LIST implied. */
/**
 * How far a sync has got, for a caller that has a screen to keep honest.
 *
 * Deliberately not a `Flow`: this is called from inside the fetch loop and a caller either
 * wants the number or does not.
 */
public fun interface ShardProgress {
    public fun at(fetched: Int, total: Int)
}

public data class RefreshResult(
    public val shards: List<Shard>,
    public val report: SyncReport,
    /**
     * Whether the LIST changed anything. `false` means the no-op run §7 promises: one request and
     * nothing else.
     */
    public val changed: Boolean,
    /** album id → ETag, for the `If-Match` on a rewrite. */
    public val etags: Map<Uuid, ETag>,
)

/** The outcome of a guarded shard rewrite (§2). */
public sealed interface ShardWriteResult {
    public data class Written(public val etag: ETag?) : ShardWriteResult

    /** Someone else wrote this shard. Re-read it, re-decide, and try again. */
    public data object StaleETag : ShardWriteResult
}

/**
 * §4's sync loop, end to end.
 *
 * Lives here rather than in the CLI and the app separately: the ingest tool and the phone running
 * slightly different versions of marker filtering, ETag diffing or delete-means-gone is exactly
 * the laptop/phone disagreement §7 says the shared module exists to prevent.
 *
 * Talks to [S3Client] directly. Ktor's engine is already the seam (§7), so tests drive this
 * offline with canned `<ListBucketResult>` XML rather than through a second protocol of its own.
 *
 * A [Mutex] rather than a confined dispatcher: what needs serialising is the *writer* — this
 * object owns two long-lived connections, `sync_state.db` and the lazily created `merged.db` —
 * and §3's "one writer, many readers" means readers open their own connection and need no
 * locking at all. `limitedParallelism(1)` would pin a thread for this object's whole lifetime to
 * say the same thing.
 */
public class CatalogSync(
    public val s3: S3Client,
    /** Holds `sync_state.db`, `shards/`, `merged.db` and `blobs/` (§4). */
    public val cacheRoot: Path,
    private val drivers: SqlDrivers,
    clock: Clock = Clock.System,
) : AutoCloseable {

    private val mutex = Mutex()
    private val shardsDirectory = Path(cacheRoot, "shards")
    private val state: SyncState

    /**
     * Created on first rebuild, not at startup.
     *
     * The CLI stops at [refresh] and never merges anything (§7), so eagerly opening this would
     * leave a schema-only database in the cache of every laptop run — a file contradicting the
     * design's claim that the merged DB is the phone's alone.
     */
    private var writer: CatalogWriter? = null

    init {
        SystemFileSystem.createDirectories(shardsDirectory)
        state = SyncState(Path(cacheRoot, "sync_state.db"), drivers, clock)
    }

    public val mergedPath: Path get() = Path(cacheRoot, "merged.db")

    public fun shardPath(albumId: Uuid): Path = Path(shardsDirectory, "$albumId.db")

    // ------------------------------------------------------------------------------- the loop

    /**
     * LIST, diff, fetch, rebuild, record.
     *
     * One LIST covers thumbnails and every derivative too: a pack is a blob referenced by
     * [AlbumInfo.thumbsId], so nothing in the zone can change without some shard's ETag moving
     * (§4).
     */
    public suspend fun sync(
        /**
         * Rebuild even when the LIST moved nothing. Deletions are recorded before the rebuild runs,
         * so a rebuild that failed left a catalog no later diff would ever touch again. The app
         * passes this on every sync; the hourly CLI does not need a merged DB at all.
         */
        alwaysRebuild: Boolean = false,
        onProgress: ShardProgress = ShardProgress { _, _ -> },
    ): SyncReport = mutex.withLock { syncing(alwaysRebuild, onProgress) }

    /**
     * Everything [sync] does except the rebuild: LIST, diff, fetch, record ETags, and hand back
     * every shard now on disk.
     *
     * The CLI stops here — it reconciles against the shards themselves and never wants a merged
     * database (§7). Splitting it out rather than giving the CLI its own loop is the point: one
     * implementation of "what does the zone hold", exercised by both devices, so they cannot
     * drift in how they diff or in what a missing key means.
     */
    public suspend fun refresh(onProgress: ShardProgress = ShardProgress { _, _ -> }): RefreshResult =
        mutex.withLock { refreshing(onProgress) }

    /** The single LIST, diffed against what is on disk. The whole sync plan (§4). */
    public suspend fun plan(): ShardDiff = mutex.withLock { planning() }

    private suspend fun syncing(alwaysRebuild: Boolean, onProgress: ShardProgress): SyncReport {
        val refreshed = refreshing(onProgress)
        if (!refreshed.changed && !alwaysRebuild) return refreshed.report

        val summary = ensureWriter().rebuild(refreshed.shards)
        return refreshed.report.copy(
            rebuilt = true,
            albums = summary.albums,
            photos = summary.photos,
            orphanedAlbums = summary.orphanedAlbums,
            duplicateNames = duplicateNames(),
        )
    }

    private suspend fun refreshing(onProgress: ShardProgress): RefreshResult {
        val diff = planning()
        val fetched = mutableListOf<Uuid>()
        var bytesFetched = 0L

        // A first sync fetches every shard in the zone, which is measured in tens of seconds
        // (§4). Reporting as it goes is what lets a caller show something other than an empty
        // list -- the CLI passes nothing and is unaffected.
        onProgress.at(0, diff.changed.size)
        for ((albumId, etag) in diff.changed) {
            bytesFetched += fetchShard(albumId)
            // Recorded only once the file has landed, so a crash leaves the state behind reality
            // rather than ahead of it — one re-download, not a missing album.
            state.record(albumId, etag)
            fetched += albumId
            onProgress.at(fetched.size, diff.changed.size)
        }

        for (albumId in diff.deleted) {
            SystemFileSystem.delete(shardPath(albumId), mustExist = false)
            state.forget(albumId)
        }

        val onDisk = loadShards()
        return RefreshResult(
            shards = onDisk.shards,
            report = SyncReport(
                fetchedShards = fetched,
                deletedAlbums = diff.deleted,
                bytesFetched = bytesFetched,
                unreadableShards = onDisk.unreadable,
                ignoredKeys = diff.ignoredKeys,
            ),
            changed = !diff.isEmpty,
            etags = state.known(),
        )
    }

    private suspend fun planning(): ShardDiff {
        val known = state.known()
        val changed = mutableMapOf<Uuid, ETag>()
        val ignoredKeys = mutableListOf<String>()
        val seen = mutableSetOf<Uuid>()

        s3.list(prefix = META_PREFIX).collect { listed ->
            // bunny.net still materialises the `meta/` marker itself — one per prefix now that
            // keys do not nest, but LIST returns it and it is not a shard (§2).
            val albumId = if (listed.isDirectoryMarker) null else listed.key.asShardAlbumId()
            // No ETag means nothing to diff against, so the shard cannot be trusted to be
            // current. Skipped rather than guessed at.
            val etag = listed.etag
            if (albumId == null || etag == null) {
                ignoredKeys += listed.key
                return@collect
            }
            seen += albumId
            if (known[albumId] != etag || !SystemFileSystem.exists(shardPath(albumId))) {
                changed[albumId] = etag
            }
        }

        // A key that vanished means the album was deleted (§4). There is no manifest to consult,
        // so absence *is* the signal.
        return ShardDiff(
            changed = changed,
            deleted = known.keys.filterNot(seen::contains).sortedBy(Uuid::toString),
            ignoredKeys = ignoredKeys,
        )
    }

    /**
     * Streams one shard into the cache and returns its size.
     *
     * Down to a scratch name and then moved, so a shard file is never half a download: §4's diff
     * would read a truncated file as a shard that failed to parse rather than as one still on its
     * way.
     */
    private suspend fun fetchShard(albumId: Uuid): Long {
        val scratch = Path(shardsDirectory, "$albumId.part")
        s3.download(albumId.shardKey, scratch)
        val size = SystemFileSystem.metadataOrNull(scratch)?.size
            ?: throw ShardUnavailableFailure(albumId)
        SystemFileSystem.atomicMove(scratch, shardPath(albumId))
        return size
    }

    /**
     * Reads every shard on disk. A shard too new to read is skipped and *probed*, never treated
     * as absent: §3's two stable columns say which folder it claims, which is what stops that
     * folder being re-uploaded as a duplicate album.
     */
    private fun loadShards(): DiskShards {
        val files = runCatching { SystemFileSystem.list(shardsDirectory) }
            .getOrDefault(emptyList())
            .filter { it.name.endsWith(".db") && it.name.removeSuffix(".db").isAlbumId() }
            .sortedBy { it.name }

        val shards = mutableListOf<Shard>()
        val unreadable = mutableListOf<ShardProbe>()
        for (file in files) {
            try {
                shards += file.readShard(drivers)
            } catch (skipped: ShardFailure.UnsupportedVersion) {
                // A probe that itself fails is left to throw: the album is then genuinely
                // unidentifiable, and continuing would risk duplicating it.
                unreadable += file.probeShard(drivers)
            }
        }
        return DiskShards(shards, unreadable)
    }

    private class DiskShards(val shards: List<Shard>, val unreadable: List<ShardProbe>)

    /**
     * Asked of the merged database rather than recomputed from shards: the rule for what counts
     * as a duplicate lives in exactly one place, and it has just been rebuilt.
     */
    private fun duplicateNames(): List<String> =
        CatalogReader(mergedPath, drivers).use { reader -> reader.duplicateNames().map(DuplicateName::name) }

    /**
     * §4 says `merged.db` can be deleted at any moment and rebuilt. If it is deleted while this
     * connection is open, the handle still points at the unlinked inode — the rebuild would
     * succeed into a file that no longer has a name, and the catalog would silently fail to
     * reappear. So the file's existence is checked before every rebuild, not only at startup.
     */
    private fun ensureWriter(): CatalogWriter {
        writer?.let { if (SystemFileSystem.exists(mergedPath)) return it else it.close() }
        return CatalogWriter(mergedPath, drivers).also { writer = it }
    }

    // --------------------------------------------------------------------- writing a shard back

    /**
     * Uploads a shard, guarded by `If-Match` (§2's single-owner rule).
     *
     * A 412 means another device wrote it first. That is returned rather than retried: re-deciding
     * what the shard should contain is the caller's business, not this method's, and a blind retry
     * would overwrite the other device's work.
     *
     * The shard is built in the cache directory rather than a temporary one — §3 says the only
     * scratch file this design needs is this one — and only takes its final name once the PUT has
     * been accepted, so a rejected write leaves the local copy as it was.
     */
    public suspend fun writeShard(shard: Shard, ifMatch: ETag?): ShardWriteResult = mutex.withLock {
        val albumId = shard.info.id
        val scratch = Path(shardsDirectory, "$albumId.part")
        shard.writeTo(scratch, drivers)
        try {
            when (val result = s3.put(albumId.shardKey, Body.File(scratch), ifMatch = ifMatch)) {
                PutResult.StaleETag -> ShardWriteResult.StaleETag
                is PutResult.Written -> {
                    result.etag?.let { state.record(albumId, it) }
                    SystemFileSystem.atomicMove(scratch, shardPath(albumId))
                    ShardWriteResult.Written(result.etag)
                }
            }
        } finally {
            SystemFileSystem.delete(scratch, mustExist = false)
        }
    }

    /**
     * Re-downloads one shard and records its new ETag. The 412 recovery path (§2): read what
     * actually landed, re-decide against it, write again.
     */
    public suspend fun reload(albumId: Uuid): Shard = mutex.withLock {
        fetchShard(albumId)
        s3.head(albumId.shardKey)?.etag?.let { state.record(albumId, it) }
        shardPath(albumId).readShard(drivers)
    }

    /** The ETag this device last saw for a shard, for `If-Match`. */
    public suspend fun etag(of: Uuid): ETag? = mutex.withLock { state.known()[of] }

    /**
     * Removes an album's shard from the zone and from the local cache.
     *
     * The shard goes **first**, so the album stops existing before its blobs do: the reverse order
     * would leave a shard pointing at objects that are gone, and §2's rule is that the catalog
     * never lies. The blobs it listed become unreferenced and are the caller's to delete.
     */
    public suspend fun deleteShard(albumId: Uuid): Unit = mutex.withLock {
        s3.delete(albumId.shardKey)
        SystemFileSystem.delete(shardPath(albumId), mustExist = false)
        state.forget(albumId)
    }

    // ---------------------------------------------------------------------------- local state

    /**
     * Drops the merged database's contents and replays every shard on disk. No network.
     *
     * This is the repair path §4 says the UI does not need a button for — nothing can need it,
     * because the merged DB is derived.
     */
    public suspend fun rebuildFromDisk(): SyncReport = mutex.withLock {
        val onDisk = loadShards()
        val summary = ensureWriter().rebuild(onDisk.shards)
        SyncReport(
            rebuilt = true,
            albums = summary.albums,
            photos = summary.photos,
            unreadableShards = onDisk.unreadable,
            orphanedAlbums = summary.orphanedAlbums,
            duplicateNames = duplicateNames(),
        )
    }

    override fun close() {
        writer?.close()
        writer = null
        state.close()
    }
}

private fun String.isAlbumId(): Boolean = runCatching { Uuid.parse(this) }.isSuccess
