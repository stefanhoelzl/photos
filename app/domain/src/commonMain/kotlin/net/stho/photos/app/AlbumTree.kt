package net.stho.photos.app

import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album

/**
 * One line of the album list (§6): an album, a container's header, or the line closing its group.
 *
 * A container is not a row you tap to find out what is in it. It heads its own sub-albums, which
 * are always on the list beneath it, one indent in — so a trip's albums read as a trip without
 * leaving the list.
 */
public sealed interface ListEntry {
    /** Unique across one list: an album appears on it at most once. */
    public val key: String

    public data class Row(
        val album: Album,
        /** How many containers above it are on this list, which is its indent. */
        val depth: Int,
        /** A container, drawn as the header of the group beneath it. */
        val header: Boolean,
        /** The containers it sits in on this list, outermost first — what pins above it. */
        val ancestors: List<Uuid>,
        /** The row's second line. */
        val contents: String,
        /**
         * The albums this row's strip reads and its actions act on: itself, whose rollup already
         * covers every descendant — or, for a header a search has narrowed, only the matches
         * beneath it, so the count, the strip and the actions all describe what is on screen.
         */
        val covers: List<Uuid>,
    ) : ListEntry {
        override val key: String get() = album.id.toString()
    }

    /** The end of [container]'s group, drawn as a heavier divider. */
    public data class End(
        val container: Uuid,
        /** The closing container's own ancestors, outermost first. */
        val ancestors: List<Uuid>,
    ) : ListEntry {
        override val key: String get() = "end:$container"
    }
}

/**
 * The list under [root] — the library when null — every level of it, in [sort] among siblings.
 *
 * Siblings keep one date order whether they are albums or containers, so a recent album is never
 * pulled away from its date; the indent is what says where each row belongs, and the closing
 * line where a group ends.
 *
 * With [matches], a search: a matching album is listed under its containers' headers, a matching
 * container with everything beneath it, and nothing else. A header that only *holds* a match
 * counts what was kept — "1 of 3 albums · 40 photos".
 *
 * With [counted], the matches are a date range's, and every count is of the photos taken in it: an
 * album reads "12 of 132 photos" and a header "1 of 3 albums · 12 of 252 photos" — the numbers the
 * calendar shows for those days. An album whose every photo is in the range reads as it always does.
 *
 * Depth-first with a visited set, for the reason [summariesByAlbum] has one.
 */
public fun albumRows(
    tree: List<Album>,
    root: Uuid?,
    sort: AlbumSort,
    summaries: Map<Uuid, AlbumSummary>,
    matches: Set<Uuid>? = null,
    counted: Map<Uuid, Int>? = null,
): List<ListEntry> {
    val children = tree.groupBy { it.parent }
    val out = mutableListOf<ListEntry>()
    val placed = mutableSetOf<Uuid>()

    fun summaryOf(album: Album) = summaries[album.id] ?: AlbumSummary(0, album.photoCount, album.dateMax)

    fun photosLine(inRange: Int, of: Int) = if (inRange == of) "$of photos" else "$inRange of $of photos"

    val keeps = mutableMapOf<Uuid, Boolean>()
    val holding = mutableSetOf<Uuid>()
    fun kept(album: Album): Boolean = keeps[album.id] ?: run {
        if (matches == null || album.id in matches) return@run true
        if (!holding.add(album.id)) return@run false
        children[album.id].orEmpty().any(::kept).also { holding -= album.id }
    }.also { keeps[album.id] = it }

    /** Beneath a narrowed header: the topmost matches, whose rollups do not overlap. */
    fun matchedUnder(album: Album, seen: MutableSet<Uuid> = mutableSetOf()): List<Album> =
        if (!seen.add(album.id)) emptyList()
        else children[album.id].orEmpty().filter(::kept).flatMap { kid ->
            if (matches == null || kid.id in matches) listOf(kid) else matchedUnder(kid, seen)
        }

    fun emit(level: List<Album>, depth: Int, ancestors: List<Uuid>, narrowed: Boolean) {
        val shown = if (narrowed) level.filter(::kept) else level
        for (album in sort.sorted(shown) { summaryOf(it).latest }) {
            if (!placed.add(album.id)) continue
            val kids = children[album.id].orEmpty()
            if (kids.isEmpty()) {
                val contents = if (counted == null) summaryOf(album).contents()
                else photosLine(counted[album.id] ?: 0, summaryOf(album).photos)
                out += ListEntry.Row(album, depth, false, ancestors, contents, listOf(album.id))
                continue
            }
            val whole = !narrowed || matches == null || album.id in matches
            out += if (whole) {
                ListEntry.Row(album, depth, true, ancestors, summaryOf(album).contents(), listOf(album.id))
            } else {
                val covered = matchedUnder(album)
                val photos = if (counted == null) "${covered.sumOf { summaryOf(it).photos }} photos"
                else photosLine(covered.sumOf { counted[it.id] ?: 0 }, summaryOf(album).photos)
                val contents = "${kids.count(::kept)} of ${kids.size} albums · $photos"
                ListEntry.Row(album, depth, true, ancestors, contents, covered.map { it.id })
            }
            emit(kids, depth + 1, ancestors + album.id, narrowed = !whole)
            out += ListEntry.End(album.id, ancestors)
        }
    }

    emit(children[root].orEmpty(), depth = 0, ancestors = emptyList(), narrowed = matches != null)
    return out
}
