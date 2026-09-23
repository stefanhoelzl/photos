package net.stho.photos.ingest

import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.AlbumState
import net.stho.photos.catalog.ObjectId
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.blobKey
import net.stho.photos.storage.S3Client

/**
 * Every thumbnail pack the shards on disk name, kept in `packs/` beside `shards/` (§7).
 *
 * For the desktop viewer (§11), which reads what this cache holds and fetches nothing itself.
 * The directory tracks the zone the way `shards/` does: derived, deletable, and repaired by the
 * next run — a pack a shard names that is not here is fetched, and a pack here that no shard
 * names is removed. So the first run after packs started being kept backfills the library once,
 * and from then on a run fetches only what it did not write itself.
 *
 * Named `<id>.db`, as the phone's own cache names them, and landed by `atomicMove` from a
 * `.part` beside it: the viewer reads this directory without the run lock, and must only ever
 * see a whole pack or none.
 */
public class LocalPacks(cacheRoot: Path, private val s3: S3Client) {

    public val directory: Path = Path(cacheRoot, DIRECTORY)

    public fun path(id: ObjectId): Path = Path(directory, "$id.db")

    /**
     * Moves a pack this run has just built and uploaded into place, rather than deleting it and
     * fetching it back at the end of the run.
     */
    public fun adopt(id: ObjectId, packed: Path) {
        SystemFileSystem.createDirectories(directory)
        SystemFileSystem.atomicMove(packed, path(id))
    }

    /**
     * Brings the directory in line with [shards]: fetches what they name and is missing, and
     * removes what none of them names.
     *
     * An album still uploading is not counted either way — its pack may not be in the zone yet
     * (§8 writes the shard first), and no reader shows it. With [unreadable] shards on disk
     * nothing is removed, for the sweep's reason: what those albums own is unknowable, and a
     * pack of theirs would read as unreferenced.
     *
     * A pack that will not download is a failure of this run and is retried by the next. The
     * run goes on either way: nothing in the zone depends on this directory.
     */
    public suspend fun reconcile(shards: List<Shard>, unreadable: Int): Reconciled {
        SystemFileSystem.createDirectories(directory)
        val named = shards
            .filter { it.info.state != AlbumState.UPLOADING }
            .mapNotNull { shard -> shard.info.thumbsId?.let { it to shard.info.name } }
            .toMap()

        var fetched = 0
        val failures = mutableListOf<IngestReport.Failure>()
        for ((id, album) in named.entries.sortedBy { it.key.toString() }) {
            val target = path(id)
            if (SystemFileSystem.exists(target)) continue
            val partial = Path(directory, "$id.${Uuid.random()}.part")
            try {
                s3.download(id.blobKey, partial)
                SystemFileSystem.atomicMove(partial, target)
                fetched++
            } catch (cancelled: CancellationException) {
                partial.deleteQuietly()
                throw cancelled
            } catch (failure: Exception) {
                partial.deleteQuietly()
                failures += IngestReport.Failure(album, "thumbnail pack $id: ${failure.message ?: failure}")
            }
        }

        var removed = 0
        val files = runCatching { SystemFileSystem.list(directory) }.getOrDefault(emptyList())
        for (file in files) {
            // A `.part` is always debris: the run lock means no other run is writing one.
            if (file.name.endsWith(".part")) {
                file.deleteQuietly()
                continue
            }
            if (unreadable > 0) continue
            val id = file.name.removeSuffix(".db").let(ObjectId::parse)
            if (id == null || id !in named) {
                file.deleteQuietly()
                removed++
            }
        }
        return Reconciled(fetched, removed, failures)
    }

    /** What one reconcile did. */
    public class Reconciled(
        public val fetched: Int,
        public val removed: Int,
        public val failures: List<IngestReport.Failure>,
    )

    public companion object {
        public const val DIRECTORY: String = "packs"
    }
}

private fun Path.deleteQuietly() {
    runCatching { SystemFileSystem.delete(this, mustExist = false) }
}
