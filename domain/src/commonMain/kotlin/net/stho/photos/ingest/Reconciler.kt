package net.stho.photos.ingest

import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.AlbumState
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.ShardProbe
import net.stho.photos.library.LibraryContents
import net.stho.photos.model.PhotoRow
import net.stho.photos.ports.Ids

/** One album as the run intends to leave it. */
public data class AlbumPlan(
    public val id: Uuid,
    public val name: String,
    /** Relative to `$LIBRARY_ROOT`. Never empty: loose files at the root are not an album. */
    public val sourcePath: String,
    public val parent: Uuid?,
    public val directory: Path,
    /** The shard as the zone currently holds it, or null for an album about to be minted. */
    public val existing: Shard?,
    /** Files with no row yet. Comes from the walk, so `.photosignore` applies (§7). */
    public val uploads: List<Path>,
    /**
     * Every file the walk offered for this album, claimed or not.
     *
     * Classification needs the whole set, not just the new files. A Live Photo is a HEIC *and* a
     * MOV that pairs with it, and the MOV has no row of its own — so classifying only the
     * unclaimed files would hand the classifier a lone MOV, which is an ordinary video, and every
     * Live Photo's MOV would be transcoded and uploaded a second time.
     */
    public val files: List<Path>,
    /** Rows whose file is still on disk. */
    public val keep: List<PhotoRow>,
    /**
     * Rows whose file is gone. Comes from `stat`, so `.photosignore` does *not* apply —
     * broadening a rule must never look like a deletion.
     */
    public val drop: List<PhotoRow>,
    /**
     * A directory holding both files and sub-albums. §2 says an album has sub-albums XOR photos,
     * so the loose files are not ingested and the children go up normally.
     */
    public val mixedFileCount: Int,
    /**
     * Whether this album's images are below the profile this build writes, so every row is
     * re-derived from the file on disk rather than only the unclaimed ones.
     *
     * Expressed through the ordinary machinery: [uploads] becomes every file and [drop] every
     * existing row, so the album is rebuilt and its old blobs deleted by the paths that already
     * do those things. The flag exists so the report can say "re-encoded" rather than reporting
     * a full album as deleted and re-added.
     */
    public val reencoding: Boolean = false,
    /**
     * Rows whose identity the derived rows take over, matched by the file each came from: the
     * phone's rows for the photos an addition brings in (§7). `photo.id` is what a cover points at
     * and what a pack keys by, so a photograph merged into an album stays the same photograph.
     */
    public val carried: List<PhotoRow> = emptyList(),
) {
    public val isNew: Boolean get() = existing == null

    /**
     * Whether the shard has to be rewritten at all. A rename or a re-parent counts: both are
     * metadata writes that move nothing (§2).
     */
    public val needsWrite: Boolean
        get() {
            val info = existing?.info ?: return true
            return reencoding ||
                uploads.isNotEmpty() ||
                drop.isNotEmpty() ||
                info.name != name ||
                info.parent != parent ||
                info.sourcePath != sourcePath
        }
}

/** A shard whose directory is gone. `rm -rf` is the only gesture that means this. */
public data class AlbumDeletion(
    public val shard: Shard,
    public val sourcePath: String,
)

/**
 * A shard the phone made and finished uploading: [AlbumState.UPLOADED].
 *
 * The pull claims it, copies it into the library, re-encodes it and only then writes
 * [AlbumState.ENCODED]. A shard that already carries a `source_path` was claimed by a run that
 * did not finish, and [sourcePath] is that same path, so the retry resumes into the directory
 * it half-filled rather than starting a second copy beside it (§7).
 */
public data class PullPlan(
    public val shard: Shard,
    /** Where it will land, relative to `$LIBRARY_ROOT`. */
    public val sourcePath: String,
    /** Whether a previous run already wrote `source_path`, so this is a resume. */
    public val claimed: Boolean = false,
    /**
     * An addition whose album is gone (§8), still at `addition/`. The pull first makes it the album
     * it reads as — `meta/` under the same id, then the `addition/` key goes — and then pulls that.
     */
    public val adopting: Boolean = false,
)

