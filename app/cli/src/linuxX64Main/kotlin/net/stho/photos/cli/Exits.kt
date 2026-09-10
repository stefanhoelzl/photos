package net.stho.photos.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import net.stho.photos.CredentialFailure
import net.stho.photos.IgnoreRulesUnreadableFailure
import net.stho.photos.IngestAbort
import net.stho.photos.MalformedResponseFailure
import net.stho.photos.PhotosFailure
import net.stho.photos.S3HttpFailure
import net.stho.photos.ShardFailure
import net.stho.photos.ShardUnavailableFailure
import net.stho.photos.StorageUnreachableFailure
import net.stho.photos.StorageUrlFailure
import net.stho.photos.ingest.ExitCode
import net.stho.photos.ports.LockAttempt

/**
 * Every run-fatal failure as one of §7's five numbers.
 *
 * This is the contract the systemd unit is written against, not a convenience:
 * `SuccessExitStatus=75` is what keeps a laptop nobody has logged into yet, and an hourly firing
 * that lands inside a 39-hour import, out of `OnFailure=`. Getting a number wrong here turns "you
 * have not logged in yet" into a page, or hides a genuinely missing password behind an hourly retry
 * that can never succeed.
 *
 * Exhaustive over a sealed hierarchy on purpose: a failure added to the domain stops compiling here
 * rather than quietly arriving as somebody's default.
 *
 * The line between `3` and `1` is *whether the zone was touched*. Everything that resolves
 * credentials, or that guards the library before the first write, is `3` — the run refused to start
 * and the zone is exactly as it was. What can only be raised once the run is under way is `1` — it
 * finished, and something in the zone may have changed.
 */
internal fun PhotosFailure.exitCode(): Int = when (this) {
    // Could not *look*: no session bus, nothing owning org.freedesktop.secrets, or a collection
    // still locked because nobody has logged in. Not now, rather than not working.
    is CredentialFailure.KeyringUnavailable -> ExitCode.DEFERRED

    // Nothing answered: no DNS, no route, a refused or dropped connection. Exactly the same
    // shape of "not now" as an unlocked keyring, and the same answer — a laptop that is asleep,
    // travelling or on hotel wifi must not page anyone on the hourly timer. Retries have already
    // been exhausted by the time this arrives.
    is StorageUnreachableFailure -> ExitCode.DEFERRED

    // A path the operator gave that is not a directory. Nothing else to do about it.
    is CredentialFailure.MissingLibraryRoot -> ExitCode.USAGE

    // A string somebody typed — into `login`, into `--endpoint`, or into `$PHOTOS_ENDPOINT` — that
    // is not a storage URL. §1 refuses opaque errors, and the honest thing to say about a
    // malformed URL is that it was entered wrong.
    is StorageUrlFailure -> ExitCode.USAGE

    // The keyring answered, and holds nothing — or answered something the spec does not allow.
    // Both are real errors that stopped the run before it wrote: waiting an hour changes neither.
    is CredentialFailure.NoSuchItem, is CredentialFailure.KeyringProtocol -> ExitCode.ABORTED

    // The structural guards. All of them fire before anything is written.
    is IngestAbort, is IgnoreRulesUnreadableFailure -> ExitCode.ABORTED

    // Raised mid-run, so the zone may already have changed: `1` says finished-with-failures, which
    // is what a person reading the journal needs to tell apart from `3`.
    is S3HttpFailure,
    is MalformedResponseFailure,
    is ShardFailure,
    is ShardUnavailableFailure,
    -> ExitCode.COMPLETED_WITH_FAILURES
}

/**
 * Runs [body], and turns anything that ends the run into §7's exit code and its one line of
 * explanation.
 *
 * One copy, so all three verbs agree — a `login` that cannot reach the keyring and a `sync` that
 * cannot reach it defer identically, which is the whole point of `SuccessExitStatus=75`.
 */
internal inline fun Console.translatingFailures(body: () -> Unit) {
    try {
        body()
    } catch (failure: PhotosFailure) {
        throw ProgramResult(report(failure))
    }
}

/**
 * Says what went wrong, where it belongs, and answers with the number.
 *
 * A deferral goes to **stdout**: it is an ordinary outcome of a machine that reboots and an import
 * that outlasts the hour between timer firings, so it belongs in the record rather than in the
 * error stream a person greps when something is wrong.
 */
internal fun Console.report(failure: PhotosFailure): Int {
    val code = failure.exitCode()
    when {
        code == ExitCode.DEFERRED -> line("deferred: ${failure.message}")
        // §7 has no undelete and no confirmation step, so a guard that fired is worth two lines:
        // what it was, and the promise that the zone is untouched.
        failure is IngestAbort -> {
            error("aborted: ${failure.message}")
            error("nothing was written")
        }

        else -> error(failure.message)
    }
    return code
}

/**
 * A refused lock, said in a way that names who has it.
 *
 * The pid is the only thing that makes the refusal actionable — §7 asks for *"another sync is
 * already running (pid 1234)"* rather than a bare "already running" — and it is missing only when
 * the holder's lock file could not be read, which is rare and not worth its own outcome.
 */
internal fun LockAttempt.HeldBy.message(): String =
    "another sync is already running" + (pid?.let { " (pid $it)" } ?: "")

/**
 * Clikt's own errors as §7's numbers.
 *
 * Clikt exits `1` for a usage error, which here means the same number as "the run finished with
 * failures". §7 gives usage its own code, so the one place that translates is this function rather
 * than every command's `statusCode`. `--help` and `--version` arrive as errors too, carrying `0`.
 */
internal fun exitCodeFor(error: CliktError): Int =
    if (error is UsageError) ExitCode.USAGE else error.statusCode
