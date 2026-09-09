package net.stho.photos.catalog

/**
 * Where an album is in its lifecycle, and so who owns it (§7).
 *
 * The three states exist because an album can reach the zone from two directions. The laptop
 * derives and uploads in one pass, so its albums are born [ENCODED] and never occupy another
 * state. The phone cannot: it uploads over a background session that survives being killed,
 * so its albums are visible in the zone long before they are complete, and complete long
 * before anything has encoded them.
 *
 * Stored as the enum's [wire] text rather than as a number. Nothing computes with the value —
 * every rule that reads it is an equality test — so the ordering a number would buy is unused,
 * and a shard opened in a shell reads as itself.
 */
public enum class AlbumState(public val wire: String) {
    /**
     * The phone has written the shard and is still uploading the objects it names.
     *
     * The shard is written **first**, which is the opposite of what a catalog usually wants:
     * it means the zone briefly holds a shard pointing at objects that do not exist yet. That
     * is deliberate. The alternative — shard last — leaves blobs that no catalog references,
     * indistinguishable from debris, which is the entire reason the sweep needs a seven-day
     * age floor. A shard in this state *names* its objects, so the sweep can skip them on
     * evidence rather than on age, and collect everything else immediately.
     *
     * The CLI does not touch an album in this state: it does not pull it, and it does not
     * treat its blobs as orphans. An album left here past the sweep's floor is a failed
     * upload — the presigned PUTs it was uploading through have expired by then, so it
     * provably cannot still finish — and is deleted, shard first.
     */
    UPLOADING("uploading"),

    /**
     * The phone has finished. Every object the shard names is in the zone, at full quality,
     * and nothing has encoded them yet.
     *
     * This is the only state the CLI pulls from. It claims the album by writing `source_path`
     * — leaving the state alone — then downloads, encodes and finally writes [ENCODED].
     */
    UPLOADED("uploaded"),

    /**
     * The laptop owns this album: the library holds its files and the zone holds derivatives
     * at `album_info.encoding_version`.
     *
     * **The deletion rule is gated on this state.** Only here does a missing file mean the
     * photo was deleted; in the other two a directory that is absent or half-filled means the
     * pull has not run or did not finish, which is why an interrupted pull cannot be mistaken
     * for someone emptying an album.
     */
    ENCODED("encoded"),
    ;

    public companion object {
        private val byWire = entries.associateBy(AlbumState::wire)

        /** For reading the column back; an unknown value is null rather than a default. */
        public fun of(wire: String): AlbumState? = byWire[wire]
    }
}
