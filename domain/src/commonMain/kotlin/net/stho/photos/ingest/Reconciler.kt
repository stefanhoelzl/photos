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
    /**
     * Directories claimed by a shard this build cannot read. Left completely alone: not uploaded
     * to, not deleted, not counted as new (§3).
     */
    public val blockedByUnreadable: List<ShardProbe> = emptyList(),
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
        get() = albums.any(AlbumPlan::needsWrite) || deletions.isNotEmpty() || pulls.isNotEmpty()
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
        // Shards a *previous* run of this tool wrote, keyed by the folder they claim.
        // Only albums the laptop owns. A shard still `uploading` or `uploaded` may carry a
        // `source_path` — the pull claims before it downloads — but it is not an ordinary album
        // yet, and treating it as one would read its half-filled directory as deletions (§7).
        val byPath = buildMap {
            for (shard in shards) {
                if (shard.info.state != AlbumState.ENCODED) continue
                val path = shard.info.sourcePath?.normalisedPath()
                if (path.isNullOrEmpty()) continue
                put(path, shard)
            }
        }
        val blockedPaths = unreadable.mapNotNullTo(mutableSetOf()) { it.sourcePath?.normalisedPath() }

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
            val claimedNames = if (reencoding) emptySet() else buildSet {
                for (row in rows) {
                    add(row.filename)
                    add(row.diskFilename)
                }
            }
            val uploads = files.filterNot { it.name in claimedNames }

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
        val reserved = (albumPaths + byPath.keys).toMutableSet()
        val pulls = mutableListOf<PullPlan>()
        for (shard in shards.filter { it.info.state == AlbumState.UPLOADED }
            .sortedBy { it.info.id.toString() }) {
            val claimed = shard.info.sourcePath?.normalisedPath()?.ifEmpty { null }
            val path = claimed ?: availablePath(shard, reserved)
            if (!matchesFilter(path)) continue
            reserved += path
            pulls += PullPlan(shard, path, claimed = claimed != null)
        }

        return IngestPlan(
            albums = albums,
            deletions = deletions,
            pulls = pulls,
            blockedByUnreadable = unreadable,
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