/**
 * An addition the phone finished uploading, and the album it goes into (§7).
 *
 * The merge claims it — `source_path` and the names its files will have on disk, fixed against the
 * album's folder — downloads the files into that folder, derives only them into the album, and
 * deletes the addition. [target] is the album as this run planned it: one still `uploaded` is
 * pulled earlier in the same run, and the merge reads it back from the cache once that has landed.
 */
public data class MergePlan(
    public val addition: Shard,
    public val target: Uuid,
    /** The album's folder, relative to `$LIBRARY_ROOT`. */
    public val sourcePath: String,
) {
    /** Whether a previous run already claimed it, so its names are fixed and this is a resume. */
    public val claimed: Boolean get() = addition.info.sourcePath != null
}

/**
 * A directory more than one album claims — encoded, or pulled by a run that stopped — a duplicate
 * left for a person to remove.
 */
public data class DoubleClaim(
    public val sourcePath: String,
    public val albumIds: List<Uuid>,
)

/** A file whose size disagrees with the row describing it. */
public data class ByteMismatch(
    public val albumPath: String,
    public val filename: String,
    public val recorded: Long,
    public val found: Long,
) {
    /** The wording the abort message quotes, so it is composed in exactly one place. */
    override fun toString(): String =
        "$albumPath/$filename: catalog says $recorded bytes, disk has $found"
}

/** What one run intends to do. */
public data class IngestPlan(
    public val albums: List<AlbumPlan> = emptyList(),
    public val deletions: List<AlbumDeletion> = emptyList(),
    public val pulls: List<PullPlan> = emptyList(),
    public val merges: List<MergePlan> = emptyList(),
    /**
     * Directories claimed by a shard this build cannot read. Left completely alone: not uploaded
     * to, not deleted, not counted as new (§3).
     */
    public val blockedByUnreadable: List<ShardProbe> = emptyList(),
    /** Directories two or more albums claim. Left alone exactly like [blockedByUnreadable]. */
    public val doublyClaimed: List<DoubleClaim> = emptyList(),
    /**
     * Loose files directly in `$LIBRARY_ROOT`. The library is a directory of albums, so these are
     * reported and never ingested.
     */
    public val looseRootFiles: List<Path> = emptyList(),
    /** Abort conditions, collected so the report can name all of them at once. */
    public val mismatches: List<ByteMismatch> = emptyList(),
) {
    public val uploadCount: Int get() = albums.sumOf { it.uploads.size }

    public val dropCount: Int get() = albums.sumOf { it.drop.size }

    public val hasWork: Boolean
        get() = albums.any(AlbumPlan::needsWrite) || deletions.isNotEmpty() || pulls.isNotEmpty() ||
            merges.isNotEmpty()
}

/**
 * Works out what the zone should look like, given a library and the shards it already has.
 *
 * Pure apart from `stat`: no network, no imaging, no encoding. That is deliberate — this is where
 * every rule that can lose data lives, so it has to be reachable from a test with a temporary
 * directory and nothing else.
 */
