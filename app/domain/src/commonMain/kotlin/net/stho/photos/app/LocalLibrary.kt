package net.stho.photos.app

import kotlin.concurrent.Volatile
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.ShardFailure
import net.stho.photos.catalog.Album
import net.stho.photos.catalog.CatalogWriter
import net.stho.photos.catalog.ObjectId
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.readShard
import net.stho.photos.model.PhotoRow
import net.stho.photos.ports.SqlDrivers

/**
 * The library as the desktop viewer reads it (§11): the CLI's shards and packs, and the originals.
 *
 * Nothing here writes to the CLI's cache. The shards are read where the CLI keeps them, without
 * its lock — each lands by `atomicMove`, so a reader sees one whole version or the other — and the
 * merged database is replayed from them into [mergedPath], which the root puts somewhere that is
 * memory rather than disk. The pixels are the library's own files, found from each shard's
 * `source_path`, which the merged database does not carry.
 */
public class LocalLibrary(
    /** The CLI's cache, `~/.cache/photos-cli` unless it was put elsewhere. */
    cliCache: Path,
    /** The library root the CLI syncs, which every `source_path` is relative to. */
    private val libraryRoot: Path,
    /** Where the merged database is replayed to. */
    public val mergedPath: Path,
    private val drivers: SqlDrivers,
) : Rebuilder, AutoCloseable {

    private val shards = Path(cliCache, "shards")
    private val packs = Path(cliCache, "packs")

    private var writer: CatalogWriter? = null

    /** Each photo's files on disk, read from the shards at the last rebuild. */
    @Volatile
    private var files: Map<Uuid, Files> = emptyMap()

    /** When each photo was taken, from the shards the last rebuild read — for §12's people order. */
    @Volatile
    private var taken: Map<Uuid, Instant> = emptyMap()

    /** A pack in the CLI's `packs/`, named as the CLI names it (§7). */
    public fun packPath(id: ObjectId): Path = Path(packs, "$id.db")

    /**
     * Replays every shard in the CLI's cache into the merged database, and records where each
     * photo's files are. Blocking; the model runs it off the thread that draws.
     *
     * A shard too new to read, or one that will not read at all, is skipped and counted: the rest
     * of the library is still worth showing.
     */
    override fun rebuild(): Rebuilt {
        val found = runCatching { SystemFileSystem.list(shards) }.getOrElse {
            throw MissingCache("no shards in $shards — has photos-cli synced on this machine?")
        }
        val read = mutableListOf<Shard>()
        var skipped = 0
        for (file in found.filter { it.name.endsWith(".db") && it.name.removeSuffix(".db").isUuid() }.sortedBy { it.name }) {
            try {
                read += file.readShard(drivers)
            } catch (unreadable: ShardFailure) {
                skipped++
            }
        }
        val summary = (writer ?: CatalogWriter(mergedPath, drivers).also { writer = it }).rebuild(read)
        files = buildMap {
            for (shard in read) {
                val folder = shard.info.sourcePath ?: continue
                val directory = Path(libraryRoot, *folder.split('/').toTypedArray())
                for (photo in shard.photos) {
                    put(photo.id, Files(Path(directory, photo.diskFilename), photo.liveVideoFilename?.let { Path(directory, it) }))
                }
            }
        }
        taken = buildMap {
            for (shard in read) for (photo in shard.photos) photo.takenAt?.let { put(photo.id, it) }
        }
        return Rebuilt(summary.albums, summary.photos, skipped)
    }

    /**
     * The photo's own file in the library: the original a still is decoded from, or the video
     * itself. Null when it is not on this machine — an addition the CLI has not pulled yet, or a
     * file deleted since the last sync.
     */
    public fun original(photo: PhotoRow): Path? = files[photo.id]?.still?.takeIf(SystemFileSystem::exists)

    /** A Live Photo's MOV beside its still, when both are on disk. */
    public fun liveVideo(photo: PhotoRow): Path? = files[photo.id]?.video?.takeIf(SystemFileSystem::exists)

    public fun takenAt(photo: Uuid): Instant? = taken[photo]

    /** The original a photo id names, for a face crop (§12) — where only the id is at hand. */
    public fun original(photo: Uuid): Path? = files[photo]?.still?.takeIf(SystemFileSystem::exists)

    override fun close() {
        writer?.close()
        writer = null
    }

    private class Files(val still: Path, val video: Path?)

    /** The CLI's cache is not where it was looked for. Said as that, rather than as an empty library. */
    public class MissingCache(message: String) : Exception(message)
}

/** Rebuilding the viewer's catalog: [LocalLibrary], or a test's stand-in. */
public fun interface Rebuilder {
    public fun rebuild(): Rebuilt
}

/** What a rebuild read: the catalog's size, and how many shards it had to leave out. */
public data class Rebuilt(val albums: Int, val photos: Int, val skipped: Int)

/**
 * The CLI's packs, as the viewer's [Thumbnails]. Every pack is either on disk or not coming — the
 * viewer fetches nothing — so nothing is prioritised and nothing arrives.
 */
public class PackDirectory(
    private val library: LocalLibrary,
    private val drivers: SqlDrivers,
) : Thumbnails {
    override fun has(album: Album): Boolean =
        album.packs.isNotEmpty() && album.packs.all { SystemFileSystem.exists(library.packPath(it)) }

    override fun cover(album: Album): ByteArray? = coverThumbnail(album, library.mergedPath, drivers, library::packPath)

    override fun all(album: Album): Map<Uuid, ByteArray> = thumbnailsOf(album, drivers, library::packPath)

    override fun prioritise(album: Album): Unit = Unit

    private val none = MutableStateFlow(0)
    override val arrivals: StateFlow<Int> = none.asStateFlow()
    override val outstanding: StateFlow<Int> = none.asStateFlow()
}

private fun String.isUuid(): Boolean = runCatching { Uuid.parse(this) }.isSuccess
