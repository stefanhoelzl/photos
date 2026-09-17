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
 * A new album the phone finished uploading: the earliest [AlbumState.UPLOADED] addition naming an
 * album no shard is (§8), pulled straight from `addition/`.
 *
 * The pull claims the addition — `source_path` and the names its files will have — downloads its
 * files, derives them into `meta/<adds_to>` and only then deletes the addition. The album's other
 * additions merge into it afterwards, in the same run. An addition that already carries a
 * `source_path` was claimed by a run that did not finish, and [sourcePath] is that same path, so
 * the retry resumes into the directory it half-filled rather than starting a second copy beside it.
 */
public data class PullPlan(
    public val shard: Shard,
    /** Where it will land, relative to `$LIBRARY_ROOT`: under its parent's folder. */
    public val sourcePath: String,
    /** Whether a previous run already claimed it, so this is a resume. */
    public val claimed: Boolean = false,
) {
    /** The album it becomes: the id the phone minted and every one of its additions names. */
    public val albumId: Uuid get() = requireNotNull(shard.info.addsTo)
}

/**
 * A new phone album the pull leaves in the zone because a sibling already has its name, compared
 * ignoring case (§2). It stays shown on every device and is pulled once the other is renamed.
 */
public data class HeldBack(
    /** Where it would land, relative to `$LIBRARY_ROOT`. */
    public val sourcePath: String,
    public val albumId: Uuid,
)

/**
 * Sibling folders whose names differ only in case. Names are unique per parent ignoring case (§2),
 * so none of them is ingested — nor anything beneath them — until all but one are renamed.
 */
