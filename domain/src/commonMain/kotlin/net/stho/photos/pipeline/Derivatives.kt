package net.stho.photos.pipeline

import net.stho.photos.exif.ExifTags
import net.stho.photos.faces.DetectedFace
import net.stho.photos.model.PhotoRow

/**
 * Everything the pipeline produces for one item.
 *
 * The object ids in [row] are left null: minting them and naming keys is ingest's job, because
 * only ingest knows what actually reached the bucket.
 */
public data class Derivatives(
    public val row: PhotoRow,
    public val tags: ExifTags,
    /** 256×256 JPEG, sRGB, no metadata. */
    public val thumbnail: ByteArray,
    /**
     * The 3200px-long-edge HEIC a reader displays, source profile intact.
     *
     * There is no companion original: the zone stopped holding those when it stopped being an
     * archive (§5), so this is the only still image the album owns.
     */
    public val image: ByteArray,
    /** The 1080p-ceiling HEVC transcode, owned by the caller once returned. */
    public val video: String? = null,
    /**
     * A Live Photo's source still, uploaded byte-for-byte so its `content.identifier` survives
     * to pair with [liveVideo]. Null for everything else (§5).
     */
    public val liveStill: String? = null,
    /** A Live Photo's paired MOV, uploaded as-is so `PHLivePhotoView` gets what it expects. */
    public val liveVideo: String? = null,
    /**
     * The faces in the still, found in the same decode (§12). Null when none were looked for — a
     * video, or a pipeline without face models — which is not the same as an empty list.
     */
    public val faces: List<DetectedFace>? = null,
)

/** A stage completing, for a caller that wants to show progress. */
public sealed interface PipelineEvent {
    public val path: String

    public data class Started(override val path: String) : PipelineEvent
    public data class Decoded(
        override val path: String,
        public val width: Int,
        public val height: Int,
    ) : PipelineEvent

    public data class Thumbnailed(override val path: String, public val bytes: Int) : PipelineEvent
    public data class Imaged(override val path: String, public val bytes: Int) : PipelineEvent

    /** Emitted per transcoded second, so a 4K video does not sit at one counter for minutes. */
    public data class Transcoding(
        override val path: String,
        public val progress: Double,
    ) : PipelineEvent

    public data class Finished(override val path: String) : PipelineEvent
    public data class Failed(override val path: String, public val message: String) : PipelineEvent
}

/**
 * `IMG_1234.CR2` → `IMG_1234.jpg`. An extensionless name simply gains one.
 *
 * Public because ingest predicts the name before spending the CPU: two rows in one album may
 * not claim the same `filename` (§3), and the only way that happens is a derivative renaming
 * its source onto a sibling.
 */
public fun String.withExtension(extension: String): String {
    // A leading dot is a hidden file, not an extension, so `.hidden` gains one rather than
    // losing its name.
    val dot = lastIndexOf('.')
    val stem = if (dot > 0) substring(0, dot) else this
    return if (stem.isEmpty()) this else "$stem.$extension"
}
