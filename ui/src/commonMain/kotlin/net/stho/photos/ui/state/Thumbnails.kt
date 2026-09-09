package net.stho.photos.ui.state

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.StateFlow
import net.stho.photos.catalog.Album

/**
 * An album's 256px thumbnails, as the screens need them (§3's pack).
 *
 * Every album's pack is fetched — that is what makes §6's promise that a grid opens instantly
 * and offline true rather than aspirational — but they arrive in the background, so every
 * question here has to have an answer for "not yet".
 *
 * A port for the first of §7's three reasons: the state tier and the screens must be
 * exercisable with no zone and no cache directory.
 */
public interface Thumbnails {
    /** Has this album's pack landed? A grid renders placeholders until it has. */
    public fun has(album: Album): Boolean

    /**
     * The album's cover thumbnail: `cover_photo_id` when one is set, otherwise the album's
     * earliest photo (§3), and for a container the first found by descending into its children.
     */
    public fun cover(album: Album): ByteArray?

    /** Every thumbnail in the album — one file read, which is exactly what the pack buys. */
    public fun all(album: Album): Map<Uuid, ByteArray>

    /**
     * Move this album to the head of the download queue.
     *
     * §6: nothing is ever disabled while loading, so opening an album that has no pack yet is
     * allowed — and this is what makes that bearable rather than a minute of placeholders.
     */
    public fun prioritise(album: Album)

    /** How many packs have landed since launch; bumped so a list already on screen redraws. */
    public val arrivals: StateFlow<Int>

    /** How many are still queued, so the nav bar can say so rather than looking broken. */
    public val outstanding: StateFlow<Int>
}
