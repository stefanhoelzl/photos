package net.stho.photos

import kotlin.uuid.Uuid
import net.stho.photos.ingest.ByteMismatch

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
 * The storage API could not be reached at all — DNS, TLS, a refused or dropped connection.
 *
 * Distinct from [S3HttpFailure], which means the zone answered and said no. Nothing answered
 * here, so there is nothing to correct and nothing to log out of: the honest reading is *not
 * now*, which is the same shape as a keyring that has not been unlocked yet, and it takes the
 * same exit code.
 *
 * It exists because the alternative is worse than a bad message. Ktor's curl engine raises a
 * plain `IllegalStateException`, which is outside this hierarchy — so before this type, an
 * offline run left the exit-code contract entirely and died on Kotlin/Native's uncaught handler
 * with `SIGABRT`, a core dump and sixteen frames of stack. On the hourly timer §7 is written
 * for, that is a page every hour for a laptop that is merely asleep or travelling.
 */
public class StorageUnreachableFailure(
    /** What the transport actually said, kept for the journal. */
    public val reason: String,
) : PhotosFailure("cannot reach the storage zone: $reason")

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

/**
 * `$LIBRARY_ROOT/.photosignore` exists and could not be read.
 *
 * *Absent* is a different thing and is not a failure here: it means no exclusions. *Unreadable*
 * is fatal, because the file existing means exclusions were intended, and proceeding without
 * them silently changes what gets uploaded — §7 already aborts a run on the byte-size mismatch
 * for the same reason. A photo manager's trash directory holds real, decodable photographs.
 */
public class IgnoreRulesUnreadableFailure(
    /** The `.photosignore` itself, not the library root. */
    public val path: String,
    public val reason: String,
) : PhotosFailure(
    "$path exists but could not be read: $reason. " +
        "Refusing to run — a file that exists means exclusions were intended, and " +
        "continuing without them is how deleted photos get uploaded.",
)

/**
 * The conditions under which a run refuses to change anything (exit 3).
 *
 * All three mean the same thing: the library is not in the state the catalog was built from, and
 * proceeding would write something irreversible on a false premise. They are structural guards
 * rather than error handling — §7 has no confirmation step and no undelete, so the only place a
 * mistaken premise can be caught is before the first write.
 *
 * Declared here rather than beside ingest because Kotlin requires a sealed class's subclasses to
 * share its package, and the two-tier split (§1) puts every run-fatal failure under
 * [PhotosFailure].
 */
public sealed class IngestAbort(message: String) : PhotosFailure(message) {

    /**
     * No readable `$LIBRARY_ROOT/.photosignore`.
     *
     * The file is the marker that says "this directory is a library root", which is what makes
     * unattended deletion safe: an unmounted disk is a bare mount point, and a mistyped root is
     * somebody else's directory — neither has one. A library that wants no exclusions writes an
     * empty file.
     *
     * Note that this is *stricter* than [net.stho.photos.library.readIgnoreRules], which reads an
     * absent file as "no exclusions". The distinction is deliberate: absent means "not a library
     * root" only to the tool that deletes things.
     */
    public class NotALibraryRoot(
        /** The library root, not the `.photosignore` itself. */
        public val root: String,
        public val detail: String,
    ) : IngestAbort("$root is not a library root: $detail")

    /**
     * A file's size no longer matches the row that describes it. §7 asserts images never change
     * on disk; a mismatch means the library broke that contract, so nothing is written and a
     * person is told, rather than the file being silently re-ingested.
     */
    public class FileChanged(
        public val mismatches: List<ByteMismatch>,
    ) : IngestAbort(describeMismatches(mismatches))

    /**
     * The cache directory cannot be locked at all — not "another run holds it", which is a
     * deferral, but "this cannot be attempted": the directory is unwritable, or gone.
     *
     * It lives here rather than in the adapter that raises it because `PhotosFailure` is sealed,
     * and Kotlin requires direct subclasses in the same module and package. Without it an adapter
     * has nowhere to put a tier-one failure and would have to throw something the domain cannot
     * name — which is the same boundary problem `MediaUnreadable` solves for the per-item tier.
     */
    public class CacheUnusable(
        public val path: String,
        public val detail: String,
    ) : IngestAbort("cannot lock $path: $detail")

    /**
     * A shard too new to read whose two stable columns could not be read either, so the folder it
     * claims is unknowable. Continuing would risk uploading that folder as a second album (§3).
     */
    public class UnidentifiableShard(public val detail: String) :
        IngestAbort("a shard could not be identified: $detail")

    /**
     * A face model could not be brought to disk, or arrived with the wrong bytes (§12).
     *
     * A broken install rather than a bad file: every run needs the models, so a run without them
     * refuses to start instead of syncing photographs and quietly leaving faces behind. Fetched
     * before anything is written, which is what makes it this tier.
     */
    public class FaceModelUnavailable(public val model: String, public val detail: String) :
        IngestAbort("face model $model is unavailable: $detail")
}

private fun describeMismatches(mismatches: List<ByteMismatch>): String =
    "${mismatches.size} file(s) changed on disk since they were ingested: " +
        mismatches.take(3).joinToString("; ")

/**
 * A credential that could not be resolved (§1).
 *
 * The cases are distinct because §7's exit codes turn on them: a keyring that cannot be reached
 * is a deferral (75, try again next hour), while a keyring that answered and holds nothing is a
 * real error (3).
 */
public sealed class CredentialFailure(message: String) : PhotosFailure(message) {

    public class MissingLibraryRoot :
        CredentialFailure("not a directory: pass --library-path, or run from inside the library")

    /**
     * The keyring could not be reached — no session bus, nothing owning
     * `org.freedesktop.secrets`, or a collection still locked because nobody has logged in yet.
     * Not a failure: exit 75 and try again next hour.
     */
    public class KeyringUnavailable(public val detail: String) :
        CredentialFailure("keyring unavailable: $detail")

    /** The keyring answered, and holds no such item. That is a real error. */
    public class NoSuchItem(
        public val service: String,
        public val field: String,
    ) : CredentialFailure(
        "nothing in the keyring for service $service, field $field. Store it with:\n" +
            "  photos-cli login\n" +
            "For development, run under secrets-env instead, which injects both from Proton Pass.",
    )

    /**
     * The keyring answered with something the Secret Service spec does not allow. Not a deferral
     * — waiting an hour will not change it — so it aborts like any other condition that stops a
     * run before it writes.
     */
    public class KeyringProtocol(public val detail: String) : CredentialFailure(detail)
}
