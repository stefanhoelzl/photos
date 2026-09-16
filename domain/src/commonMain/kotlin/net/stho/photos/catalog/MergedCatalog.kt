package net.stho.photos.catalog

import app.cash.sqldelight.adapter.primitive.IntColumnAdapter
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.ports.Journal
import net.stho.photos.catalog.merged.MergedDatabase
import net.stho.photos.catalog.merged.MergedQueries
import net.stho.photos.catalog.merged.Photo as MergedPhoto
import net.stho.photos.catalog.merged.Album as AlbumTable
import net.stho.photos.model.PhotoRow

private val mergedAlbumAdapter = AlbumTable.Adapter(
    album_idAdapter = uuidAdapter,
    parentAdapter = uuidAdapter,
    photo_countAdapter = IntColumnAdapter,
    date_minAdapter = instantAdapter,
    date_maxAdapter = instantAdapter,
    cover_photo_idAdapter = uuidAdapter,
    thumbs_idAdapter = objectIdAdapter,
    addition_packsAdapter = objectIdListAdapter,
)

private val mergedPhotoAdapter = MergedPhoto.Adapter(
    idAdapter = uuidAdapter,
    album_idAdapter = uuidAdapter,
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
 * Opens `merged.db` at this path.
 *
 * WAL is not optional (§4): the rebuild holds a single write transaction for 1–3 s while the
 * album list may be on screen, and WAL is what lets readers see the pre-transaction snapshot for
 * its whole duration and switch at commit, rather than blocking.
 */
private fun Path.openMergedDriver(drivers: SqlDrivers) =
    openDriver(drivers, MergedDatabase.Schema, creating = true, journal = Journal.WAL)

/**
 * The merged database's write side (§3): the rebuild, and nothing else.
 *
 * The one write connection. Readers never go through here; they open their own, so a 1–3 s
 * rebuild never puts the album list behind a lock. [CatalogSync] is what serialises writers.
 */
public class CatalogWriter(public val path: Path, drivers: SqlDrivers) : AutoCloseable {

    private val driver = path.openMergedDriver(drivers)
    private val database = MergedDatabase(driver, mergedAlbumAdapter, mergedPhotoAdapter)

    /**
     * Replays every shard into the merged database, wholesale.
     *
     * In place and inside one transaction, not via a temp file and a rename: there is only ever
     * one merged DB on disk, and SQLite's guarantee that readers see the old contents until
     * commit is what makes the swap atomic (§4).
     *
     * Shards arrive already parsed. A shard too new to read never reaches here — the sync skips
     * it and reports it — so anything in [shards] is replayable.
     *
     * Replayable is not the same as shown. An album the phone is still uploading has a shard
     * naming objects that may not exist yet (§8 writes it first, on purpose), so it stays out of
     * the catalog on every device until the second write marks it `uploaded`. The phone doing
     * the uploading shows its progress instead, and nothing else could tell it apart from an
     * album whose blobs went missing.
     */
    public fun rebuild(shards: List<Shard>): RebuildSummary {
        val visible = shards.filter { it.info.state != AlbumState.UPLOADING }
        // An addition is folded into the album it adds to (§8): its photos are that album's, and
        // its pack is one more the album's grid reads from. An addition whose target is not here —
        // deleted before the laptop merged it — is shown as the album it records being added to,
        // so photos the phone may already have deleted from its gallery stay in sight.
        val albumIds = visible.filterNot { it.info.isAddition }.mapTo(mutableSetOf()) { it.info.id }
        val folded = visible.filter { it.info.addsTo?.let(albumIds::contains) == true }
            .sortedBy { it.info.addedAt }
            .groupBy { requireNotNull(it.info.addsTo) }
        val albums = visible.filterNot { it.info.addsTo?.let(albumIds::contains) == true }
        val present = albums.mapTo(mutableSetOf()) { it.info.id }
        val queries = database.mergedQueries
        var photoCount = 0

        database.transaction {
            queries.deleteAllPhotos()
            queries.deleteAllAlbums()

            for (shard in albums) {
                val info = shard.info
                // A parent naming a shard that is not here is not an error: the album surfaces
                // at the root and the sync reports it, so no album can become unreachable
                // because one object failed to arrive (§2).
                val parent = info.parent?.takeIf(present::contains)
                val additions = folded[info.id].orEmpty()
                // A merge the laptop wrote but did not get to finish leaves an addition whose rows
                // the album already holds (§7). They are the same photographs, so they count once.
                val ids = shard.photos.mapTo(mutableSetOf(), PhotoRow::id)
                val photos = shard.photos + additions.flatMap { addition -> addition.photos.filter { ids.add(it.id) } }
                val dates = photos.mapNotNull(PhotoRow::takenAt)
                photoCount += photos.size

                queries.insertAlbum(
                    album_id = info.id,
                    name = info.name,
                    name_folded = info.name.foldedForSearch(),
                    parent = parent,
                    photo_count = photos.size,
                    date_min = dates.minOrNull(),
                    date_max = dates.maxOrNull(),
                    cover_photo_id = info.coverPhotoId,
                    thumbs_id = info.thumbsId,
                    addition_packs = additions.mapNotNull { it.info.thumbsId }.distinct(),
                )

                for (photo in photos) queries.insertPhoto(
                    album_id = info.id,
                    id = photo.id,
                    filename = photo.filename,
                    source_filename = photo.sourceFilename,
                    taken_at = photo.takenAt,
                    lat = photo.latitude,
                    lon = photo.longitude,
                    width = photo.width,
                    height = photo.height,
                    bytes = photo.bytes,
                    media_type = photo.mediaType,
                    source_bytes = photo.sourceBytes,
                    original_hash = photo.originalHash,
                    image_id = photo.imageId,
                    live_still_id = photo.liveStillId,
                    live_video_id = photo.liveVideoId,
                    video_id = photo.videoId,
                )
            }

            // Locations last, once every album is present: a container's pin is the centroid of
            // its descendants', which cannot be known one shard at a time.
            queries.fillLocations()
        }

        return RebuildSummary(
            albums = albums.size,
            photos = photoCount,
            orphanedAlbums = albums.mapNotNull { shard ->
                shard.info.id.takeIf { shard.info.parent?.let(present::contains) == false }
            },
        )
    }

    /** Deletes the merged database's contents without touching the file. */
    public fun clear() {
        database.transaction {
            database.mergedQueries.deleteAllPhotos()
            database.mergedQueries.deleteAllAlbums()
        }
    }

    override fun close(): Unit = driver.close()
}

public data class RebuildSummary(
    public val albums: Int,
    public val photos: Int,
    /** Albums whose [AlbumInfo.parent] named a shard that was not present. Surfaced at the root. */
    public val orphanedAlbums: List<Uuid>,
)

/** One photo and the album it belongs to, for the map's photo layer. */
public data class PlacedPhoto(public val albumId: Uuid, public val photo: PhotoRow)

/** An album name that appears more than once under one parent, and the albums that claim it. */
public data class DuplicateName(public val name: String, public val albums: List<Uuid>)

/**
 * The merged database's read side.
 *
 * Each consumer opens its own connection, which is what lets a query run while the writer holds
 * its transaction (§3). Readers need no locking at all.
 */
public class CatalogReader(public val path: Path, drivers: SqlDrivers) : AutoCloseable {

    /**
     * Opened as the file it finds, never as `creating`.
     *
     * Creating means "run the schema if `user_version` is still 0" — which is exactly what the
     * file reads as while the writer is still creating it. A reader that did so needed the write
     * lock the writer held, and a first run's album list failed with "database is locked" (measured
     * on iOS). The writer alone creates this database; a reader that arrives before it has finished
     * gets "no such file" or "no such table", which every caller already reads as "nothing yet".
     * WAL is still named, because it persists in the file and naming anything else would change it.
     */
    private val driver = path.openDriver(drivers, MergedDatabase.Schema, creating = false, journal = Journal.WAL)
    private val queries: MergedQueries =
        MergedDatabase(driver, mergedAlbumAdapter, mergedPhotoAdapter).mergedQueries

    // ---------------------------------------------------------------------------- albums

    /** An album's children, or the root albums when [parent] is null. */
    public fun albums(under: Uuid?): List<Album> =
        if (under == null) queries.selectRootAlbums(::Album).executeAsList()
        else queries.selectChildAlbums(under, ::Album).executeAsList()

    public fun allAlbums(): List<Album> = queries.selectAllAlbums(::Album).executeAsList()

    public fun album(id: Uuid): Album? = queries.selectAlbum(id, ::Album).executeAsOneOrNull()

    /**
     * Case- and diacritic-insensitive substring search over album names (§3). No fuzzy matching —
     * too noisy on short names — and no filename search.
     */
    public fun searchAlbums(text: String): List<Album> {
        val needle = text.foldedForSearch()
        if (needle.isEmpty()) return emptyList()
        return queries.searchAlbums("%${needle.escapedForLike()}%", ::Album).executeAsList()
    }

    /** Albums with a pin, for the map. */
    public fun placedAlbums(): List<Album> = queries.selectPlacedAlbums(::Album).executeAsList()

    /**
     * Every album name that appears more than once under the same parent.
     *
     * Two devices creating one album no longer collide on a key, so duplicates are possible. They
     * are shown and reported, never merged: merging is a destructive guess about intent (§2).
     */
    public fun duplicateNames(): List<DuplicateName> =
        queries.selectDuplicateNames { name, ids ->
            DuplicateName(name, ids.split(',').mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() })
        }.executeAsList()

    // ---------------------------------------------------------------------------- photos

    /** An album's photos in §3's order: oldest first, undated last by filename. */
    public fun photos(inAlbum: Uuid): List<PhotoRow> =
        queries.selectPhotosInAlbum(inAlbum, ::PhotoRow).executeAsList()

    /**
     * The album's cover: the explicit choice if there is one, otherwise its earliest photo.
     *
     * A container owns no photos, so its cover is resolved by descending into children until one
     * is found — unless it too has an explicit choice, which is why `coverPhotoId` is storable on
     * containers (§3).
     */
    public fun coverPhoto(of: Uuid): PhotoRow? {
        val album = album(of) ?: return null
        album.coverPhotoId
            ?.let { queries.selectPhotoById(it, ::PhotoRow).executeAsOneOrNull() }
            ?.let { return it }
        return queries.selectEarliestPhotoUnder(of, ::PhotoRow).executeAsOneOrNull()
    }

    /**
     * The album a photo belongs to — which is where its thumbnail's pack is: one of its [Album.packs].
     * A container's cover photo belongs to a descendant, since a container has no pack of its own (§2).
     */
    public fun albumOf(photo: Uuid): Album? = queries.selectAlbumOfPhoto(photo, ::Album).executeAsOneOrNull()

    /** Photos with coordinates, for the map's photo layer. */
    public fun placedPhotos(): List<PlacedPhoto> = queries.selectPlacedPhotos {
        albumId, id, filename, sourceFilename, takenAt, lat, lon, width, height, bytes,
        sourceBytes, originalHash, mediaType, imageId, liveStillId, liveVideoId, videoId ->
        PlacedPhoto(
            albumId,
            PhotoRow(
                id, filename, sourceFilename, takenAt, lat, lon, width, height, bytes,
                sourceBytes, originalHash, mediaType, imageId, liveStillId, liveVideoId, videoId,
            ),
        )
    }.executeAsList()

    public fun photoCount(): Int = queries.countPhotos().executeAsOne().toInt()

    /**
     * How many photos were taken on each day, keyed by epoch day; an undated photo is on none.
     *
     * The day is `taken_at`'s UTC day, which is the camera's own: EXIF's local time carries no zone
     * and is stored as if it were UTC (§3). One `GROUP BY` over `ix_photo_taken`, for the calendar.
     */
    public fun photosPerDay(): Map<Long, Int> =
        queries.countPhotosByDay().executeAsList().associate { requireNotNull(it.day) to it.photos.toInt() }

    /** Each album's photos taken from [from] up to [until], for the albums with any: a date filter's matches (§3). */
    public fun photosTakenBetween(from: Instant, until: Instant): Map<Uuid, Int> =
        queries.countPhotosTakenBetween(from, until).executeAsList().associate { it.album_id to it.photos.toInt() }

    override fun close(): Unit = driver.close()
}

