package net.stho.photos.app

import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album

/**
 * The album list's state, over the catalog: what both apps' models share (§6, §11).
 *
 * The phone's model adds the back stack, the sync, the cache and the upload; the desktop
 * viewer's adds a selection. What neither adds is here — the whole tree, each album's rollup, the
 * calendar's numbers, and the rule that turns a sort, a search and a date range into rows — so
 * the list reads the same way on both, and is right or wrong in one place.
 *
 * Holds what it reads until [refresh]: the tree and the rollups change only when the catalog
 * does, and re-walking the hierarchy per keystroke was one SQLite open per album.
 */
public class AlbumListing(private val catalog: Catalog) {

    /**
     * Every album, flat, parents before children.
     *
     * Re-walking the hierarchy meant one SQLite open per album, and the phone's cache view is
     * recomputed whenever a blob lands — so on a first run that was ~120 database opens several
     * hundred times over, on the same dispatcher the download workers use. The workers starved
     * and the queue stopped draining entirely. The tree only changes when the catalog does.
     */
    public var tree: List<Album> = emptyList()
        private set

    /** Each album's sub-album and photo counts and latest date, summed up the tree. */
    public var summaries: Map<Uuid, AlbumSummary> = emptyMap()
        private set

    /** Photos per day across the library: read when the calendar first needs them, and kept until the catalog changes. */
    private var days: Map<Day, Int>? = null

    /** Re-reads what a rebuild can change. */
    public fun refresh() {
        tree = allAlbums()
        summaries = summariesByAlbum(tree)
        // A rebuild can change any day's number: counted again when next needed.
        days = null
    }

    /** One `GROUP BY` over an index, kept until the catalog changes. */
    public fun photosPerDay(): Map<Day, Int> = days ?: catalog.photosPerDay().also { days = it }

    /** The calendar sheet's days and months (§6). */
    public fun calendar(): CalendarUi = CalendarUi.of(photosPerDay())

    /**
     * Whether a photo was taken in [range]. A range with none cannot be applied: a list filtered
     * down to nothing only looks broken.
     */
    public fun hasPhotosIn(range: DateRange): Boolean =
        photosPerDay().any { (day, photos) -> day in range && photos > 0 }

    /** The albums a search or a date range matches, by id; null with neither. */
    public fun matching(query: String, range: DateRange?): Set<Uuid>? =
        range?.let { catalog.photosIn(it).keys }
            ?: query.takeIf { it.isNotBlank() }?.let { text -> catalog.search(text).mapTo(mutableSetOf()) { it.id } }

    /**
     * The list under [parent] — the library when null — every level of it, in [sort].
     *
     * A range's matches carry their counts, which every line then reads in place of the whole
     * album's; a search's are names alone. [narrowing] says whether either applies here at all:
     * only the album list is narrowed (§6), never a container's screen, which has no field.
     * [within] narrows further, to the albums the desktop's map shows (§11) — a match outside it
     * is kept off the list exactly as a non-match is.
     */
    public fun rows(
        sort: AlbumSort,
        query: String,
        range: DateRange?,
        parent: Uuid? = null,
        narrowing: Boolean = true,
        within: Set<Uuid>? = null,
    ): Listed {
        val counted = range?.takeIf { narrowing }?.let(catalog::photosIn)
        val searched = counted?.keys
            ?: query.takeIf { narrowing && it.isNotBlank() }
                ?.let { text -> catalog.search(text).mapTo(mutableSetOf()) { it.id } }
        val matches = if (within == null) searched else searched?.intersect(within) ?: within
        val rows = albumRows(tree, parent, sort, summaries, matches, counted)
        return Listed(
            rows = rows,
            matched = matches?.let { ids -> rows.count { it is ListEntry.Row && it.album.id in ids } } ?: 0,
        )
    }

    /** Every album beneath [id], at any depth — over the cached tree, not the database. */
    public fun descendantsOf(id: Uuid): Set<Uuid> {
        val children = tree.groupBy { it.parent }
        val out = mutableSetOf<Uuid>()
        fun walk(parent: Uuid) {
            children[parent].orEmpty().forEach { if (out.add(it.id)) walk(it.id) }
        }
        walk(id)
        return out
    }

    private fun allAlbums(): List<Album> {
        val out = mutableListOf<Album>()
        fun walk(parent: Uuid?) {
            for (album in catalog.albums(under = parent)) {
                out += album
                walk(album.id)
            }
        }
        walk(null)
        return out
    }

    /** One list: its rows, and how many albums a search or a range matched on it (zero with neither). */
    public data class Listed(val rows: List<ListEntry>, val matched: Int)
}
