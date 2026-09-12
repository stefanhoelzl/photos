package net.stho.photos.app

import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album
import net.stho.photos.catalog.ObjectId

/**
 * What one album's strip draws.
 *
 * The whole cache vocabulary is four readings of a single mark, and they are exactly the four
 * combinations this type can be in:
 *
 * | strip | here |
 * |---|---|
 * | grey | [heldBytes] is 0 |
 * | part blue | 0 < [heldBytes] < [totalBytes] |
 * | part blue, pulsing | …and [moving] |
 * | full green | [complete] |
 *
 * There is no caption to keep in step, because there is no caption: the row says what the album
 * *contains* and the strip says everything about the cache.
 */
public data class AlbumCache(
    public val heldBytes: Long,
    public val totalBytes: Long,
    /** A worker is fetching one of this album's blobs right now — the only thing that pulses. */
    public val moving: Boolean,
) {
    /** How much of the strip is filled. Zero when the album has nothing to fetch at all. */
    public val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (heldBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)

    /** Every blob is on disk, so the whole album works offline. An empty album is not complete. */
    public val complete: Boolean get() = totalBytes > 0L && heldBytes >= totalBytes

    public val empty: Boolean get() = heldBytes <= 0L

    public companion object {
        public val nothing: AlbumCache = AlbumCache(0L, 0L, moving = false)
    }
}

/**
 * Per-album cache state, with containers aggregating their children.
 *
 * §2 makes an album hold sub-albums XOR photos, so a container owns no blobs of its own and
 * would otherwise always read as empty — which is wrong twice over: its row would say nothing,
 * and asking it to download would have nothing to do. Rolling its descendants up is what makes
 * "download Iceland" mean the eight sub-albums underneath, which is how a person thinks about
 * a trip.
 *
 * Pure, and takes plain collections rather than a reader, so the rollup — the part that can be
 * wrong — is exercisable with three maps and no database.
 */
public fun cacheByAlbum(
    albums: List<Album>,
    blobs: Map<Uuid, List<BlobRef>>,
    held: Set<ObjectId>,
    active: Set<ObjectId>,
): Map<Uuid, AlbumCache> {
    val children = albums.groupBy { it.parent }
    val cache = mutableMapOf<Uuid, AlbumCache>()

    // Depth-first with an explicit visited set: §4 surfaces an album whose parent resolved to
    // nothing rather than dropping it, so the "tree" can contain a cycle only if a shard lies —
    // and a stack overflow is a poor way to find that out.
    val visiting = mutableSetOf<Uuid>()

    fun of(album: Album): AlbumCache {
        cache[album.id]?.let { return it }
        if (!visiting.add(album.id)) return AlbumCache.nothing
        // Media only. A pack is fetched for every album whether or not anyone asked, so
        // counting it would put a sliver of blue on every strip in the list and "grey means
        // nothing is held" would never be true of anything. The strip is about what the person
        // chose to keep; the pack is about the grid opening at all, and Settings totals it
        // separately.
        // One pass, no intermediate lists. This runs for every album on every queue event
        // while a download is in flight -- ~10,600 refs several times a second -- and the
        // filtered copies it used to allocate were pure garbage on the frame the strip is
        // trying to animate on.
        var heldBytes = 0L
        var totalBytes = 0L
        var moving = false
        for (ref in blobs[album.id].orEmpty()) {
            if (ref.kind != BlobKind.Media) continue
            totalBytes += ref.bytes
            if (ref.id in held) heldBytes += ref.bytes
            if (!moving && ref.id in active) moving = true
        }
        for (child in children[album.id].orEmpty()) {
            val below = of(child)
            heldBytes += below.heldBytes
            totalBytes += below.totalBytes
            moving = moving || below.moving
        }
        visiting -= album.id
        return AlbumCache(heldBytes, totalBytes, moving).also { cache[album.id] = it }
    }

    albums.forEach(::of)
    return cache
}

/**
 * Which controls an album's row offers, given its state.
 *
 * Derived rather than stored, so the row and the revealed actions can never disagree about what
 * an album is doing.
 */
public fun actionsFor(cache: AlbumCache, wanted: Boolean): List<CacheAction> = when {
    // §10's zero-photo album: emptying a directory leaves one, and it must render. There is
    // nothing to fetch and nothing to remove, so it offers nothing -- a Download that cannot
    // do anything is worse than no control at all.
    cache.totalBytes <= 0L -> emptyList()
    cache.complete -> listOf(CacheAction.Clear)
    wanted -> listOf(CacheAction.Pause, CacheAction.Clear)
    cache.empty -> listOf(CacheAction.Download)
    else -> listOf(CacheAction.Download, CacheAction.Clear)
}

/** Icon-only, and which ones appear is the album's state. */
public enum class CacheAction { Download, Pause, Clear }
