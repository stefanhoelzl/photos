@file:OptIn(ExperimentalUuidApi::class)

package net.stho.photos.e2e

import net.stho.photos.adapter.linux.NativeSqlDrivers
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.toList
import kotlinx.io.files.Path
import net.stho.photos.catalog.ADDITION_PREFIX
import net.stho.photos.catalog.AlbumInfo
import net.stho.photos.catalog.AlbumState
import net.stho.photos.catalog.BLOB_PREFIX
import net.stho.photos.catalog.META_PREFIX
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.ObjectId
import net.stho.photos.catalog.asBlobObjectId
import net.stho.photos.catalog.blobKey
import net.stho.photos.catalog.packThumbnails
import net.stho.photos.catalog.readShard
import net.stho.photos.catalog.shardKey
import net.stho.photos.catalog.writeTo
import net.stho.photos.derivative.DerivativeSpec
import net.stho.photos.fixtures.syntheticHeic
import net.stho.photos.fixtures.syntheticJpeg
import net.stho.photos.fixtures.write
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.storage.Body
import net.stho.photos.storage.GetResult
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.S3Object
import net.stho.photos.storage.list

/** The zone as it actually is, read back through the same client the CLI writes with. */
internal data class ZoneState(
    val albums: List<Shard>,
    /** Every shard under `addition/`: photos the phone added that no run has merged yet (§8). */
    val additions: List<Shard>,
    /** Every key under `blob/`, by id, with its size. */
    val blobs: Map<ObjectId, Long>,
) {
    /** Albums by their `source_path` where they have one, else by name. */
    val byPath: Map<String, Shard>
        get() = albums.associateBy { it.info.sourcePath ?: it.info.name }
}

/** Everything in the zone, gone. Each scenario starts from a bucket it fully owns. */
internal suspend fun S3Client.clearZone() {
    for (listed in list().toList()) {
        if (!listed.isDirectoryMarker) delete(listed.key)
    }
}

/** Reads the zone: every shard, opened, plus the blob keys they should account for. */
internal suspend fun S3Client.readZone(scratch: Path): ZoneState {
    suspend fun shards(prefix: String): List<Shard> = list(prefix = prefix).toList()
        .filterNot(S3Object::isDirectoryMarker)
        .map { listed ->
            val bytes = (get(listed.key) as GetResult.Content).bytes
            Path(scratch, "read-${Uuid.random()}.db").write(bytes).readShard(NativeSqlDrivers())
        }
    val shards = shards(META_PREFIX)
    val additions = shards(ADDITION_PREFIX)
    val blobs = list(prefix = BLOB_PREFIX).toList()
        .filterNot(S3Object::isDirectoryMarker)
        .mapNotNull { listed -> listed.key.asBlobObjectId()?.let { it to listed.size } }
        .toMap()
    return ZoneState(shards, additions, blobs)
}

/**
 * Writes an album into the zone directly, without a CLI run.
 *
 * The only way to reach a state no run produces -- above all an album with no `source_path`,
 * which is what an album the phone made looks like, and what §7's archive-only pull acts on.
 */
internal class GivenAlbum(private val name: String) : Given {
    /** Null means unclaimed: no local directory has been connected to this album yet. */
    var sourcePath: String? = null

    /**
     * Defaults to what the path implies: an album with no `source_path` is one the phone made
     * and finished uploading, so it is `uploaded` at encoding version 0 — the only shape the
     * CLI pulls from. One that names a path is already the laptop's.
     */
    var state: AlbumState? = null

    val id: Uuid = Uuid.random()
    private val photos = GivenPhotos()

    fun photo(filename: String, width: Int = 160, height: Int = 120) {
        photos.add(filename, width, height)
    }

    private val resolvedState: AlbumState
        get() = state ?: if (sourcePath == null) AlbumState.UPLOADED else AlbumState.ENCODED

