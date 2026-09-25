package net.stho.photos.ports

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.SharedFlow
import net.stho.photos.exif.ExifTags
import net.stho.photos.faces.DetectedFace
import net.stho.photos.pipeline.Derivatives
import net.stho.photos.pipeline.MediaFormat
import net.stho.photos.pipeline.MediaItem
import net.stho.photos.pipeline.PipelineEvent
import net.stho.photos.pipeline.VideoInfo

/**
 * What the domain needs from the world (DESIGN §7).
 *
 * A seam becomes a port for one of three reasons and no other — it needs a fake to be
 * testable, its implementation differs by platform, or the domain would otherwise reach for it
 * *statically* rather than receive it. Purity is not a reason, which is why neither the SQL
 * driver nor the HTTP client appears here: SQLDelight and Ktor are already those abstractions,
 * and both ship test doubles of their own. Nor does a clock — `kotlin.time.Clock` is one. Nor,
 * after the fact, does the run's own reporting: §7's report turned out to be `IngestReport`,
 * plain data a test asserts on directly, so the port that was going to carry it had no caller
 * and no fake to be. Rendering it is the CLI's business and lives there.
 */

/**
 * What each platform's imaging library provides.
 *
 * A contract rather than an implementation: ImageIO/AVFoundation on Apple platforms,
 * libheif/ffmpeg behind the C shim on Linux. Extraction lives behind the port because both
 * platforms already ship a library that reads EXIF and a hand-written container parser would
 * only add a third opinion. *Interpretation* does not — that is `ExifMapper`, shared, so a
 * phone and a laptop cannot derive different dates from the same file.
 */
public fun interface ImageBackend {
    /**
     * Raw tags for one file, normalised to EXIF/TIFF tag names.
     *
     * Throws [MediaUnreadable] when this file cannot be read — and nothing else for that case.
     * Callers treat missing tags as a courtesy withheld, not as a failed run.
     */
    public fun rawTags(path: String): ExifTags
}

/**
 * One file could not be read. Per-item and recoverable, and deliberately **not** a
 * `PhotosFailure`.
 *
 * It exists so the two error tiers survive the port boundary. Without it a caller in the domain
 * has nothing narrow to catch — the adapter's own exception type is not visible here — so it
 * must catch `Exception`, which would silently swallow a genuinely fatal `PhotosFailure` and
 * report it as "this file has no tags". Declaring the failure on the port is what lets the
 * catch be narrow.
 */
public class MediaUnreadable(
    message: String,
    override val cause: Throwable? = null,
) : Exception(message)

/**
 * There is no `Clock` port: `kotlin.time.Clock` already is one.
 *
 * The reason a clock has to be injected at all still holds — three rules depend on "now" (the
 * sweep's seven-day floor, presigned-URL expiry, an album's `added_at`) and a test that cannot
 * move time cannot exercise any of them — but the stdlib supplies the interface, `Clock.System`
 * the production implementation, and a test its own. Declaring our own would only shadow it.
 */

/**
 * Freshly minted identity.
 *
 * Ingest mints object UUIDs in a dozen places, and a run whose ids are unpredictable cannot be
 * asserted against.
 */
@OptIn(ExperimentalUuidApi::class)
public fun interface Ids {
    public fun next(): Uuid
}

/**
 * The credential store: the Secret Service over D-Bus on Linux, the Keychain on iOS (§1).
 *
 * [read] distinguishes three outcomes rather than two, because conflating them is what §1's
 * exit codes turn on: a value, no value stored, or *could not look* — a locked collection, no
 * session bus, nothing answering. The last is a deferral (75), the middle is a real error (3).
 */
public interface Keyring {
    public fun read(field: String): KeyringRead
    public fun write(field: String, secret: String)
    public fun remove(field: String)
}

public sealed interface KeyringRead {
    public data class Found(val secret: String) : KeyringRead
    public data object Absent : KeyringRead
    public data class Unavailable(val reason: String) : KeyringRead
}

/**
 * Where this device keeps things — XDG directories on Linux, the app container on iOS.
 *
 * §4's on-device layout lives under [cacheRoot]: `lock`, `sync_state.db`, `shards/`, `merged.db`,
 * `blobs/` and `work/`.
 */
public interface Paths {
    public val cacheRoot: String
    public val configRoot: String
}

/**
 * Stops two syncs running at once (§7).
 *
 * A port for the caller's sake rather than its own: the real `flock` is faithful and its own
 * tests need no fake, but acquiring it from a static factory would make "another run holds it"
 * indistinguishable in a test from "the cache directory is not writable" — and those take
 * different exit paths.
 */
public interface RunLock {
    /**
     * Takes the lock, or says who has it. Never waits: a run that queued behind another would
     * be an hourly timer piling up, which is the thing the lock exists to prevent.
     *
     * Returning a *result* rather than null is what lets §7's refusal name the holder — "another
     * sync is already running (pid 1234)" rather than a bare "already running". The pid is the
     * only thing that makes the message actionable, so it belongs on the contract rather than on
     * one implementation.
     */
    public fun acquire(): LockAttempt
}

public sealed interface LockAttempt {
    public data class Acquired(val handle: LockHandle) : LockAttempt

    /** [pid] is null when the holder's lock file could not be read — rare, and not fatal. */
    public data class HeldBy(val pid: Int?) : LockAttempt
}

public interface LockHandle : AutoCloseable

