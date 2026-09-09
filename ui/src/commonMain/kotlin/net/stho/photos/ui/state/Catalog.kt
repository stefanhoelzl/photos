package net.stho.photos.ui.state

import net.stho.photos.catalog.Album
import net.stho.photos.model.PhotoRow
import kotlin.uuid.Uuid

/**
 * The read side of the catalog, as the app needs it.
 *
 * A port for the first of §7's three reasons — it needs a fake to be testable. The state tier
 * must be exercisable with no SQLite, no cache directory and no zone, and `CatalogReader` is a
 * class over an open database rather than an interface.
 */
public interface Catalog {
    /** Direct children of [parent]; the root when it is null (§2's real hierarchy). */
    public fun albums(under: Uuid?): List<Album>

    /** Substring, case- and diacritic-insensitive, over album names alone (§3). */
    public fun search(text: String): List<Album>

    /** One album's photos, oldest first, undated last — §3's order, straight from the index. */
    public fun photos(inAlbum: Uuid): List<PhotoRow>

    /** One album by id, for a screen that was reached by id rather than by row. */
    public fun album(id: Uuid): Album?

    /**
     * What the catalog holds, for §6's Settings row.
     *
     * Read from the merged DB rather than taken from the last sync's report: a run that
     * changed nothing rebuilds nothing and so reports no counts, which is how that row came
     * to read "0 · 0 photos" while the library was plainly there.
     */
    public fun totals(): Totals
}

/** What a sync run does, from the state tier's point of view. */
public interface Syncer {
    /** [onProgress] is called as shards land, so a first run can show something truthful. */
    public suspend fun sync(onProgress: (fetched: Int, total: Int) -> Unit): SyncOutcome
}

/**
 * Deliberately not `SyncReport`.
 *
 * The domain's report says what happened to the *zone*; this says what the screen should do,
 * which is a smaller thing. Keeping them apart is what lets §1's "name the status and the
 * cause" rule live in one place instead of being re-derived per call site.
 */
/** Albums and photos in the catalog as it stands. */
public data class Totals(val albums: Int, val photos: Int)

public sealed interface SyncOutcome {
    public data class Succeeded(val albums: Int, val photos: Int) : SyncOutcome

    public data class Failed(val notice: Notice) : SyncOutcome
}
