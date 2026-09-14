package net.stho.photos.app

import kotlin.time.Instant
import net.stho.photos.catalog.Album

/**
 * §3's album ordering, and §6's one-icon-that-toggles rule.
 *
 * Two states, newest first and oldest first: the icon performs one visible action, and the nav
 * bar's subtitle names the state, so there is no menu anywhere (§6). The labels are short because
 * that line also carries the count and, during a first sync, the thumbnail progress — on a phone.
 */
public enum class AlbumSort(public val label: String) {
    DateNewest("newest first"),
    DateOldest("oldest first"),
    ;

    public fun next(): AlbumSort = entries[(ordinal + 1) % entries.size]

    /**
     * Albums with no dated photo at all collect at one end (§3) — always the far end, whichever
     * direction the dates run, because "undated" is not a date that sorts.
     *
     * Both orders read **the same date, the album's latest**, so oldest first is newest first
     * reversed. Keying it on the earliest date instead left an album that spans the whole library
     * at the top of *both* orders. [latest] is that date: a container's comes from its
     * descendants ([AlbumSummary]), since it owns no photos of its own.
     */
    public fun sorted(albums: List<Album>, latest: (Album) -> Instant? = { it.dateMax }): List<Album> = when (this) {
        DateNewest -> albums.sortedWith(compareBy<Album> { latest(it) == null }.thenByDescending { latest(it) })
        DateOldest -> albums.sortedWith(compareBy<Album> { latest(it) == null }.thenBy { latest(it) })
    }
}
