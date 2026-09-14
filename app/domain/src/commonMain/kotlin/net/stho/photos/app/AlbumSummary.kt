package net.stho.photos.app

import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album

/**
 * What an album row says it holds, and the date the list sorts it by.
 *
 * For an album of photos both are its own. For a container they are summed over every
 * descendant, because a container owns no photos (§2) — so read straight from the merged DB it
 * said "0 photos" and, having no dated photo, sorted as undated: always at the far end of both
 * date orders, whatever its sub-albums held.
 */
public data class AlbumSummary(
    /** Direct sub-albums. Zero for an album of photos: §2's sub-albums XOR photos. */
    val albums: Int,
    /** Every photo at or below this album. */
    val photos: Int,
    /** The latest photo at or below this album; null when none of them is dated. */
    val latest: Instant?,
) {
    /** The row's second line: "2 albums · 132 photos" for a container, "132 photos" otherwise. */
    public fun contents(): String =
        if (albums > 0) "$albums albums · $photos photos" else "$photos photos"
}

/**
 * Every album's [AlbumSummary], computed once over the flat tree.
 *
 * Depth-first with a visited set, for the reason [cacheByAlbum] has one: an album whose parent
 * resolved to nothing is surfaced rather than dropped, so a cycle means a shard lied, and a stack
 * overflow is a poor way to find that out.
 */
public fun summariesByAlbum(albums: List<Album>): Map<Uuid, AlbumSummary> {
    val children = albums.groupBy { it.parent }
    val out = mutableMapOf<Uuid, AlbumSummary>()
    val visiting = mutableSetOf<Uuid>()

    fun of(album: Album): AlbumSummary {
        out[album.id]?.let { return it }
        if (!visiting.add(album.id)) return AlbumSummary(0, 0, null)
        val kids = children[album.id].orEmpty()
        var photos = album.photoCount
        var latest = album.dateMax
        for (kid in kids) {
            val summary = of(kid)
            photos += summary.photos
            val theirs = summary.latest
            if (theirs != null && (latest == null || theirs > latest)) latest = theirs
        }
        return AlbumSummary(kids.size, photos, latest).also { out[album.id] = it }
    }

    albums.forEach(::of)
    return out
}