public data class NameClash(public val sourcePaths: List<String>)

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
    /** Sibling folders named alike but for case. Left alone, with everything beneath them. */
    public val nameClashes: List<NameClash> = emptyList(),
    /** New phone albums whose name a sibling already has. Left in the zone. */
    public val heldBack: List<HeldBack> = emptyList(),
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
        // Only albums the laptop owns: an addition a pull has claimed carries a `source_path` too,
        // but it is not an album yet, and treating it as one would read its half-filled directory
        // as deletions (§7).
        val claims = shards
            .filter { it.info.state == AlbumState.ENCODED }
            .groupBy { it.info.sourcePath?.normalisedPath().orEmpty() }
            .filterKeys(String::isNotEmpty)
        // Folders a pull claimed and a stopped run left half-filled. They are that pull's, not the
        // walk's: read as an album of their own, the files already downloaded would go up as a
        // second album beside the one the pull finishes into the same folder (§7).
        // An addition claimed by a merge names its album's folder instead, and that album is encoded.
        val encodedIds = shards.filter { it.info.state == AlbumState.ENCODED }.mapTo(mutableSetOf()) { it.info.id }
        val resuming = additions
            .filter { it.info.state == AlbumState.UPLOADED && it.info.addsTo !in encodedIds }
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
        // Names are unique per parent, ignoring case (§2), and a library on a case-sensitive disk
        // can still hold `Spain` beside `spain`. Neither is the album: picking one would be a guess,
        // so both are left alone with everything beneath them — their children would otherwise
        // lose their parent and surface at the root — until a person renames one.
        val nameClashes = albumPaths
            .groupBy { it.parentPath() to it.lastComponent().lowercase() }
            .values.filter { it.size > 1 }
            .map { NameClash(it.sorted()) }
            .sortedBy { it.sourcePaths.first() }
        val clashing = nameClashes.flatMap(NameClash::sourcePaths)
        val underClash = albumPaths.filter { path -> clashing.any { path == it || path.startsWith("$it/") } }
        blockedPaths += underClash
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

        // A new album is photos the phone added to an album no shard is yet (§8): every addition
        // naming it carries the id the phone minted. `uploaded` means the phone has finished and
        // nothing has encoded it yet; `uploading` is skipped, still in flight. An addition whose album
        // was deleted — by an earlier run, or by this very run, which deletes before it pulls — reads
        // the same way, and is pulled as the album it records being added to.
        //
        // Each such album is pulled once, from its earliest addition, or from the one a stopped run
        // already claimed, which resumes into the folder it half-filled. The rest merge into it
        // afterwards, like additions to any album.
        val deleted = deletions.mapTo(mutableSetOf()) { it.shard.info.id }
        val deletedPaths = deletions.map(AlbumDeletion::sourcePath)
        // An album this build cannot read is not an album that is gone: pulling its additions would
        // make a second album of photos that belong in it (§3's unreadable-not-absent rule).
        val unreadableIds = unreadable.mapTo(mutableSetOf(), ShardProbe::albumId)
        val albumsById = shards.associateBy { it.info.id }
        val landed = additions.filter { it.info.state == AlbumState.UPLOADED }
            .sortedWith(compareBy({ it.info.addedAt }, { it.info.id.toString() }))
        val absent = landed.filter { addition ->
            val target = requireNotNull(addition.info.addsTo)
            target !in unreadableIds && albumsById[target]?.takeIf { it.info.id !in deleted } == null
        }

        // Every folder already spoken for, including a deleted album's: a pull never puts a deleted
        // folder back in the run that deleted it.
        val reserved = (albumPaths + byPath.keys + resuming.keys + blockedPaths).toMutableSet()
        val pulls = mutableListOf<PullPlan>()
        val heldBack = mutableListOf<HeldBack>()
        val pulledPaths = mutableMapOf<Uuid, String>()
        for ((albumId, group) in absent.groupBy { requireNotNull(it.info.addsTo) }.entries.sortedBy { it.key.toString() }) {
            val resume = group.firstNotNullOfOrNull { addition ->
                addition.info.sourcePath?.normalisedPath()?.takeIf(resuming::containsKey)?.let { addition to it }
            }
            if (resume != null) {
                val (addition, path) = resume
                if (path in blockedPaths || !matchesFilter(path)) continue
                pulls += PullPlan(addition, path, claimed = true)
                pulledPaths[albumId] = path
                continue
            }
            val first = group.first()
            val path = destination(first, albumsById, deleted, unreadableIds) ?: continue
            if (path.ancestors().any(blockedPaths::contains) || !matchesFilter(path)) continue
            // Names are unique per parent, ignoring case (§2). One taken since the phone chose it —
            // by another device, or a folder made on the laptop — is not renamed: the album waits,
            // shown everywhere and named in the report, until a person renames one of the two.
            if (clashes(path, reserved)) {
                // Its album's own folder, deleted this run, is not a name someone else has: it waits
                // for the next run quietly, since a pull never puts back a folder the run deleted.
                if (deletedPaths.none { it.equals(path, ignoreCase = true) }) heldBack += HeldBack(path, albumId)
                continue
            }
            reserved += path
            pulls += PullPlan(first, path)
            pulledPaths[albumId] = path
        }

        // An addition merges once its album is there to take it: `encoded`, or pulled earlier in
        // this same run. One still `uploading` waits, and past the sweep's floor it is abandoned like
        // any other upload.
        val pulling = pulls.mapTo(mutableSetOf()) { it.shard.info.id }
        val merges = mutableListOf<MergePlan>()
        for (addition in landed) {
            if (addition.info.id in pulling) continue
            val targetId = requireNotNull(addition.info.addsTo)
            if (targetId in unreadableIds) continue
            val target = albumsById[targetId]?.takeIf { it.info.id !in deleted }
            val path = when (target?.info?.state) {
                AlbumState.ENCODED -> target.info.sourcePath?.normalisedPath()?.ifEmpty { null }
                // A new album: pulled earlier in this run, or held back, or left for a later run.
                null -> pulledPaths[targetId]
                // An album an older app wrote under `meta/` itself, which this build does not pull.
                else -> null
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
            nameClashes = nameClashes,
            heldBack = heldBack,
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
     * Where a new phone album lands: under its parent's folder, named as the album is. Null while
     * that parent is a shard this build cannot read — it is not gone, so landing elsewhere would put
     * the album in the wrong place for good. A parent that *is* gone surfaces the album at the root,
     * as every reader shows it (§2).
     */
    private fun destination(
        addition: Shard,
        albumsById: Map<Uuid, Shard>,
        deleted: Set<Uuid>,
        unreadableIds: Set<Uuid>,
    ): String? {
        val name = addition.info.name.replace("/", "-").normalisedPath()
            .ifEmpty { requireNotNull(addition.info.addsTo).toString() }
        val parentId = addition.info.parent ?: return name
        if (parentId in unreadableIds) return null
        val parent = albumsById[parentId]
            ?.takeIf { it.info.id !in deleted && it.info.state == AlbumState.ENCODED }
            ?: return name
        val parentPath = parent.info.sourcePath?.normalisedPath()?.ifEmpty { null } ?: return name
        return "$parentPath/$name"
    }

    /** Whether a sibling of [path] — a folder on disk, or one this run plans — has its name, ignoring case. */
    private fun clashes(path: String, taken: Set<String>): Boolean {
        val parent = path.parentPath()
        val name = path.lastComponent().lowercase()
        if (taken.any { it.parentPath() == parent && it.lastComponent().lowercase() == name }) return true
        val siblings = runCatching { SystemFileSystem.list(pathOf(parent)) }.getOrDefault(emptyList())
        return siblings.any { it.name.lowercase() == name }
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

/** `a/b/c` → `a/b`; the root's children → the empty string. */
private fun String.parentPath(): String = substringBeforeLast('/', "")
