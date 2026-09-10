package net.stho.photos.desktop

import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.ObjectId
import net.stho.photos.catalog.blobKey
import net.stho.photos.storage.S3Client
import net.stho.photos.ui.state.BlobStore

/**
 * The queue's platform half: bytes to disk, and what is already there.
 *
 * Everything the scheduler decides — the ladder, the worker roles, the retry policy — lives
 * above this line in `:ui/state`, where a test can reach it. What is left here is genuinely
 * platform: an HTTP GET, a rename, and a directory listing.
 *
 * §4's layout keeps two directories, and the split is policy rather than tidiness: a pack is
 * always kept so that every grid opens instantly and offline, while `blobs/` is what a clear
 * empties. A pack is recognised by having been *written* as one — [packs] is consulted first on
 * every lookup, so an id that arrived as a pack is never later mistaken for media.
 */
public class FileBlobStore(
    private val s3: S3Client,
    cacheRoot: Path,
) : BlobStore {

    private val media = Path(cacheRoot, "blobs")
    private val packs = Path(cacheRoot, "packs")

    /**
     * Ids the caller has declared to be packs.
     *
     * The queue knows which is which from the catalog — a `thumbs_id` is a pack, a photo's
     * blobs are media — and tells this store before it fetches, because a bare `ObjectId` is a
     * hash and says nothing about what it addresses.
     */
    private val knownPacks = mutableSetOf<ObjectId>()

    init {
        SystemFileSystem.createDirectories(media)
        SystemFileSystem.createDirectories(packs)
    }

    /** Declare an id to be a pack, so it lands beside the other packs and survives a clear. */
    public fun expectPack(id: ObjectId) {
        knownPacks += id
    }

    /** Where a pack ends up, for the reader that opens it as a SQLite file. */
    public fun packPath(id: ObjectId): Path = Path(packs, "$id.db")

    override fun has(id: ObjectId): Boolean = SystemFileSystem.exists(pathFor(id))

    override suspend fun fetch(id: ObjectId) {
        val target = pathFor(id)
        if (SystemFileSystem.exists(target)) return
        // A scratch name per attempt, so two fetches of one blob can never rename each other's
        // file out from under themselves.
        val partial = Path(target.parent!!, "${target.name}.${Uuid.random()}.part")
        try {
            s3.download(id.blobKey, to = partial)
            SystemFileSystem.atomicMove(partial, target)
        } catch (failure: Throwable) {
            // Cancellation included: an abandoned fetch must not leave a partial file behind for
            // the next `present()` to count as held. The queue decides what a failure means.
            SystemFileSystem.delete(partial, mustExist = false)
            throw failure
        }
    }

    override fun delete(id: ObjectId) {
        SystemFileSystem.delete(Path(media, id.toString()), mustExist = false)
    }

    /**
     * Every blob on disk, as one directory read per directory.
     *
     * Measured at ~12.5 ms for 34,607 names against ~119 ms to `stat` them all, which is why
     * this answers presence only and every size comes from the catalog. `.part` files are
     * skipped: a fetch in flight is not something the rows should count as held.
     */
    override fun present(): Set<ObjectId> = buildSet {
        for (directory in listOf(media, packs)) {
            for (entry in SystemFileSystem.list(directory)) {
                val name = entry.name
                if (name.endsWith(".part")) continue
                ObjectId.parse(name.removeSuffix(".db"))?.let(::add)
            }
        }
    }

    private fun pathFor(id: ObjectId): Path =
        if (id in knownPacks) packPath(id) else Path(media, id.toString())
}
