package net.stho.photos.model

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What kind of thing a photo row describes (§3). */
public enum class MediaType(public val code: Int) {
    PHOTO(0),
    VIDEO(1),
    LIVE_PHOTO(2),
    ;

    public companion object {
        private val byCode = entries.associateBy(MediaType::code)

        /** For reading the column back; unknown codes are null rather than a default. */
        public fun of(code: Int): MediaType? = byCode[code]
    }
}

/**
 * One photo, video or Live Photo.
 *
 * [id] is row identity and nothing else: it is what `coverPhotoId` points at and what the
 * thumbnail pack keys by, so it survives a derivative being re-encoded — which mints a new
 * [imageId] but leaves the photo the same photo (§3).
 *
 * Lives in the domain rather than beside the storage code because the pipeline produces these
 * and the catalog stores them; a type both sides own belongs below both.
 */
@OptIn(ExperimentalUuidApi::class)
public data class PhotoRow(
    public val id: Uuid,
    /**
     * The name of the object *in the zone* — NFC, with the extension its bytes actually have —
     * so a pull restores a file that is what it claims to be. For a still this is the on-disk
     * name; for a carved CR2 it is `.jpg`, and for a transcode `.mp4` (§3).
     */
    public val filename: String,
    /**
     * The name the file had at ingest, when it differs from [filename]: `IMG_1234.CR2` beside
     * `IMG_1234.jpg`, `VID_0001.MOV` beside `VID_0001.mp4`. Null for the great majority of
     * rows, where the blob simply *is* the file on disk.
     *
     * A hint for reconciliation, like `AlbumInfo.sourcePath` — never an identity.
     */
    public val sourceFilename: String? = null,
    /**
     * EXIF `DateTimeOriginal`, truncated to whole seconds — EXIF has one-second resolution, so
     * nothing is lost that was ever really there. Null for the 0.5% of photos without one;
     * those sort last and are absent from date filters.
     */
    public val takenAt: Instant? = null,
    public val latitude: Double? = null,
    public val longitude: Double? = null,
    /** Display dimensions: already rotated, so no consumer applies orientation (§3). */
    public val width: Int? = null,
    public val height: Int? = null,
    /**
     * The size of the blob a tap fetches: the viewing image for a still, the transcode for a
     * video (§3). Not the source file's size — see [sourceBytes].
     */
    public val bytes: Long? = null,
    /**
     * The size of the file on disk at ingest.
     *
     * This is what the change assertion compares against the directory entry, and it is free
     * for the same reason its predecessor was: the scan reads the entry anyway. Unlike the old
     * check — which could only run where the blob happened to *be* the file — this holds for
     * every row, video and carved RAW included, because it describes the source rather than
     * the upload (§7).
     */
    public val sourceBytes: Long? = null,
    /**
     * Digest of the source file, recorded at ingest where the file is already being read.
     *
     * Deliberately never verified on a schedule: a full pass is ~100 GiB of reads against a
     * timer that fires hourly. It is a forensic record for investigating a file already
     * suspected of having changed, not a monitor (§7).
     */
    public val contentHash: String? = null,
    public val mediaType: MediaType = MediaType.PHOTO,
    /**
     * The one image a reader displays: the 3200px HEIC, and for a video its poster still.
     *
     * There is no separate original. The zone is not an archive — the laptop library is — so
     * every image here is a derivative sized for the largest screen that will show it (§5).
     * While an album is still `uploading` or `uploaded` this points at the phone's
     * full-quality upload instead, which is what `encoding_version` distinguishes.
     */
    public val imageId: Uuid? = null,
    /**
     * A Live Photo's source still, byte-for-byte, when [mediaType] is [MediaType.LIVE_PHOTO].
     *
     * The one place an untouched original survives in the zone. `PHLivePhotoView` pairs a still
     * with its MOV by Apple's `content.identifier`, which re-encoding strips — so rather than
     * doing the maker-note surgery §5 set out to avoid, the 187 photos that need one keep one.
     */
    public val liveStillId: Uuid? = null,
    /** The paired MOV, when [mediaType] is [MediaType.LIVE_PHOTO]. */
    public val liveVideoId: Uuid? = null,
    /** 1080p HEVC transcode, for video. */
    public val videoId: Uuid? = null,
) {
    /**
     * The name this row's file has on disk: its source name when the blob is a derivative,
     * otherwise the zone name. What reconciliation stats for (§7).
     */
    public val diskFilename: String get() = sourceFilename ?: filename

    /**
     * Whether this row records a source size to check the directory entry against.
     *
     * Every row written by this build does. The guard is for rows read back from a shard an
     * older writer produced, where the column is absent.
     */
    public val byteCountIsCheckable: Boolean get() = sourceBytes != null

    /**
     * Every blob this row owns. What deleting the album removes, and what the orphan sweep
     * counts as referenced.
     */
    public val objectIds: List<Uuid>
        get() = listOfNotNull(imageId, liveStillId, liveVideoId, videoId)
}
