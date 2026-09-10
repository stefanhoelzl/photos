package net.stho.photos.ingest

import kotlin.time.Duration
import net.stho.photos.catalog.AlbumState
import kotlin.time.Duration.Companion.days
import kotlinx.io.files.Path

/**
 * Everything one run needs to know, resolved before it starts.
 *
 * A value rather than a pile of parameters so the CLI stays what §7 says it is — argument parsing
 * and wiring — and every rule that matters is reachable from a test that never builds a command
 * line.
 *
 * Not a `data class`, because [jobs] and [uploadJobs] are clamped rather than stored as given.
 */
public class IngestConfig(
    /**
     * The master copy. The run is read-only on it, apart from pulling down albums the laptop has
     * never owned (§7).
     */
    public val libraryRoot: Path,
    /**
     * Holds `sync_state.db` and `shards/`. Derived and deletable: losing it costs one re-fetch of
     * every shard.
     *
     * There is no `defaultCacheRoot` here: that is `Paths.cacheRoot`, the port §7 already
     * declares for "where this device keeps things", and its XDG rules belong in the Linux
     * adapter rather than in a second copy under the domain.
     */
    public val cacheRoot: Path,
    /**
     * Encoder workers. One per core, measured at 0.43 s/photo wall on 16 (§7).
     *
     * No default: the core count is a platform question, and reaching for it from the domain is
     * the third of §7's three reasons a seam becomes a port. The CLI passes it.
     */
    jobs: Int,
    /**
     * Upload connections. **One**, because §9 measured 1.9 MB/s on one stream against 1.5 MB/s on
     * eight — parallel uploads are slower, not faster. The knob is here for a different link, not
     * for this one.
     */
    uploadJobs: Int = 1,
    /**
     * Delete connections. **Sixty-four**, and the opposite reasoning to [uploadJobs]: a delete
     * carries no bytes, so it is not competing for the upstream link — it is one round trip to
     * Frankfurt and back, and round trips overlap.
     *
     * Measured against the live zone while emptying it: a single delete costs **~1.4 s**, and
     * 64 in flight sustained **~45/s**. Serially that is 34,000 blobs in about thirteen hours,
     * which is what a profile bump orphans (§5) against a three-hour import. The retry policy
     * covers 429 and 5xx, so a server that dislikes the rate says so and the run backs off.
     */
    deleteJobs: Int = 64,
    /**
     * Restricts the run to albums whose source path contains this, case-insensitively. It scopes
     * deletions and pulls as well as uploads: a scoped run that deleted everything outside its
     * scope would be a trap.
     */
    public val albumFilter: String? = null,
    /** Plan and print, change nothing. The only safety surface there is (§7). */
    public val dryRun: Boolean = false,
    /**
     * How long an album may sit at [AlbumState.UPLOADING] before it counts as abandoned.
     *
     * Seven days is not a guess: presigned URLs live at most 7 days (§1) and §8's background
     * uploads run against them, so past that the upload provably cannot finish.
     *
     * It no longer gates garbage collection. §8 names every blob an upload will write before
     * writing any of them, so an unreferenced blob cannot belong to something in flight and is
     * collected at once however new (§2). Liveness and garbage stopped sharing this knob.
     */
    public val sweepAge: Duration = 7.days,
) {
    public val jobs: Int = jobs.coerceAtLeast(1)
    public val uploadJobs: Int = uploadJobs.coerceAtLeast(1)
    public val deleteJobs: Int = deleteJobs.coerceAtLeast(1)

    /**
     * Where derivatives are staged before upload. Emptied when the run starts and again when it
     * ends.
     *
     * Under [cacheRoot] rather than `$TMPDIR`, and that is the whole fix for a run staging video
     * transcodes in RAM: `/tmp` is tmpfs on a normal Linux desktop, so a 39-hour import used to
     * hold every transcode in memory until its upload returned. It also buys the cleanup rule.
     * The run lock lives in [cacheRoot], so a run that holds it is the only run that can be using
     * this directory — which makes anything found here at startup debris from a run that died,
     * with no pid to check and no age to guess at. `finally` cannot be the only cleanup: `SIGKILL`
     * skips it, and an out-of-memory kill is exactly how a run staging into RAM ends.
     *
     * Derived rather than passed in, because a caller that could point it somewhere else could
     * point two runs at one directory, and the lock that makes emptying it safe would not follow.
     */
    public val workRoot: Path = Path(cacheRoot, "work")
}

/**
 * Process exit codes (§7).
 *
 * `1` and `3` are both failures to `OnFailure=`, but they say different things to a person reading
 * the journal: `1` means the run finished and the zone changed, `3` means it refused to start and
 * the zone is untouched.
 */
public object ExitCode {
    public const val CLEAN: Int = 0
    public const val COMPLETED_WITH_FAILURES: Int = 1
    public const val USAGE: Int = 2
    public const val ABORTED: Int = 3

    /**
     * `EX_TEMPFAIL`. Not now, rather than not working. Two things say it: the keyring is still
     * locked because nobody has logged in yet, and another sync already holds the run lock. The
     * systemd unit sets `SuccessExitStatus=75` so neither reaches `OnFailure=` — both are the
     * ordinary state of a machine that reboots and an import that outlasts the hour between timer
     * firings.
     */
    public const val DEFERRED: Int = 75
}
