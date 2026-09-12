package net.stho.photos.app

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.uuid.Uuid
import net.stho.photos.model.PhotoRow

/**
 * The 2048px previews a swipe shows (§5's second tier), browse-to-cache (§6).
 *
 * Nothing is downloaded ahead of time and nothing is auto-evicted: whatever you view is kept
 * until you clear it. The viewer prefetches ±3 around the current photo and cancels the rest
 * when you leave the album — the whole-album variant belongs to E.2, the milestone that also
 * brings the button that clears it again.
 *
 * A port for two of §7's three reasons at once: it needs a fake to be testable, and its
 * implementation is a platform's decoder — HEIC decodes through `CGImageSource` on iOS and
 * through libheif on Linux, neither of which shared code can name.
 */
public interface Previews {
    /** Already on disk and decoded? Then the viewer can draw it this frame. */
    public fun cached(photo: PhotoRow): Preview?

    /** Fetch and decode, if it is not cached. Suspends; safe to cancel. */
    public suspend fun load(photo: PhotoRow): Preview?

    /** Warm the cache around [index] without waiting for any of it. */
    public fun prefetch(photos: List<PhotoRow>, index: Int)

    /** Leaving the album abandons whatever is still in flight. */
    public fun cancelPrefetch()
}

/**
 * A decoded preview, ready to draw.
 *
 * An `ImageBitmap` rather than raw pixels, because decision 28's rule puts a UI-shaped
 * implementation in the platform **app** module, which is already a UI module — so this port's
 * adapter may name a Compose type while `:adapter:linux`, which does the actual HEIC decode,
 * stays free of Compose and hands back pixels.
 */
public class Preview(public val id: Uuid, public val image: ImageBitmap)
