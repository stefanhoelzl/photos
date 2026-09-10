@file:OptIn(ExperimentalUuidApi::class)

package net.stho.photos.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import net.stho.photos.catalog.ShardProbe
import net.stho.photos.ingest.IngestEvent
import net.stho.photos.ingest.IngestReport
import net.stho.photos.library.IgnoreRule

/**
 * The end-of-run block, and what a run says while it is happening.
 *
 * §4's "reported, not resolved" is a promise the CLI keeps or breaks: every unresolved condition is
 * named on *every* run until a person deals with it. A condition the reporter silently drops is
 * indistinguishable from one that never happened, which is why the test that matters is that each
 * of them reaches the record at all.
 */
class ConsoleReporterTest {

    @Test
    fun everyUnresolvedConditionIsNamed() {
        val recorder = Recorder()
        val orphan = Uuid.parse("00000000-0000-4000-8000-00000000beef")

        ConsoleReporter(recorder.console).render(
            IngestReport(
                albums = listOf(
                    IngestReport.AlbumOutcome(
                        "Rauhöd", 2, 0, 2048, created = true, duration = 1.seconds,
                    ),
                ),
                strays = listOf(IngestReport.Failure("Rauhöd/notes.txt", "not media")),
                failures = listOf(IngestReport.Failure("Rauhöd/a.cr2", "no preview")),
                mixedFolders = listOf(IngestReport.MixedFolder("Reisen", 3)),
                looseRootFiles = 2,
                blockedByUnreadable = listOf(ShardProbe(Uuid.NIL, "Neuseeland", 9)),
                contendedAlbums = listOf("Alpen"),
                duplicateNames = listOf("Sommer"),
                orphanedAlbums = listOf(orphan),
                unusedRules = listOf(IgnoreRule("*.xmp", false, false, "*.xmp", 3)),
            ),
        )

        // Everything a person has to deal with is marked, and nothing else is.
        val notes = recorder.out.filter { it.startsWith("! ") }
        assertEquals(9, notes.size, recorder.out.joinToString("\n"))
        for (subject in listOf(
            "Rauhöd/notes.txt", "Rauhöd/a.cr2", "Reisen", "the library root",
            "Neuseeland", "Alpen", "Sommer", "$orphan", "*.xmp",
        )) {
            assertTrue(notes.any { subject in it }, "$subject is not in the record: $notes")
        }
    }

    @Test
    fun theSummaryIsTheLastLineAndCountsWhatChanged() {
        val recorder = Recorder()

        ConsoleReporter(recorder.console).render(
            IngestReport(
                albums = listOf(
                    IngestReport.AlbumOutcome(
                        "Rauhöd", 2, 1, 2_000_000, created = true, duration = 1.seconds,
                    ),
                ),
                deletedAlbums = listOf(IngestReport.DeletedAlbum("Alpen", 4)),
                pulledAlbums = listOf(IngestReport.PulledAlbum("Neuseeland", 3, 512)),
                failures = listOf(IngestReport.Failure("Rauhöd/a.cr2", "no preview")),
                ignoredFiles = 7,
            ),
        )

        assertEquals(
            "── 1 album(s), 2 photos, 2.0 MB, 1 row(s) dropped, 1 album(s) deleted, " +
                "1 album(s) pulled, 1 failed, 7 ignored",
            recorder.out.last(),
        )
    }

    @Test
    fun aDryRunSaysSoBeforeAnythingElse() {
        val recorder = Recorder()

        ConsoleReporter(recorder.console).render(IngestReport(dryRun = true))

        assertEquals("dry run — nothing was changed", recorder.out.first())
        assertEquals("── 0 album(s)", recorder.out.last())
    }

    /**
     * §2's two wins are invisible unless the run says so. A resumed import and a profile bump
     * both do most of their work by *not* sending anything, and a run that reports only what it
     * uploaded reads as though it did nothing at all.
     */
    @Test
    fun workAvoidedIsStillReported() {
        val recorder = Recorder()

        ConsoleReporter(recorder.console).render(
            IngestReport(skippedUploads = 1204, skippedBytes = 2_000_000),
        )

        assertTrue(
            recorder.out.any { "1204 object(s) already in the zone" in it && "2.0 MB" in it },
            "the run said nothing about what it skipped: ${recorder.out}",
        )
    }

    /** §7: a run says what it intends before it does it — even when it intends nothing. */
    @Test
    fun thePlanIsSaidBeforeTheRun() {
        val recorder = Recorder()
        val reporter = ConsoleReporter(recorder.console)

        reporter.show(
            IngestEvent.Planned(albums = 2, files = 30, bytes = 1024, deletions = 1, pulls = 1),
        )
        reporter.show(IngestEvent.Planned(albums = 0, files = 0, bytes = 0, deletions = 0, pulls = 0))

        assertEquals(
            listOf(
                "to do: 2 album(s), 30 photos, 1.0 KB to read, 1 album(s) to delete, 1 album(s) to pull",
                "nothing to do",
            ),
            recorder.out,
        )
    }

    /**
     * The counter belongs on a terminal and nowhere else, so an unattended run's log is exactly the
     * facts (§7). [Recorder] is not a terminal, so a status event must leave no trace at all.
     */
    @Test
    fun theCounterNeverReachesTheRecord() {
        val recorder = Recorder()
        val reporter = ConsoleReporter(recorder.console)

        reporter.show(IngestEvent.Status("12/30  4.0 MB  1.9 MB/s"))
        reporter.show(IngestEvent.Line("+ Rauhöd  2 photos"))

        assertEquals(listOf("+ Rauhöd  2 photos"), recorder.out)
        assertTrue(recorder.err.isEmpty(), "a journal saw the counter: ${recorder.err}")
    }
}
