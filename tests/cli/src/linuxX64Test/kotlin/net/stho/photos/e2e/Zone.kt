@file:OptIn(ExperimentalUuidApi::class)

package net.stho.photos.e2e

import net.stho.photos.adapter.linux.NativeSqlDrivers
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.toList
import kotlinx.io.files.Path
import net.stho.photos.catalog.AlbumInfo
import net.stho.photos.catalog.BLOB_PREFIX
import net.stho.photos.catalog.META_PREFIX
import net.stho.photos.catalog.Shard
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
    /** Every key under `blob/`, by id, with its size. */
    val blobs: Map<Uuid, Long>,
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
    val shards = mutableListOf<Shard>()
    for (listed in list(prefix = META_PREFIX).toList()) {
        if (listed.isDirectoryMarker) continue
        val bytes = (get(listed.key) as GetResult.Content).bytes
        val local = Path(scratch, "read-${Uuid.random()}.db").write(bytes)
        shards += local.readShard(NativeSqlDrivers())
    }
    val blobs = list(prefix = BLOB_PREFIX).toList()
        .filterNot(S3Object::isDirectoryMarker)
        .mapNotNull { listed ->
            Uuid.parseOrNull(listed.key.removePrefix(BLOB_PREFIX))?.let { it to listed.size }
        }
        .toMap()
    return ZoneState(shards, blobs)
}

/**
 * Writes an album into the zone directly, without a CLI run.
 *
 * The only way to reach a state no run produces -- above all an album with no `source_path`,
 * which is what an album the phone made looks like, and what §7's archive-only pull acts on.
 */
internal class GivenAlbum(private val name: String) {
    /** Null means unclaimed: no local directory has been connected to this album yet. */
    var sourcePath: String? = null

    val id: Uuid = Uuid.random()
    private val photos = mutableListOf<GivenPhoto>()

    fun photo(filename: String, width: Int = 160, height: Int = 120) {
        photos += GivenPhoto(filename, width, height)
    }

    internal suspend fun materialise(s3: S3Client, scratch: Path) {
        val rows = photos.map { photo ->
            val imageId = Uuid.random()
            // A real HEIC, for the same reason the thumbnail pack is real: a forged zone that a
            // shape assertion can tell apart from a genuine one is a fixture that proves nothing.
            val image = syntheticHeic(photo.width, photo.height)
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
        val thumbsId = Uuid.random()
        val pack = Path(scratch, "given-thumbs-$thumbsId.db")
        thumbnails.packThumbnails(pack, NativeSqlDrivers())
        s3.put(thumbsId.blobKey, Body.File(pack))

        val shard = Shard(
            info = AlbumInfo(
                id = id,
                name = name,
                sourcePath = sourcePath,
                thumbsId = thumbsId,
                addedAt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds),
            ),
            photos = rows,
        )
        val local = Path(scratch, "given-$id.db")
        shard.writeTo(local, NativeSqlDrivers())
        s3.put(id.shardKey, Body.File(local))
    }

    private data class GivenPhoto(val filename: String, val width: Int, val height: Int)
}
