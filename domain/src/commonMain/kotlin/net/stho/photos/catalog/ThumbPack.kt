package net.stho.photos.catalog

import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.thumb.Thumb
import net.stho.photos.catalog.thumb.ThumbPackDatabase
import net.stho.photos.catalog.thumb.ThumbQueries

private val thumbAdapter = Thumb.Adapter(idAdapter = uuidAdapter)

/**
 * An album's thumbnails, packed into one blob (§3).
 *
 * The pack exists so a grid opens in **one request, offline**. One blob per thumbnail would be
 * uniform with everything else and would make adding a photo a 9 KB write instead of a repack —
 * but it would also cost 1,755 round trips to open the largest album here, against one ~15 MB
 * GET. bunny.net charges no per-request fee, so this is latency rather than money; the promise it
 * protects is §6's.
 *
 * It is referenced from the shard by [AlbumInfo.thumbsId] rather than living under its own
 * prefix, so a changed pack is visible through the single LIST on `meta/` that §4 already does:
 * the reference *is* the change signal.
 *
 * Like a shard, a pack is a file — §6 keeps them permanently, so there is nothing to serialise.
 */
public class ThumbPack(private val path: Path) {

    /** Every thumbnail in the pack. For a whole grid, which is the usual case. */
    public fun unpack(): Map<Uuid, ByteArray> =
        read { it.selectAll { id, jpeg -> id to jpeg }.executeAsList().toMap() }

    /** One thumbnail, without decoding the rest — for a cover tile in the album list. */
    public fun thumbnail(id: Uuid): ByteArray? = read { it.selectOne(id).executeAsOneOrNull() }

    /** The ids the pack holds, without their bytes. */
    public fun ids(): Set<Uuid> = read { it.selectIds().executeAsList().toSet() }

    private inline fun <T> read(block: (ThumbQueries) -> T): T {
        val driver = path.openDriver(ThumbPackDatabase.Schema, creating = false)
        try {
            return block(ThumbPackDatabase(driver, thumbAdapter).thumbQueries)
        } finally {
            driver.close()
        }
    }
}

/**
 * Packs these thumbnails, keyed by `PhotoRow.id`, into a new pack at [path].
 *
 * Rows go in sorted, so a pack of the same thumbnails is the same bytes twice running. Nothing
 * depends on that — blobs are immutable and get a fresh uuid anyway — but it makes two packs
 * comparable when a test or a person needs to know whether anything actually changed.
 */
public fun Map<Uuid, ByteArray>.packThumbnails(into: Path): ThumbPack {
    SystemFileSystem.delete(into, mustExist = false)
    val driver = into.openDriver(ThumbPackDatabase.Schema, creating = true)
    try {
        val database = ThumbPackDatabase(driver, thumbAdapter)
        database.transaction {
            for (id in keys.sortedBy(Uuid::toString)) {
                database.thumbQueries.insertThumb(id, getValue(id))
            }
        }
    } finally {
        driver.close()
    }
    return ThumbPack(into)
}