    override suspend fun materialise(s3: S3Client, scratch: Path) {
        val (rows, thumbsId) = photos.upload(s3, scratch)
        val shard = Shard(
            info = AlbumInfo(
                id = id,
                name = name,
                sourcePath = sourcePath,
                thumbsId = thumbsId,
                state = resolvedState,
                encodingVersion = if (resolvedState == AlbumState.ENCODED) {
                    DerivativeSpec.ENCODING_VERSION
                } else {
                    0
                },
                addedAt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds),
            ),
            photos = rows,
        )
        val local = Path(scratch, "given-$id.db")
        shard.writeTo(local, NativeSqlDrivers())
        s3.put(id.shardKey, Body.File(local))
    }
}

/** Something a scenario puts in the zone directly. */
internal interface Given {
    suspend fun materialise(s3: S3Client, scratch: Path)
}

/**
 * Photos the phone added to an album and finished uploading (§8): an `uploaded` addition at
 * `addition/<id>.db`, under the camera's names — the phone leaves clashes to the laptop.
 *
 * The album is named by [into], the folder a run already made it from, and looked up in the zone
 * when the addition is written; or by [intoId], for a phone album given in the same scenario or
 * one that is not there at all. [name] is what the addition records as the album's name.
 */
internal class GivenAddition(
    private val into: String?,
    private val intoId: Uuid?,
    private val name: String,
) : Given {
    val id: Uuid = Uuid.random()
    private val photos = GivenPhotos()

    fun photo(filename: String, width: Int = 160, height: Int = 120) {
        photos.add(filename, width, height)
    }

    override suspend fun materialise(s3: S3Client, scratch: Path) {
        val target = intoId ?: requireNotNull(into?.let { s3.readZone(scratch).byPath[it] }) {
            "no album at $into to add to -- run sync first"
        }.info.id
        val (rows, thumbsId) = photos.upload(s3, scratch)
        val shard = Shard(
            info = AlbumInfo(
                id = id,
                name = name,
                thumbsId = thumbsId,
                state = AlbumState.UPLOADED,
                encodingVersion = 0,
                addedAt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds),
                addsTo = target,
            ),
            photos = rows,
        )
        val local = Path(scratch, "given-$id.db")
        shard.writeTo(local, NativeSqlDrivers())
        s3.put(shard.info.key, Body.File(local))
    }
}

/** A given shard's photos: real HEIC blobs, and a real pack of their thumbnails. */
private class GivenPhotos {
    private val photos = mutableListOf<GivenPhoto>()

    fun add(filename: String, width: Int, height: Int) {
        photos += GivenPhoto(filename, width, height)
    }

    suspend fun upload(s3: S3Client, scratch: Path): Pair<List<PhotoRow>, ObjectId> {
        val rows = photos.map { photo ->
            // A real HEIC, for the same reason the thumbnail pack is real: a forged zone that a
            // shape assertion can tell apart from a genuine one is a fixture that proves nothing.
            val image = syntheticHeic(photo.width, photo.height)
            val imageId = ObjectId.ofContent(image)
            s3.put(imageId.blobKey, Body.Bytes(image))
            PhotoRow(
                id = Uuid.random(),
                filename = photo.filename,
                width = photo.width,
                height = photo.height,
                bytes = image.size.toLong(),
                sourceBytes = image.size.toLong(),
                mediaType = MediaType.PHOTO,
                imageId = imageId,
            )
        }
        // A forged shard carries a thumbnail pack, because a real one always does: the album
        // list opens a grid from one blob (§5), so a shard without one is a state the phone
        // could not have written and asserting against it would prove nothing.
        val edge = DerivativeSpec.THUMBNAIL_EDGE
        val thumbnails = rows.associate { it.id to syntheticJpeg(edge, edge) }
        // Packed first, then named after what it holds — the same order the CLI uses (§2).
        val pack = Path(scratch, "given-thumbs-${Uuid.random()}.db")
        thumbnails.packThumbnails(pack, NativeSqlDrivers())
        val thumbsId = ObjectId.ofContent(pack)
        s3.put(thumbsId.blobKey, Body.File(pack))
        return rows to thumbsId
    }

    private data class GivenPhoto(val filename: String, val width: Int, val height: Int)
}
