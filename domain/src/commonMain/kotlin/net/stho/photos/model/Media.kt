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
 * [previewId] but leaves the photo the same photo (§3).
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
     * A hint for reconciliation, like `AlbumInfo.sourcePath` — never an identity. It is also
     * what says whether [bytes] can be checked against the directory entry: only a row whose
     * original is the disk file byte-for-byte can be (§7).
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
     * The size of the blob a tap fetches — the original for a still or Live Photo, the carved
     * JPEG for a CR2, the transcode for a video (§3). Not the source file's size.
     */
    public val bytes: Long? = null,
    public val mediaType: MediaType = MediaType.PHOTO,
    /**
     * The original as uploaded. Absent for video (originals stay on the laptop). For a
     * developed RAW this points at the JPEG extracted from the CR2, not the RAW (§5).
     */
    public val originalId: Uuid? = null,
    /** The paired MOV, when [mediaType] is [MediaType.LIVE_PHOTO]. */
    public val liveVideoId: Uuid? = null,
    /** 2048px HEIC. Also the poster still for a video. */
    public val previewId: Uuid? = null,
    /** 1080p HEVC transcode, for video. */
    public val videoId: Uuid? = null,
) {
    /**
     * The name this row's file has on disk: its source name when the blob is a derivative,
     * otherwise the zone name. What reconciliation stats for (§7).
     */
    public val diskFilename: String get() = sourceFilename ?: filename

    /**
     * Whether [bytes] can be checked against the directory entry. True only when the original
     * was uploaded byte-for-byte, which excludes video (no original at all) and carved RAW
     * (the blob is the extracted JPEG).
     */
    public val byteCountIsCheckable: Boolean get() = originalId != null && sourceFilename == null

    /**
     * Every blob this row owns. What deleting the album removes, and what the orphan sweep
     * counts as referenced.
     */
    public val objectIds: List<Uuid>
        get() = listOfNotNull(originalId, liveVideoId, previewId, videoId)
}