/**
 * A request to stop, from outside the process.
 *
 * A port for the second of the three reasons: `SIGINT`/`SIGTERM` and a self-pipe are what this
 * means on Linux, and nothing about them would survive a move to another platform. Like [RunLock]
 * it is used by the composition root rather than by the domain — nothing under `ingest` knows it
 * can be interrupted, because it does not need to: cancelling the scope is what unwinds a run,
 * and the `finally` that empties the work directory is already there.
 *
 * It cannot make a run survivable in general — `SIGKILL` is not deliverable to a handler, and an
 * out-of-memory kill is exactly that — which is why it sits beside the startup reclaim rather
 * than instead of it.
 */
public interface Interrupts : AutoCloseable {
    /**
     * Suspends until the run is asked to stop.
     *
     * The second request is not this port's business: the default disposition is restored the
     * moment the first arrives, so a second `^C` kills the process outright. That is deliberate
     * and it is the only escape hatch there is — cancellation cannot interrupt a transcode that
     * is already inside the encoder, so a clean stop waits for it.
     */
    public suspend fun awaitInterrupt(): Interrupted

    /**
     * Dies the way an unhandled signal would have: default disposition, signal re-raised at
     * ourselves.
     *
     * Called once the run has unwound, so that what a shell, a parent process and `systemd` see
     * is a process killed by a signal rather than one that chose an exit code. `ExitCode` (§7)
     * therefore needs no member for "interrupted" — an interrupted run did not exit.
     */
    public fun surrender(interrupted: Interrupted): Nothing
}

/** Which signal asked, kept so that [Interrupts.surrender] re-raises the same one. */
@JvmInline
public value class Interrupted(public val signal: Int)

/**
 * What a file *is*, and what a video container says about it — both from a bounded read.
 *
 * A port for the second of the three reasons: sniffing is libheif/ffmpeg behind the C shim here
 * and `CGImageSource`/`AVAsset` on iOS, and the classifier that consumes it is shared. It is
 * kept apart from [ImageBackend] because that one is EXIF extraction and nothing else; a probe
 * answers a question about the *container*, before any tag is read.
 */
public interface MediaProbe {
    /**
     * Reads a bounded header prefix, never the whole file — which is what makes pointing the
     * scan at a 2.66 GB SQLite database cost one small read.
     */
    public fun sniff(path: String): MediaFormat

    /**
     * Null when the container cannot be read; a file the pipeline cannot probe is skipped.
     * Throws [MediaUnreadable] only for a failure that is about this file.
     */
    public fun videoInfo(path: String): VideoInfo?
}

/**
 * The derivative pipeline: one [MediaItem] in, everything §5 stores out.
 *
 * [derive] is synchronous and safe to call from many threads at once. It owns no worker pool —
 * ingest owns that, because only ingest can interleave encoding, which is CPU-bound and
 * parallel, with §9's uploads, which are link-bound and deliberately serial.
 *
 * Progress arrives on [events] rather than through a callback parameter, so [derive]'s
 * signature stays a plain function and every worker publishes into the one flow. A
 * `SharedFlow` never completes; a collector ends by cancelling its own scope, which is why the
 * port has no `finish()` to call.
 */
public interface Pipeline {
    public val events: SharedFlow<PipelineEvent>

    public fun derive(item: MediaItem): Derivatives

    /**
     * The faces in one photograph, decoded afresh — §12's backfill, for photos derived before
     * faces were looked for. A photo being derived gets its faces in [Derivatives.faces] instead,
     * from the decode it already has.
     *
     * [sensitive] looks harder — a lower confidence floor and a larger detection size — for a photo
     * where a person drew a box around a face the ordinary pass missed (§12).
     *
     * Null when this pipeline was built without face models. Throws [MediaUnreadable] for a file
     * that cannot be decoded. Safe to call from many threads at once, like [derive].
     */
    public fun findFaces(item: MediaItem, sensitive: Boolean = false): List<DetectedFace>? = null
}

/**
 * How this platform opens a SQLite file (§3).
 *
 * A port for the second of the three reasons above: SQLDelight ships a driver per platform —
 * SQLiter on Kotlin/Native, JDBC on the JVM — and they configure a database differently enough
 * that neither can be named in shared code. What is *not* here is any query layer: SQLDelight's
 * generated API is that, and this contract ends at handing back an open [SqlDriver].
 *
 * > An earlier draft made this `expect`/`actual` — `domain/build.gradle.kts` still predicts it.
 * > It is a port instead so the repo has exactly one mechanism for a platform difference, and
 * > because `expect`/`actual` binds per compilation target: a test could never substitute one.
 *
 * The two callers' needs differ, which is why [journal] is on the contract. A shard and a
 * thumbnail pack are *objects* that get uploaded, so they are `DELETE` — WAL would leave half
 * of one in a sidecar no PUT carries. The two on-device databases require `WAL`, and §4 is
 * explicit about why: without it the 1–3 s rebuild blocks the album list that is on screen.
 */
public fun interface SqlDrivers {
    /**
     * Opens [path], which the caller has already created a directory for and checked the
     * existence of — those rules are the catalog's, not a platform's.
     *
     * [creating] false means *read what is there*: the driver must not run the schema, and must
     * not check `user_version` either, because the file may carry a `schema_version` this build
     * has never seen (§3's forward-compatibility rule).
     */
    public fun open(
        path: String,
        schema: SqlSchema<QueryResult.Value<Unit>>,
        creating: Boolean,
        journal: Journal,
    ): SqlDriver
}

/** The two journal modes §3 and §4 ask for, named without reference to either driver. */
public enum class Journal { DELETE, WAL }
