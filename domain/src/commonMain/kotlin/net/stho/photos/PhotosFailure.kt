package net.stho.photos

import kotlin.uuid.Uuid

/**
 * A failure that ends the run.
 *
 * DESIGN §1 splits failures in two, and the types keep them apart: what stops a run is thrown,
 * what stops one photo or one album is returned as data. Everything under this root is the
 * first kind.
 *
 * [message] is never opaque — §1 requires a failure to name its cause and say what to do about
 * it, so every subclass composes a sentence a person can act on, and it is non-null here for
 * exactly that reason.
 */
public sealed class PhotosFailure(override val message: String) : Exception(message)

/**
 * An HTTP failure from the storage API.
 *
 * §1 requires that sync and upload failures report the HTTP status and name the cause —
 * *"Sync failed: 403 Forbidden — log out and check the password"* — never an opaque error.
 * [message] composes the first half of that sentence and [userMessage] the whole of it, both in
 * one place, so the wording cannot drift between the CLI and the app.
 */
public class S3HttpFailure(
    public val status: Int,
    /** The `<Code>` element from S3's XML error body, when there is one. */
    public val code: String? = null,
    /**
     * The `<Message>` element from that body.
     *
     * Called `detail` rather than `message`, which [PhotosFailure] already owns and which has
     * to stay the whole composed sentence.
     */
    public val detail: String? = null,
    public val key: String? = null,
    public val requestId: String? = null,
) : PhotosFailure(describe(status, code, detail, key)) {

    /** Actionable one-liner for the UI, following §1's example wording. */
    public val userMessage: String
        get() = when (status) {
            401, 403 -> "$message — log out and check the password"
            404 -> "$message — the object is missing from the storage zone"
            412 -> "$message — the object changed since it was read"
            429 -> "$message — too many requests, try again shortly"
            in 500..599 -> "$message — the storage service is failing, try again later"
            else -> message
        }
}

private fun describe(status: Int, code: String?, detail: String?, key: String?): String =
    buildString {
        append(status).append(' ').append(reasonPhrase(status))
        if (code != null) append(" — ").append(code)
        if (detail != null && detail != code) append(": ").append(detail)
        if (key != null) append(" (").append(key).append(')')
    }

private fun reasonPhrase(status: Int): String = when (status) {
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    409 -> "Conflict"
    412 -> "Precondition Failed"
    416 -> "Range Not Satisfiable"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    504 -> "Gateway Timeout"
    else -> "HTTP $status"
}

/**
 * A 2xx response whose body could not be understood.
 *
 * The LIST parse is the dangerous one: §4's diff reads a missing key as a deleted album, so a
 * response that cannot be trusted must never come back as "no objects".
 */
public class MalformedResponseFailure(why: String) : PhotosFailure("malformed response: $why")

/**
 * A string that is not a storage URL.
 *
 * One URL carries endpoint, signing region and zone (§1) and the zone doubles as the access key
 * ID, so a parsing slip breaks every signature. The cases are distinct types rather than one
 * message because the setup screen and the CLI both want to say precisely what is wrong.
 */
public sealed class StorageUrlFailure(message: String) : PhotosFailure(message) {
    public class NotAUrl(public val text: String) : StorageUrlFailure("not a valid URL: $text")

    public class MissingScheme :
        StorageUrlFailure("missing scheme — the URL must start with https://")

    public class UnsupportedScheme(public val scheme: String) :
        StorageUrlFailure("unsupported scheme $scheme — must be http or https")

    public class MissingHost : StorageUrlFailure("missing host")

    public class MissingZone :
        StorageUrlFailure("missing storage zone — expected https://<host>/<zone>")
}

/**
 * A shard that cannot be read.
 *
 * Distinguishable cases rather than one message, because §3 turns on the difference: the CLI
 * reconciles a local library against the zone, and a shard it reports as *absent* gets
 * re-uploaded as a new album — with UUID keys nothing collides to stop the duplicate. So
 * "written by a newer schema" has to be tellable from "this file is not a shard" and from
 * "SQLite is unwell".
 */
public sealed class ShardFailure(message: String) : PhotosFailure(message) {

    /**
     * The shard was written by a newer schema than this build understands.
     *
     * Not a corruption: the caller skips the album, probes it for §3's two permanently stable
     * columns, and reports it — never as absent.
     */
    public class UnsupportedVersion(
        public val found: Int,
        public val supported: Int,
    ) : ShardFailure("shard schema version $found is newer than this build supports ($supported)")

    /** No `album_info` row, so the file is not a shard at all. */
    public class MissingAlbumInfo :
        ShardFailure("shard has no album_info row")

    /** A column held something its type says it cannot — a uuid that is not one, say. */
    public class Malformed(public val detail: String) :
        ShardFailure("malformed shard: $detail")
}

/**
 * A shard the LIST named, which then could not be brought to disk.
 *
 * §4's diff reads a missing key as a deleted album, so a shard that listed and then failed to
 * arrive must end the run rather than quietly become an absence.
 */
public class ShardUnavailableFailure(public val albumId: Uuid) :
    PhotosFailure("shard $albumId listed but could not be fetched")