public class Reconciler(
    public val root: Path,
    /**
     * Album identity is minted here, before anything is planned. A port rather than
     * `Uuid.random()` because a run whose ids are unpredictable cannot be asserted against (§7).
     */
    private val ids: Ids,
    public val albumFilter: String? = null,
) {

    public fun plan(
        contents: LibraryContents,
        shards: List<Shard>,
        unreadable: List<ShardProbe> = emptyList(),
    ): IngestPlan {
        // Additions are not albums: they are planned as merges at the end, and until then only
        // their claimed names matter, which keep their files from reading as new uploads.
        val additions = shards.filter { it.info.isAddition }
        val shards = shards.filterNot { it.info.isAddition }
        // Files an unfinished merge already put in its album's folder (§7). They belong to the
        // addition that claimed them, which the merge brings in with the phone's row identities, so
        // the walk must not upload them first as photos of its own.
        val mergingNames = additions
            .filter { it.info.state == AlbumState.UPLOADED && it.info.sourcePath != null }
            .groupBy { requireNotNull(it.info.addsTo) }
            .mapValues { (_, pending) -> pending.flatMap { it.photos }.flatMapTo(mutableSetOf()) { it.claimedFilenames } }

        // Shards a *previous* run of this tool wrote, keyed by the folder they claim.
        // Only albums the laptop owns. A shard still `uploading` or `uploaded` may carry a
        // `source_path` — the pull claims before it downloads — but it is not an ordinary album
        // yet, and treating it as one would read its half-filled directory as deletions (§7).
        val claims = shards
            .filter { it.info.state == AlbumState.ENCODED }
            .groupBy { it.info.sourcePath?.normalisedPath().orEmpty() }
            .filterKeys(String::isNotEmpty)
        // Folders a pull claimed and a stopped run left half-filled. They are that pull's, not the
        // walk's: read as an album of their own, the files already downloaded would go up as a
        // second album beside the one the pull finishes into the same folder (§7).
        val resuming = shards
            .filter { it.info.state == AlbumState.UPLOADED }
            .groupBy { it.info.sourcePath?.normalisedPath().orEmpty() }
            .filterKeys(String::isNotEmpty)
        // One folder, one album. Two claiming it is a duplicate this tool made — an interrupted pull
        // walked as a new album did it before the walk left such folders alone. Picking either would
        // upload into it and leave the other to linger unseen, so the folder is left alone, like one
        // a too-new shard claims, and neither pulled nor uploaded until a person removes one.
        val doublyClaimed = (claims.keys + resuming.keys)
            .map { path -> path to claims[path].orEmpty() + resuming[path].orEmpty() }
            .filter { (_, claimants) -> claimants.size > 1 }
            .sortedBy { (path, _) -> path }
            .map { (path, claimants) -> DoubleClaim(path, claimants.map { it.info.id }.sortedBy(Uuid::toString)) }
        val byPath = claims.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val blockedPaths = unreadable.mapNotNullTo(mutableSetOf()) { it.sourcePath?.normalisedPath() }
        blockedPaths += doublyClaimed.map(DoubleClaim::sourcePath)

        // Directories the walk found media in. The root itself is not an album: a stray file
        // beside the album folders must not re-parent all 240 of them under a new root album, so
        // it is reported instead.
        var looseRootFiles: List<Path> = emptyList()
        val filesByPath = mutableMapOf<String, List<Path>>()
        for (album in contents.albums) {
            val path = album.relativePath.normalisedPath()
            if (path.isEmpty()) looseRootFiles = album.files else filesByPath[path] = album.files
        }

        // Every directory that has to exist as an album: one that holds media, every ancestor of
        // one (§10's containers — the walker emits nothing for `Kalifornien`, which holds 17
        // children and no photos of its own), and any directory a shard still claims, so an album
        // emptied of files survives with zero photos.
        val albumPaths = filesByPath.keys.toMutableSet()
        for (path in filesByPath.keys) albumPaths += path.ancestors()
        for (path in byPath.keys) {
            if (!directoryExists(path)) continue
            albumPaths += path
            albumPaths += path.ancestors().filter(::directoryExists)
        }
        albumPaths -= blockedPaths
        albumPaths -= resuming.keys

        // Identity is assigned before anything is planned, so a child can name its parent whether
        // or not that parent already exists in the zone.
        val sorted = albumPaths.sorted()
        val idByPath = sorted.associateWith { byPath[it]?.info?.id ?: ids.next() }

        val albums = mutableListOf<AlbumPlan>()
        val mismatches = mutableListOf<ByteMismatch>()

        for (path in sorted) {
            if (!matchesFilter(path)) continue
            val directory = pathOf(path)
            val existing = byPath[path]
            val hasChildren = sorted.any { it != path && it.startsWith("$path/") }
            val ownFiles = filesByPath[path].orEmpty()
            // §2: sub-albums XOR photos. The children are unambiguous and go up; the loose files
            // do not, and are named in the report.
            val files = if (hasChildren) emptyList() else ownFiles

            // Below the current profile: every row is re-derived from the file on disk, so
            // nothing counts as already claimed and every file is an upload.
            val reencoding = existing?.needsReencode == true
            val rows = existing?.photos.orEmpty()
            // Every file the existing rows account for — which is more than one file per row: a
            // Live Photo is a HEIC and a MOV and a single row, so the MOV is named by
            // `liveVideoFilename` or by nothing at all (§3).
            val claimedNames = if (reencoding) emptySet() else buildSet {
                for (row in rows) addAll(row.claimedFilenames)
            }
            val merging = existing?.let { mergingNames[it.info.id] }.orEmpty()
            val uploads = files.filterNot { it.name in claimedNames || it.name in merging }

            val keep = mutableListOf<PhotoRow>()
            val drop = mutableListOf<PhotoRow>()
            for (row in rows) {
                // Existence is a `stat`, never the walk: `.photosignore` says what may be
                // uploaded and nothing else, so broadening a rule can only ever stop an upload —
                // it can never make a photo look deleted (§7).
                val source = Path(directory, row.diskFilename)
                val zone = Path(directory, row.filename)
                val sourceThere = SystemFileSystem.exists(source)
                if (!sourceThere && !SystemFileSystem.exists(zone)) {
                    drop += row
                    continue
                }
                keep += row
                // `sourceBytes` describes the file rather than the upload, so unlike its
                // predecessor this holds for every row — video and carved RAW included. The
                // guard is only for rows an older writer left without one (§7).
                if (!sourceThere || !row.byteCountIsCheckable) continue
                val recorded = row.sourceBytes ?: continue
                val found = SystemFileSystem.metadataOrNull(source)?.size ?: continue
                if (found != recorded) {
                    mismatches += ByteMismatch(path, row.diskFilename, recorded, found)
                }
            }

            // The old rows and the blobs they own go, and the re-derived ones replace them.
            // Deleting them is what the ordinary `drop` path already does, so re-encoding needs
            // no delete path of its own.
            if (reencoding) {
                drop += keep
                keep.clear()
            }

            albums += AlbumPlan(
                id = idByPath.getValue(path),
                name = path.lastComponent(),
                sourcePath = path,
                parent = path.ancestors().firstNotNullOfOrNull(idByPath::get),
                directory = directory,
                existing = existing,
                uploads = uploads,
                files = files,
                keep = keep,
                drop = drop,
                mixedFileCount = if (hasChildren) ownFiles.size else 0,
                reencoding = reencoding,
            )
        }

        // A shard claiming a directory that is gone. Nothing else means this: an album emptied of
        // files still has its directory, and so still has an album.
        val deletions = byPath.entries.sortedBy { it.key }
            .filter { (path, _) ->
                path !in albumPaths && path !in blockedPaths &&
                    matchesFilter(path) && !directoryExists(path)
            }
            .map { (path, shard) -> AlbumDeletion(shard, path) }

        // `uploaded` means the phone has finished and nothing has encoded it yet — the only
        // state the CLI pulls from. `uploading` is skipped: it is still in flight. A shard that
        // already names a path was claimed by a run that did not finish, so it resumes there.
        val reserved = (albumPaths + byPath.keys + resuming.keys + blockedPaths).toMutableSet()
        val pulls = mutableListOf<PullPlan>()
        for (shard in shards.filter { it.info.state == AlbumState.UPLOADED }
            .sortedBy { it.info.id.toString() }) {
            val claimed = shard.info.sourcePath?.normalisedPath()?.ifEmpty { null }
            if (claimed != null && claimed in blockedPaths) continue
            val path = claimed ?: availablePath(shard, reserved)
            if (!matchesFilter(path)) continue
            reserved += path
            pulls += PullPlan(shard, path, claimed = claimed != null)
        }

        // An addition merges once its album is there to take it: `encoded`, or `uploaded` and pulled
        // earlier in this same run. One whose album is gone — or is being deleted by this very run —
        // is adopted instead, and pulled as the album it records being added to (§8). One still
        // `uploading` waits, and past the sweep's floor it is abandoned like any other upload.
        val deleted = deletions.mapTo(mutableSetOf()) { it.shard.info.id }
        // An album this build cannot read is not an album that is gone: adopting its additions would
        // make a second album of photos that belong in it (§3's unreadable-not-absent rule).
        val unreadableIds = unreadable.mapTo(mutableSetOf(), ShardProbe::albumId)
        val albumsById = shards.associateBy { it.info.id }
        val pulledPaths = pulls.associate { it.shard.info.id to it.sourcePath }
        val merges = mutableListOf<MergePlan>()
        for (addition in additions.filter { it.info.state == AlbumState.UPLOADED }
            .sortedWith(compareBy({ it.info.addedAt }, { it.info.id.toString() }))) {
            val targetId = requireNotNull(addition.info.addsTo)
            if (targetId in unreadableIds) continue
            val target = albumsById[targetId]?.takeIf { it.info.id !in deleted }
            val path = when (target?.info?.state) {
                AlbumState.ENCODED -> target.info.sourcePath?.normalisedPath()?.ifEmpty { null }
                AlbumState.UPLOADED -> pulledPaths[targetId]
                // Still uploading: an album cannot be chosen before it is shown, so this is the
                // phone being ahead of the zone. It waits.
                AlbumState.UPLOADING -> continue
                null -> {
                    val adopted = availablePath(addition, reserved)
                    if (!matchesFilter(adopted)) continue
                    reserved += adopted
                    pulls += PullPlan(addition, adopted, claimed = false, adopting = true)
                    continue
                }
            }
            // A target left alone this run — too new to read beside it, claimed twice, filtered out,
            // or a pull that never planned — is merged into by a later run instead.
            if (path == null || path in blockedPaths || !matchesFilter(path)) continue
            merges += MergePlan(addition, targetId, path)
        }

        return IngestPlan(
            albums = albums,
            deletions = deletions,
            pulls = pulls,
            merges = merges,
            blockedByUnreadable = unreadable,
            doublyClaimed = doublyClaimed,
            looseRootFiles = looseRootFiles,
            mismatches = mismatches,
        )
    }

    // ------------------------------------------------------------------------------------ paths

    private fun pathOf(path: String): Path =
        if (path.isEmpty()) root else Path(root, *path.split('/').toTypedArray())

    private fun directoryExists(path: String): Boolean =
        SystemFileSystem.metadataOrNull(pathOf(path))?.isDirectory == true

    private fun matchesFilter(path: String): Boolean =
        albumFilter.isNullOrEmpty() || path.contains(albumFilter, ignoreCase = true)

    /**
     * Where a phone-owned album lands. Its name is not guaranteed unique (§2 permits duplicates),
     * so a taken directory gets the album id appended rather than merged into.
     */
    private fun availablePath(shard: Shard, taken: Set<String>): String {
        val base = shard.info.name.replace("/", "-").normalisedPath()
        val candidate = base.ifEmpty { shard.info.id.toString() }
        if (candidate !in taken && !directoryExists(candidate)) return candidate
        return "$candidate (${shard.info.id.toString().take(8)})"
    }
}

/**
 * A `source_path` as this build compares them: no leading or trailing separator.
 *
 * Unicode composition is deliberately **not** folded here: §2 measured that none of the
 * library's 51 non-ASCII names is decomposed, so paths are compared exactly as the filesystem
 * and the catalog spell them.
 */
private fun String.normalisedPath(): String = trim('/')

/** `a/b/c` → `a/b`, `a`. Never the empty string: the root is not an album. */
private fun String.ancestors(): List<String> {
    val components = split('/').filter(String::isNotEmpty)
    return (components.size - 1 downTo 1).map { components.take(it).joinToString("/") }
}

private fun String.lastComponent(): String = substringAfterLast('/')
