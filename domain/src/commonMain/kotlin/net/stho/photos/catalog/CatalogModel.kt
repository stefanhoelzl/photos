package net.stho.photos.catalog

import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.stho.photos.derivative.DerivativeSpec
import net.stho.photos.model.PhotoRow

/**
 * The shard schema version this build writes and is willing to read.
 *
 * A reader reads any shard at or below this and **skips anything above it**, naming it in the
 * sync report. A device always reads what it wrote, so a skip only ever affects whichever device
 * is behind (§3).
 *
 * **2** — `photo.source_filename` added, and `photo.bytes` redefined to describe the blob a tap
 * fetches rather than the file on disk (§3). The bump is not merely about the new column: a
 * version-1 reader would read `bytes` as something it is not.
 *
 * **3** — the two-tier rewrite. `photo.original_id` and `photo.preview_id` collapse into a
 * single `image_id`, since the zone no longer holds originals at all; `photo.source_bytes` and
 * `photo.content_hash` replace the byte check that used to ride on `bytes`; and `album_info`
 * gains `state` and `encoding_version`. A version-2 reader would find no image to display.
 *
 * **4** — `photo.live_video_filename` added, so a Live Photo's MOV is a file the catalog can
 * name. It is the same kind of column as `source_filename` and it fixes the same kind of bug:
 * without it reconciliation cannot tell the one file in the library that no row is named after
 * from a file that was never ingested, so it planned every pair as an upload on every run (§7).
 * A version-3 reader would read such a shard correctly but write it back without the column,
 * which is why this is a bump and not a silent addition — being skipped and reported is
 * recoverable, and being quietly un-fixed once per run is not.
 *
 * **5** — `album_info.adds_to`, for the shards the phone writes when it adds photos to an album
 * that already exists (§8). Those live under `addition/` rather than `meta/`, so a reader that
 * predates them never lists one; the bump is for the column, as every added column's is.
 */
public const val SHARD_SCHEMA_VERSION: Int = 5

/**
 * The first version whose `photo` table has `live_video_filename`.
 *
 * Named rather than spelled `4` at the one place that reads it, because what the reader is
 * asking is "does this file have the column", not "is this shard modern".
 */
internal const val SCHEMA_LIVE_VIDEO_FILENAME: Int = 4

/** The first version whose `album_info` has `adds_to`. */
internal const val SCHEMA_ADDS_TO: Int = 5

/**
 * An album's own record — the single row of a shard's `album_info` table.
 *
 * [id] is the album's identity and equals its key (`meta/<id>.db`). Renaming or re-parenting an
 * album changes [name] or [parent]; it never changes [id], and it moves no objects (§2).
 *
 * Names are stored exactly as given: §2 measured NFC normalisation out of the project, having
 * found that none of the library's 51 non-ASCII names is decomposed.
 */
public data class AlbumInfo(
    public val id: Uuid,
    public val name: String,
    /**
     * The parent album's id, or null at the root. A parent that resolves to no shard is not an
     * error — see [SyncReport.orphanedAlbums].
     */
    public val parent: Uuid? = null,
    /**
     * The folder this album came from, relative to `$LIBRARY_ROOT`. A hint the CLI uses to
     * reconnect a local directory to its shard, never an identity.
     */
    public val sourcePath: String? = null,
    /** Overrides the default cover, which is otherwise the album's earliest photo. */
    public val coverPhotoId: Uuid? = null,
    /** The blob holding this album's packed thumbnails, or null before one exists. */
    public val thumbsId: ObjectId? = null,
    /**
     * Where this album is in its lifecycle, and so who owns it (§7). A laptop-made album is
     * born [AlbumState.ENCODED]; only the phone ever writes the other two.
     */
    public val state: AlbumState = AlbumState.ENCODED,
    /**
     * Which encoding profile produced this album's image blobs, or 0 for "as uploaded, never
     * encoded here". `sync` re-derives any album below [DerivativeSpec.ENCODING_VERSION].
     *
     * The schema forbids the two ways this can contradict [state]: `ENCODED` at 0, or either
     * other state above 0.
     */
    public val encodingVersion: Int = DerivativeSpec.ENCODING_VERSION,
    /**
     * Stored to whole-second resolution, so a value that has been through a shard and one that
     * has not compare equal. Callers minting one from a clock truncate it first.
     */
    public val addedAt: Instant,
    public val schemaVersion: Int = SHARD_SCHEMA_VERSION,
    /**
     * The album these photos are being added to, or null for an album of its own (§8).
     *
     * An addition is the phone's way of putting photos into an album it does not own: a shard of
     * its own at `addition/<id>.db`, with its own [state] and its own pack, which readers fold into
     * the target and the laptop merges into it. [name] and [parent] hold the target's as they were
     * when it was uploaded, so an addition whose target has gone reads as an album of its own.
     */
    public val addsTo: Uuid? = null,
) {
    /** Whether this shard adds photos to another album rather than being one. */
    public val isAddition: Boolean get() = addsTo != null

    /** Where this shard lives in the zone: `meta/<id>.db`, or `addition/<id>.db` for an addition. */
    public val key: String get() = if (isAddition) id.additionKey else id.shardKey
}

