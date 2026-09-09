package net.stho.photos.storage

import kotlin.time.Instant

/** One object as reported by HEAD or LIST. */
public data class S3Object(
    /** The key relative to the zone root, percent-decoded. */
    public val key: String,
    public val size: Long,
    public val etag: ETag? = null,
    public val lastModified: Instant? = null,
) {
    /**
     * bunny.net materialises implicit directory markers: writing `meta/Trips/Iceland.db` also
     * produces a zero-byte `meta/Trips/` key with no ETag, and LIST returns it (§2). The client
     * reports keys raw; §4's sync diff is what must skip these.
     */
    public val isDirectoryMarker: Boolean get() = key.endsWith("/")
}

/** Result of a conditional GET. */
public sealed interface GetResult {
    /** The server answered 304 — the caller's ETag is still current. */
    public data object NotModified : GetResult

    public data class Content(public val bytes: ByteArray, public val etag: ETag?) : GetResult {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Content && etag == other.etag && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + etag.hashCode()
    }
}

public val GetResult.asBytes: ByteArray?
    get() = (this as? GetResult.Content)?.bytes

/** Result of a PUT that carried `If-Match`. */
public sealed interface PutResult {
    public data class Written(public val etag: ETag?) : PutResult

    /**
     * The server answered 412 — the object changed since it was read, so the caller must
     * re-read and retry (§2's guarded single-owner shard rule).
     */
    public data object StaleETag : PutResult
}

public val PutResult.asETag: ETag?
    get() = (this as? PutResult.Written)?.etag
