package net.stho.photos.catalog

import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import net.stho.photos.ports.Journal
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.catalog.syncstate.Shard_state
import net.stho.photos.catalog.syncstate.SyncStateDatabase
import net.stho.photos.storage.ETag

private val shardStateAdapter = Shard_state.Adapter(
    album_idAdapter = uuidAdapter,
    etagAdapter = etagAdapter,
    fetched_atAdapter = instantAdapter,
)

/**
 * The ETag of every shard currently on disk (§4).
 *
 * A separate file from `merged.db` on purpose. If the ETags lived in the merged database, losing
 * it would mean re-fetching all 288 shards; here it stays purely derived and can be deleted and
 * rebuilt with no network. Deleting `sync_state.db` on its own forces a full re-fetch, which is a
 * free repair path.
 *
 * One connection, owned by the [CatalogSync] that guards it — like every other connection here.
 */
internal class SyncState(
    path: Path,
    drivers: SqlDrivers,
    private val clock: Clock,
) : AutoCloseable {

    private val driver = path.openDriver(
        drivers,
        SyncStateDatabase.Schema, creating = true, journal = Journal.WAL,
    )
    private val queries = SyncStateDatabase(driver, shardStateAdapter).syncStateQueries

    /** album id → the ETag of the shard on disk. */
    fun known(): Map<Uuid, ETag> =
        queries.selectKnown { albumId, etag -> albumId to etag }.executeAsList().toMap()

    /**
     * Recorded only once the shard is on disk, so a crash between the two leaves the state behind
     * reality rather than ahead of it — which costs one re-download, not a missing album.
     */
    fun record(albumId: Uuid, etag: ETag) {
        queries.record(album_id = albumId, etag = etag, fetched_at = clock.now())
    }

    fun forget(albumId: Uuid) {
        queries.forget(albumId)
    }

    override fun close(): Unit = driver.close()
}

/** What the LISTs imply for the local copy. */
public data class ShardDiff(
    /** Shards to download: new, or whose ETag moved. */
    public val changed: Map<Uuid, ETag> = emptyMap(),
    /** Albums whose key is gone from the zone. §4: absent means deleted. */
    public val deleted: List<Uuid> = emptyList(),
    /**
     * Keys the LISTs returned that are not shards — a prefix's directory marker, or anything whose
     * name is not a uuid. Skipped, never guessed at.
     */
    public val ignoredKeys: List<String> = emptyList(),
    /** Which of [changed] live under `addition/` rather than `meta/` (§8). */
    public val additions: Set<Uuid> = emptySet(),
) {
    public val isEmpty: Boolean get() = changed.isEmpty() && deleted.isEmpty()

    /** Where a changed shard is fetched from. */
    public fun keyOf(id: Uuid): String = if (id in additions) id.additionKey else id.shardKey
}
