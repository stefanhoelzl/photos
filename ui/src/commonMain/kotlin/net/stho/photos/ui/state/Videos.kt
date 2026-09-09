package net.stho.photos.ui.state

import net.stho.photos.model.PhotoRow

/**
 * The transcoded MP4 a video row plays (§5's uniform HEVC/AAC, 1080p ceiling).
 *
 * Browse-to-cache like a preview, and for the same reason: §5 keeps video *originals* on the
 * laptop, so what the zone holds — and what this fetches — is the transcode.
 */
public interface Videos {
    /** A path on this device, fetching it first if need be. Null when it will not download. */
    public suspend fun localFile(photo: PhotoRow): String?
}