/**
 * One album as the merged database sees it: the shard's own fields plus everything that can only
 * be known once every shard is present.
 */
public data class Album(
    public val id: Uuid,
    public val name: String,
    /** Case- and diacritic-folded, for search. Merged-DB only — never uploaded. */
    public val nameFolded: String,
    /** Null at the root, and also when the shard named a parent that does not exist. */
    public val parent: Uuid?,
    public val photoCount: Int,
    public val dateMin: Instant?,
    public val dateMax: Instant?,
    /**
     * Centroid of this album's tagged photos, or of all its descendants' for a container.
     * Computed at rebuild; null means "not on the map".
     */
    public val latitude: Double?,
    public val longitude: Double?,
    public val coverPhotoId: Uuid?,
    public val thumbsId: ObjectId?,
    /**
     * The packs of the additions folded into this album, which hold the thumbnails of the photos
     * the phone added and the laptop has not merged yet (§8). Merged-DB only.
     */
    public val additionPacks: List<ObjectId> = emptyList(),
) {
    /** Every pack this album's grid reads from: its own, then its additions'. */
    public val packs: List<ObjectId> get() = listOfNotNull(thumbsId) + additionPacks
}

/** The contents of one shard. */
public data class Shard(
    public val info: AlbumInfo,
    public val photos: List<PhotoRow> = emptyList(),
) {
    /** Whether this album's images are below the profile this build writes, so need re-deriving. */
    public val needsReencode: Boolean
        get() = info.encodingVersion < DerivativeSpec.ENCODING_VERSION

    /** Every blob the album owns, thumbnail pack included. */
    public val objectIds: List<ObjectId>
        get() = photos.flatMap(PhotoRow::objectIds) + listOfNotNull(info.thumbsId)
}

// ------------------------------------------------------------------------------------- keys
//
// The three prefixes the zone has (§2). Keys are built here and nowhere else.

public const val META_PREFIX: String = "meta/"
public const val ADDITION_PREFIX: String = "addition/"
public const val BLOB_PREFIX: String = "blob/"

/** This album's shard key: `meta/<album-uuid>.db`. */
public val Uuid.shardKey: String get() = "$META_PREFIX$this.db"

/** An addition's shard key: `addition/<uuid>.db` (§8). */
public val Uuid.additionKey: String get() = "$ADDITION_PREFIX$this.db"

/** This object's blob key: `blob/<id>`, with no extension (§2). */
public val ObjectId.blobKey: String get() = "$BLOB_PREFIX$this"

/**
 * The album id in `meta/<uuid>.db`, or null for anything else — a directory marker, a stray key,
 * a name that is not a uuid.
 *
 * The design leans on LIST being the whole sync mechanism, so this is deliberately strict: a key
 * it cannot parse is skipped, never guessed at.
 */
public fun String.asShardAlbumId(): Uuid? = shardId(META_PREFIX)

/** The addition id in `addition/<uuid>.db`, or null for anything else — as strict as [asShardAlbumId]. */
public fun String.asAdditionId(): Uuid? = shardId(ADDITION_PREFIX)

private fun String.shardId(prefix: String): Uuid? {
    if (!startsWith(prefix) || !endsWith(".db")) return null
    val name = substring(prefix.length, length - ".db".length)
    return if (name.isEmpty()) null else Uuid.parseOrNull(name)
}

/**
 * The object id in `blob/<id>`, or null for anything else. What the orphan sweep uses to tell a
 * blob from the `blob/` directory marker or a stray key.
 */
public fun String.asBlobObjectId(): ObjectId? =
    if (startsWith(BLOB_PREFIX)) ObjectId.parse(substring(BLOB_PREFIX.length)) else null

private fun Uuid.Companion.parseOrNull(text: String): Uuid? = runCatching { parse(text) }.getOrNull()
