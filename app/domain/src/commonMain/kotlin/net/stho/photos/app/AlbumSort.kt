package net.stho.photos.app

import net.stho.photos.catalog.Album

/**
 * §3's album ordering, and §6's one-icon-that-cycles rule.
 *
 * Three states rather than two axes: the icon performs one visible action, and the nav bar's
 * subtitle names the state, so there is no menu anywhere (§6).
 */
public enum class AlbumSort(public val label: String) {
    DateNewest("sorted by date, newest first"),
    DateOldest("sorted by date, oldest first"),
    Name("sorted by name"),
    ;

    public fun next(): AlbumSort = entries[(ordinal + 1) % entries.size]

    /**
     * Albums with no dated photo at all collect at one end (§3) — always the far end, whichever
     * direction the dates run, because "undated" is not a date that sorts.
     */
    public fun sorted(albums: List<Album>): List<Album> = when (this) {
        DateNewest -> albums.sortedWith(compareBy<Album> { it.dateMax == null }.thenByDescending { it.dateMax })
        DateOldest -> albums.sortedWith(compareBy<Album> { it.dateMin == null }.thenBy { it.dateMin })
        Name -> albums.sortedBy { it.nameFolded }
    }
}
