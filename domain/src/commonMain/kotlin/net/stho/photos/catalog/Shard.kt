package net.stho.photos.catalog

import app.cash.sqldelight.adapter.primitive.IntColumnAdapter
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.ShardFailure
import net.stho.photos.catalog.shard.Album_info
import net.stho.photos.catalog.shard.ShardDatabase
import net.stho.photos.catalog.shard.ShardQueries
import net.stho.photos.model.PhotoRow
import net.stho.photos.catalog.shard.Photo as ShardPhoto

/**
 * A shard is a file, not a byte array (§3).
 *
 * §4's on-device layout already keeps `shards/<uuid>.db` as the source of truth for a rebuild, so
 * opening one is opening a file the design wanted anyway — which is what makes the platform's own
 * SQLite sufficient, since nothing has to serialise a database in or out of memory.
 */

private val albumInfoAdapter = Album_info.Adapter(
    album_idAdapter = uuidAdapter,
    parentAdapter = uuidAdapter,
    cover_photo_idAdapter = uuidAdapter,
    thumbs_idAdapter = objectIdAdapter,
    stateAdapter = albumStateAdapter,
    encoding_versionAdapter = IntColumnAdapter,
    added_atAdapter = instantAdapter,
    schema_versionAdapter = IntColumnAdapter,
)

private val shardPhotoAdapter = ShardPhoto.Adapter(
    idAdapter = uuidAdapter,
    taken_atAdapter = instantAdapter,
    widthAdapter = IntColumnAdapter,
    heightAdapter = IntColumnAdapter,
    media_typeAdapter = mediaTypeAdapter,
    image_idAdapter = objectIdAdapter,
    live_still_idAdapter = objectIdAdapter,
    live_video_idAdapter = objectIdAdapter,
    video_idAdapter = objectIdAdapter,
)

/**
 * What can be learned from a shard this build cannot read.
 *
 * The fields come from the two `album_info` columns §3 declares permanently stable, so a reader
 * from any era can produce one.
 */
public data class ShardProbe(
    public val albumId: Uuid,
    public val sourcePath: String?,
    public val schemaVersion: Int,
)

/**
 * Writes this shard to [path], ready to PUT at `meta/<album-uuid>.db`.
 *
 * The whole shard is rewritten every time; there is no incremental path, which is what makes
 * stale rows impossible by construction (§2's single-owner rule). The file is removed first for
 * the same reason — a rewrite must not inherit a single row of the old one.
 */
public fun Shard.writeTo(path: Path, drivers: SqlDrivers) {
    SystemFileSystem.delete(path, mustExist = false)
    val driver = path.openDriver(drivers, ShardDatabase.Schema, creating = true)
    try {
        val database = ShardDatabase(driver, albumInfoAdapter, shardPhotoAdapter)
        database.transaction {
            database.shardQueries.insertAlbumInfo(
                album_id = info.id,
                name = info.name,
                parent = info.parent,
                source_path = info.sourcePath,
                cover_photo_id = info.coverPhotoId,
                thumbs_id = info.thumbsId,
                state = info.state,
                encoding_version = info.encodingVersion,
                added_at = info.addedAt,
                schema_version = info.schemaVersion,
            )
            for (photo in photos) database.shardQueries.insertPhoto(
                id = photo.id,
                filename = photo.filename,
                source_filename = photo.sourceFilename,
                taken_at = photo.takenAt,
                lat = photo.latitude,
                lon = photo.longitude,
                width = photo.width,
                height = photo.height,
                bytes = photo.bytes,
                source_bytes = photo.sourceBytes,
                original_hash = photo.originalHash,
                media_type = photo.mediaType,
                image_id = photo.imageId,
                live_still_id = photo.liveStillId,
                live_video_id = photo.liveVideoId,
                video_id = photo.videoId,
                live_video_filename = photo.liveVideoFilename,
            )
        }
    } finally {
        driver.close()
    }
}

/** Reads the shard at this path. */
public fun Path.readShard(drivers: SqlDrivers): Shard = withShard(drivers) { queries ->
    val version = queries.schemaVersion()
    if (version > SHARD_SCHEMA_VERSION) {
        throw ShardFailure.UnsupportedVersion(found = version, supported = SHARD_SCHEMA_VERSION)
    }
    val info = queries.selectAlbumInfo(::AlbumInfo).executeAsOneOrNull()
        ?: throw ShardFailure.MissingAlbumInfo()
    // Reading an older shard is reading a file with fewer columns, and SQLite answers a
    // statement naming one it does not have with an error rather than a null. So the version
    // picks the statement — the cost of §3's promise to read anything at or below this build,
    // paid once per column that is ever added. `liveVideoFilename` is null for the older shape,
    // which is exactly what "this shard never recorded it" means.
    val photos = if (version >= SCHEMA_LIVE_VIDEO_FILENAME) {
        queries.selectPhotos(::PhotoRow).executeAsList()
    } else {
        queries.selectPhotosBeforeSchema4(::PhotoRow).executeAsList()
    }
    Shard(info, photos)
}

/**
 * What this shard says about itself even when it is too new to read.
 *
 * Only the two columns §3 declares permanently stable are touched, so this keeps working across
 * every future schema. It is what makes a skipped shard *unreadable* rather than *absent*: the
 * CLI learns which folder the album claims, leaves that folder alone, and so cannot re-upload it
 * as a duplicate (§3).
 */
public fun Path.probeShard(drivers: SqlDrivers): ShardProbe = withShard(drivers) { queries ->
    queries.selectProbe(::ShardProbe).executeAsOneOrNull() ?: throw ShardFailure.MissingAlbumInfo()
}

/**
 * The schema version alone, without decoding the rest.
 *
 * Lets a caller decide to skip a shard before paying to read it.
 */
public fun Path.shardSchemaVersion(drivers: SqlDrivers): Int =
    withShard(drivers, ShardQueries::schemaVersion)

private fun ShardQueries.schemaVersion(): Int =
    selectSchemaVersion().executeAsOneOrNull() ?: throw ShardFailure.MissingAlbumInfo()

/**
 * Opens this file as a shard for the duration of [block].
 *
 * A file with no `album_info` at all is not a shard — a truncated download, a wrong key, an
 * unrelated database. That is [ShardFailure.MissingAlbumInfo], not a raw SQLite error, so a
 * caller can tell "not a shard" from "SQLite is unwell".
 */
private inline fun <T> Path.withShard(drivers: SqlDrivers, block: (ShardQueries) -> T): T {
    val driver = openDriver(drivers, ShardDatabase.Schema, creating = false)
    try {
        if (!driver.hasTable("album_info")) throw ShardFailure.MissingAlbumInfo()
        return block(ShardDatabase(driver, albumInfoAdapter, shardPhotoAdapter).shardQueries)
    } finally {
        driver.close()
    }
}
