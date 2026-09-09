package net.stho.photos.ports

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import net.stho.photos.exif.ExifTags

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
    /** Raw tags for one file, normalised to EXIF/TIFF tag names. */
    public fun rawTags(path: String): ExifTags
}

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
