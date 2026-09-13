package net.stho.photos.app

import net.stho.photos.model.PhotoRow

/**
 * The moving half of a row: a video's transcode, or a Live Photo's pair.
 *
 * Browse-to-cache like a preview, and for the same reason: §5 keeps video *originals* on the
 * laptop, so what the zone holds — and what this fetches — is the transcode. A Live Photo is the
 * exception §5 names: its still is kept byte-for-byte, because the pairing the platform plays
 * it by does not survive a re-encode.
 */
public interface Videos {
    /** A path on this device, fetching it first if need be. Null when it will not download. */
    public suspend fun localFile(photo: PhotoRow): String?

    /**
     * A Live Photo's untouched still and its MOV, both on disk.
     *
     * Both or neither: the platform view pairs them by an identifier inside each file, so half a
     * pair is not a smaller Live Photo, it is a still — which the viewer is already showing.
     */
    public suspend fun livePair(photo: PhotoRow): LivePair?
}

/** Two local paths that play as one Live Photo. */
public data class LivePair(val still: String, val video: String)