/**
 * Folds a name for search: lower-cased and diacritic-stripped, so §3's `Grün→grun`,
 * `Rauhöd→rauhod` and `Straße→strasse` all hold.
 *
 * A fixed table over Latin-1 Supplement and Latin Extended-A rather than an ICU fold, because
 * Kotlin/Native carries no ICU and the alternative would be a Unicode database on each platform —
 * exactly the second implementation §2 removed NFC to avoid. That is affordable *only* because
 * this value lives in the merged database, which is per-device and never uploaded: at worst a
 * search behaves slightly differently on one device. Combining marks are dropped as well, so a
 * decomposed name folds the same as its composed twin.
 */
public fun String.foldedForSearch(): String = buildString(length) {
    for (character in lowercase()) {
        val code = character.code
        val expansion = EXPANSIONS[character]
        when {
            // Combining marks, so a decomposed name folds the same as its composed twin.
            code in 0x0300..0x036F -> Unit
            expansion != null -> append(expansion)
            code in 0x00C0..0x00FF -> append(LATIN_1_SUPPLEMENT[code - 0x00C0])
            code in 0x0100..0x017F -> append(LATIN_EXTENDED_A[code - 0x0100])
            else -> append(character)
        }
    }
}

/** The letters that fold to more than one: ß→ss falls out of case folding elsewhere, not here. */
private val EXPANSIONS = mapOf(
    'æ' to "ae", 'ß' to "ss", 'þ' to "th", 'ĳ' to "ij", 'œ' to "oe",
)

/** U+00C0…U+00FF, one folded letter each; `×` and `÷` are not letters and stay as they are. */
private const val LATIN_1_SUPPLEMENT =
    "aaaaaaaceeeeiiii" + "dnooooo×ouuuuyty" +
    "aaaaaaaceeeeiiii" + "dnooooo÷ouuuuyty"

/** U+0100…U+017F, one folded letter each. */
private const val LATIN_EXTENDED_A =
    "aaaaaaccccccccdd" + "ddeeeeeeeeeegggg" +
    "gggghhhhiiiiiiii" + "iiijjjkkklllllll" +
    "lllnnnnnnnnnoooo" + "oooorrrrrrssssss" +
    "ssttttttuuuuuuuu" + "uuuuwwyyyzzzzzzs"

/** `LIKE` treats `%` and `_` as wildcards, so a search for "50%" would otherwise match everything. */
private fun String.escapedForLike(): String = buildString(length) {
    for (character in this@escapedForLike) {
        if (character == '%' || character == '_' || character == '\\') append('\\')
        append(character)
    }
}
