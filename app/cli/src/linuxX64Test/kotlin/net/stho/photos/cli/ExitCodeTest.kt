@file:OptIn(ExperimentalUuidApi::class)

package net.stho.photos.cli

import com.github.ajalt.clikt.core.NoSuchOption
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import net.stho.photos.CredentialFailure
import net.stho.photos.IgnoreRulesUnreadableFailure
import net.stho.photos.IngestAbort
import net.stho.photos.MalformedResponseFailure
import net.stho.photos.PhotosFailure
import net.stho.photos.S3HttpFailure
import net.stho.photos.ShardFailure
import net.stho.photos.ShardUnavailableFailure
import net.stho.photos.StorageUrlFailure
import net.stho.photos.ingest.ByteMismatch
import net.stho.photos.ingest.Credentials
import net.stho.photos.ingest.ExitCode
import net.stho.photos.ports.LockAttempt

/**
 * Every way a run can end, as the number it ends with.
 *
 * This is the piece of the CLI worth testing on its own: §7's exit codes are a contract the systemd
 * unit is written against, so `SuccessExitStatus=75` covering both deferrals — and only those two —
 * is an assertion rather than a comment. A wrong number here turns "you have not logged in yet"
 * into a page at 3 a.m., or hides a genuinely missing password behind an hourly retry that can
 * never succeed.
 *
 * Nothing here needs a keyring, a zone or a library: the mapping is a total function over a sealed
 * hierarchy, and every case is constructible by hand.
 */
class ExitCodeTest {

    // ------------------------------------------------------------------ 75, and only these two

    @Test
    fun aKeyringThatCannotBeReachedDefers() {
        assertEquals(
            ExitCode.DEFERRED,
            CredentialFailure.KeyringUnavailable("no session bus").exitCode(),
        )
    }

    @Test
    fun aLockAnotherRunHoldsDefersAndNamesTheHolder() {
        assertEquals(
            "another sync is already running (pid 1234)",
            LockAttempt.HeldBy(1234).message(),
        )
    }

    @Test
    fun anUnreadableLockFileStillSaysWhatHappened() {
        assertEquals("another sync is already running", LockAttempt.HeldBy(null).message())
    }

    // ---------------------------------------------------------------------------- 3, not 75

    /**
     * The distinction the whole port exists for: *could not look* is a deferral, *looked and found
     * nothing* is a real error. Conflating them is what would turn a laptop nobody has logged into
     * yet into an hourly failure notification — or hide a missing password behind a retry that can
     * never succeed.
     */
    @Test
    fun aKeyringThatAnsweredAndHoldsNothingIsARealError() {
        assertEquals(
            ExitCode.ABORTED,
            CredentialFailure.NoSuchItem(Credentials.SERVICE, "password").exitCode(),
        )
    }

    @Test
    fun aReplyTheSpecDoesNotAllowIsARealError() {
        val reply = CredentialFailure.KeyringProtocol("the stored password is empty")
        assertEquals(ExitCode.ABORTED, reply.exitCode())
    }

    @Test
    fun everyStructuralGuardAbortsBeforeWriting() {
        val guards: List<PhotosFailure> = listOf(
            IngestAbort.NotALibraryRoot("/photos", "no .photosignore"),
            IngestAbort.FileChanged(listOf(ByteMismatch("Rauhöd", "a.jpg", 10, 12))),
            IngestAbort.CacheUnusable("/cache/lock", "permission denied"),
            IngestAbort.UnidentifiableShard("no album_info"),
            IgnoreRulesUnreadableFailure("/photos/.photosignore", "permission denied"),
        )
        for (guard in guards) assertEquals(ExitCode.ABORTED, guard.exitCode(), guard.message)
    }

    // ------------------------------------------------------------------------------------ 2

    @Test
    fun aLibraryRootThatIsNotADirectoryIsUsage() {
        assertEquals(ExitCode.USAGE, CredentialFailure.MissingLibraryRoot().exitCode())
    }

    @Test
    fun everyWayAStorageUrlCanBeWrongIsUsage() {
        val typos: List<PhotosFailure> = listOf(
            StorageUrlFailure.NotAUrl("https://:::"),
            StorageUrlFailure.MissingScheme(),
            StorageUrlFailure.UnsupportedScheme("ftp"),
            StorageUrlFailure.MissingHost(),
            StorageUrlFailure.MissingZone(),
        )
        for (typo in typos) assertEquals(ExitCode.USAGE, typo.exitCode(), typo.message)
    }

    // ------------------------------------------------------------------------------------ 1

    /**
     * `1` and `3` are both failures to `OnFailure=`, but they say different things to a person
     * reading the journal: `1` means the run finished and the zone may have changed, `3` means it
     * refused to start and the zone is untouched. Everything below can only be raised once the run
     * is under way.
     */
    @Test
    fun whatCanOnlyHappenMidRunFinishesWithFailures() {
        val duringTheRun: List<PhotosFailure> = listOf(
            S3HttpFailure(status = 403),
            MalformedResponseFailure("truncated ListBucketResult"),
            ShardFailure.UnsupportedVersion(found = 9, supported = 1),
            ShardFailure.MissingAlbumInfo(),
            ShardFailure.Malformed("album_id is not a uuid"),
            ShardUnavailableFailure(Uuid.NIL),
        )
        for (failure in duringTheRun) {
            assertEquals(ExitCode.COMPLETED_WITH_FAILURES, failure.exitCode(), failure.message)
        }
    }

    // ------------------------------------------------------------------- what clikt itself throws

    @Test
    fun aMistypedOptionIsUsageRatherThanCliktsOwnOne() {
        assertEquals(ExitCode.USAGE, exitCodeFor(NoSuchOption("--albun")))
    }

    @Test
    fun helpAndVersionAreCleanExits() {
        assertEquals(ExitCode.CLEAN, exitCodeFor(PrintMessage("1.0.0")))
        assertEquals(ExitCode.CLEAN, exitCodeFor(PrintHelpMessage(null)))
    }

    @Test
    fun aCodeAVerbChoseSurvivesUntouched() {
        assertEquals(ExitCode.DEFERRED, exitCodeFor(ProgramResult(ExitCode.DEFERRED)))
        assertEquals(ExitCode.ABORTED, exitCodeFor(ProgramResult(ExitCode.ABORTED)))
    }

    // -------------------------------------------------------------------------- what gets said

    @Test
    fun aDeferralIsSaidOnStdoutBecauseItIsAnOrdinaryOutcome() {
        val recorder = Recorder()
        val code = recorder.console.report(CredentialFailure.KeyringUnavailable("no session bus"))

        assertEquals(ExitCode.DEFERRED, code)
        assertEquals(listOf("deferred: keyring unavailable: no session bus"), recorder.out)
        assertTrue(recorder.err.isEmpty(), "a deferral is not an error: ${recorder.err}")
    }

    @Test
    fun aGuardThatFiredPromisesTheZoneIsUntouched() {
        val recorder = Recorder()
        val code = recorder.console.report(IngestAbort.NotALibraryRoot("/photos", "no marker"))

        assertEquals(ExitCode.ABORTED, code)
        assertTrue(recorder.out.isEmpty(), "an abort belongs on stderr: ${recorder.out}")
        assertEquals(
            listOf(
                "photos-cli: aborted: /photos is not a library root: no marker",
                "photos-cli: nothing was written",
            ),
            recorder.err,
        )
    }
}
