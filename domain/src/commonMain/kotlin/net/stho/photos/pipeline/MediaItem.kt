package net.stho.photos.pipeline

/** One thing the pipeline can be asked to derive from. */
public data class MediaItem(
    public val path: String,
    public val kind: Kind,
    public val byteCount: Long,
) {
    public sealed interface Kind {
        /** A photograph, in any container the still path can decode. */
        public data object Still : Kind

        /** A Canon raw. Carved rather than decoded — the full-resolution JPEG is already inside. */
        public data object Raw : Kind

        /** A Live Photo: the HEIC still, plus the MOV that pairs with it. */
        public data class LivePhoto(public val video: String) : Kind

        /** A video. §5 keeps the original on the laptop, so only the transcode is uploaded. */
        public data object Video : Kind
    }

    public val filename: String get() = path.substringAfterLast('/')
}

/**
 * A file the classifier deliberately did not turn into a [MediaItem].
 *
 * Reported rather than discarded: a run that silently omitted things would be a run you could
 * not audit.
 *
 * There is no `Excluded` case. Exclusions are `.photosignore`'s business and the walker
 * applies them, so a file the library asked to ignore never reaches the classifier at all.
 */
public data class SkippedFile(public val path: String, public val reason: Reason) {
    public sealed interface Reason {
        /** Sniffed as nothing the pipeline handles. */
        public data object UnrecognisedFormat : Reason

        /** A MOV that pairs with a HEIC; it is uploaded as part of that Live Photo, not alone. */
        public data object ConsumedAsLivePhotoVideo : Reason

        public data class Unreadable(public val detail: String) : Reason
    }
}

/**
 * What a bounded header read decided a file is.
 *
 * Extensions are not consulted. Decision 22's denylist excludes the known junk and everything
 * else is sniffed, so a file's content decides what it is — which is also why pointing the scan
 * at a 2.66 GB SQLite database costs one small read rather than a decode attempt.
 */
public enum class MediaFormat {
    JPEG,
    HEIF,
    PNG,
    TIFF,
    CR2,
    VIDEO,
    UNKNOWN,
    ;

    public val isStillImage: Boolean
        get() = when (this) {
            JPEG, HEIF, PNG, TIFF -> true
            CR2, VIDEO, UNKNOWN -> false
        }
}

/** What a video's container says about it, before any decoding. */
public data class VideoInfo(
    public val width: Int,
    public val height: Int,
    public val duration: Double,
    /** Degrees from the display matrix. 118 of the library's files say 90 and 15 say 180. */
    public val rotation: Int,
    public val hasAudio: Boolean,
    public val isInterlaced: Boolean,
    /**
     * `com.apple.quicktime.content.identifier` — the Live Photo pairing id, and the whole basis
     * of decision 14. Null when absent.
     */
    public val contentIdentifier: String? = null,
)
