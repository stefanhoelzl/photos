package net.stho.photos.ports

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.SharedFlow
import net.stho.photos.exif.ExifTags
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
 * and both ship test doubles of their own. Nor does a clock — `kotlin.time.Clock` is one.
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
 * §4's on-device layout lives under [cacheRoot]: `sync_state.db`, `shards/`, `merged.db` and
 * `blobs/`.
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
    /** Returns null when another run holds the lock; throws when the lock cannot be attempted. */
    public fun acquire(): LockHandle?
}

public interface LockHandle : AutoCloseable

/**
 * Where a run says what it did.
 *
 * §7's report is a contract, not decoration: every unresolved condition is named on *every*
 * run until a person deals with it — unlocated albums, mixed folders, shards too new to read,
 * an ignore rule that matched nothing. Those are assertions worth writing, and asserting on a
 * recording fake beats asserting on captured stdout.
 */
public interface Reporter {
    public fun progress(message: String)
    public fun unresolved(condition: String, detail: String)
    public fun failed(subject: String, message: String)
}

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
 * `SharedFlow` never completes; a collector ends by cancelling its own scope, which is why
 * there is no counterpart to Swift's `finish()`.
 */
public interface Pipeline {
    public val events: SharedFlow<PipelineEvent>

    public fun derive(item: MediaItem): Derivatives
}
