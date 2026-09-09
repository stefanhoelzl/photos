package net.stho.photos.ingest

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.io.files.Path
import net.stho.photos.ports.Ids

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
    /** Where derivatives are staged before upload. Removed when the run ends. */
    public val workRoot: Path,
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
     * Restricts the run to albums whose source path contains this, case-insensitively. It scopes
     * deletions and pulls as well as uploads: a scoped run that deleted everything outside its
     * scope would be a trap.
     */
    public val albumFilter: String? = null,
    /** Plan and print, change nothing. The only safety surface there is (§7). */
    public val dryRun: Boolean = false,
    /**
     * How old an unreferenced blob must be before the sweep may delete it.
     *
     * Seven days is not a guess: presigned URLs live at most 7 days (§1) and §8's background
     * uploads run against them, so a blob older than that cannot belong to an upload that can
     * still complete. Below it, an unreferenced blob is indistinguishable from one the phone is
     * uploading right now.
     */
    public val sweepAge: Duration = 7.days,
) {
    public val jobs: Int = jobs.coerceAtLeast(1)
    public val uploadJobs: Int = uploadJobs.coerceAtLeast(1)

    public companion object {
        /**
         * A fresh staging directory under [temporaryDirectory]. One per run, so debris from a run
         * that died is a directory a tmp-cleaner can see rather than something to reason about.
         *
         * The directory is passed in rather than read from `$TMPDIR` here, for the same reason
         * [Credentials] takes the environment as data: the domain needs a few values at startup,
         * not a live query.
         */
        public fun newWorkRoot(temporaryDirectory: Path, ids: Ids): Path =
            Path(temporaryDirectory, "photos-cli-${ids.next().toString().take(8)}")
    }
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
