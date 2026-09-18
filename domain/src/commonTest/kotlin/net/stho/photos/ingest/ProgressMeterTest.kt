package net.stho.photos.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * The counter's arithmetic, which nothing asserted on until it was wrong twice.
 *
 * It once compared bytes *sent* against bytes *read* — two quantities that stopped being
 * comparable when §5 stopped storing originals — so the bar crawled to a seventh of the way
 * across and the estimate ran seven times long. Nothing caught it, because everything about the
 * meter was a display detail no test looked at.
 */
class ProgressMeterTest {

    private class SteppedClock(private var now: Instant = Instant.fromEpochSeconds(1_700_000_000)) : Clock {
        override fun now(): Instant = now
        fun advance(by: Duration) { now += by }
    }

    /** A total nobody can compute yet is better left unsaid than guessed from one photograph. */
    @Test
    fun noTotalIsShownUntilThereIsASampleToProjectFrom() = runTest {
        val clock = SteppedClock()
        val meter = ProgressMeter(clock)
        meter.start(files = 100, sourceBytes = 100_000_000)

        clock.advance(2.seconds)
        val early = assertNotNull(meter.finished(files = 1, bytes = 1_000, source = 10_000))

        assertTrue("1/100 files" in early, early)
        assertTrue(" of " !in early, "a one-photo projection is not worth showing: $early")
        assertTrue("left" !in early, "nor an estimate built on it: $early")
    }

    /**
     * The projection is the run's own ratio applied to what is left — so a library that derives
     * to a tenth of its size is projected at a tenth, with no constant anywhere saying so.
     */
    @Test
    fun theTotalIsProjectedFromTheRatioTheRunObserves() = runTest {
        val clock = SteppedClock()
        val meter = ProgressMeter(clock)
        // 100 files, 100 MB on disk. Each derives to a tenth of its source.
        meter.start(files = 100, sourceBytes = 100_000_000)

        var line: String? = null
        repeat(20) {
            clock.advance(2.seconds)
            line = meter.finished(files = 1, bytes = 100_000, source = 1_000_000) ?: line
        }

        // 20 done: 2,000,000 bytes sent from 20,000,000 read. The remaining 80,000,000 project
        // to 8,000,000, for 10,000,000 in total — a tenth of the source, which is the ratio it
        // was fed and which no constant anywhere told it.
        val shown = assertNotNull(line)
        assertTrue("20/100 files" in shown, shown)
        assertTrue("of ~10.0 MB" in shown, "projected the wrong total: $shown")
        assertTrue("left" in shown, "an estimate should follow a projection: $shown")
    }

    /** Bytes read are not bytes sent, and the counter must never conflate them again. */
    @Test
    fun theProjectionTracksWhatIsSentNotWhatIsRead() = runTest {
        val clock = SteppedClock()
        val meter = ProgressMeter(clock)
        meter.start(files = 40, sourceBytes = 40_000_000)

        var line: String? = null
        repeat(20) {
            clock.advance(2.seconds)
            line = meter.finished(files = 1, bytes = 137_000, source = 1_000_000) ?: line
        }

        val shown = assertNotNull(line)
        // Half done, so the projection is twice what has been sent — and nowhere near the
        // 40 MB that came off the disk.
        assertTrue("of ~5.5 MB" in shown, shown)
        assertTrue("40.0 MB" !in shown, "the disk figure leaked into the upload total: $shown")
    }

    /**
     * §9 bills at $0.01/GB and means the decimal GB, so a run reporting 19.2 GB and an invoice
     * computed on 19.2 GB have to be the same number. Dividing by 1024 while writing `GB` put
     * them 7.4% apart with nothing on screen to say why.
     */
    @Test
    fun bytesArePrintedInTheUnitsTheyClaim() {
        assertEquals("1.0 KB", formatBytes(1_000))
        assertEquals("1.0 MB", formatBytes(1_000_000))
        assertEquals("1.0 GB", formatBytes(1_000_000_000))
        assertEquals("999 B", formatBytes(999))
        // The discriminating case: 2³⁰ bytes is one GiB, and one GiB is 1.1 GB. Under the
        // divide-by-1024 this replaces it printed "1.0 GB", which is what a GiB is not.
        assertEquals("1.1 GB", formatBytes(1_073_741_824))
        assertEquals("19.2 GB", formatBytes(19_219_978_650))
    }

    @Test
    fun aRunWithNoFilesSaysNothingAtAll() = runTest {
        val meter = ProgressMeter(SteppedClock())
        meter.start(files = 0, sourceBytes = 0)

        assertNull(meter.finished(files = 1, bytes = 0, source = 0))
    }

    /**
     * The counter once read 175/70: pulls fed their files into a total counted before them. A
     * phase restarts the meter, so what it shows is that phase's own work against its own total.
     */
    @Test
    fun eachPhaseCountsAgainstItsOwnTotal() = runTest {
        val clock = SteppedClock()
        val meter = ProgressMeter(clock)
        meter.start(files = 2, sourceBytes = 2_000)
        repeat(2) { clock.advance(2.seconds); meter.finished(files = 1, bytes = 100, source = 1_000) }

        meter.start(files = 3, sourceBytes = 3_000)
        clock.advance(2.seconds)
        val line = assertNotNull(meter.finished(files = 1, bytes = 100, source = 1_000))

        assertTrue(line.startsWith("1/3 files"), line)
    }

    /**
     * A Live Photo is two files and one item. The total is in files, so it ticks two — otherwise
     * a library of them ends short of its total and the final line is never drawn.
     */
    @Test
    fun aLivePhotoTicksBothItsFilesAndTheCounterReachesItsTotal() = runTest {
        val clock = SteppedClock()
        val meter = ProgressMeter(clock)
        meter.start(files = 3, sourceBytes = 3_000)

        clock.advance(2.seconds)
        meter.finished(files = 2, bytes = 100, source = 2_000)
        // Too soon for an ordinary redraw — but the last one is always drawn.
        clock.advance(100.milliseconds)
        val last = assertNotNull(meter.finished(files = 1, bytes = 100, source = 1_000))

        assertTrue(last.startsWith("3/3 files"), last)
    }

    /** A counter that moved only between files would sit still for all of a 200 MB video. */
    @Test
    fun aDownloadCountsBytesWhileAFileIsStillArriving() = runTest {
        val clock = SteppedClock()
        val meter = DownloadMeter(clock)
        meter.start(files = 2, bytes = 200_000_000)

        clock.advance(10.seconds)
        val midway = assertNotNull(meter.receiving(slot = 0, received = 50_000_000))

        assertTrue(midway.startsWith("0/2 files  50.0 MB of 200.0 MB"), midway)
        assertTrue("5.00 MB/s" in midway, midway)
        assertTrue("~30s left" in midway, midway)
    }

    /** Several files in flight add up; one that was already on disk is done but was not fetched. */
    @Test
    fun downloadsInFlightAddUpAndAFileFoundOnDiskIsNotCountedAsSpeed() = runTest {
        val clock = SteppedClock()
        val meter = DownloadMeter(clock)
        meter.start(files = 3, bytes = 30_000_000)

        meter.finished(slot = 0, bytes = 10_000_000, fetched = false)
        meter.receiving(slot = 1, received = 2_000_000)
        clock.advance(2.seconds)
        val line = assertNotNull(meter.receiving(slot = 2, received = 4_000_000))

        assertTrue(line.startsWith("1/3 files  16.0 MB of 30.0 MB"), line)
        // 6 MB came over the link in two seconds; the 10 MB found on disk did not.
        assertTrue("3.00 MB/s" in line, line)
    }

    @Test
    fun theLastDownloadIsAlwaysDrawnAndNeverExceedsItsTotal() = runTest {
        val clock = SteppedClock()
        val meter = DownloadMeter(clock)
        meter.start(files = 2, bytes = 2_000)

        clock.advance(2.seconds)
        meter.finished(slot = 0, bytes = 1_000, fetched = true)
        val last = assertNotNull(meter.finished(slot = 1, bytes = 1_000, fetched = true))

        assertTrue(last.startsWith("2/2 files  2.0 KB of 2.0 KB"), last)
        assertTrue("left" !in last, last)
    }

    /** A pulled album is one line in the journal, carrying what came down and what went up. */
    @Test
    fun aPulledAlbumIsOneLineWithBothDirections() {
        val album = IngestReport.AlbumOutcome(
            "Lachenspitze", uploaded = 40, dropped = 0, bytes = 9_800_000, created = false,
            duration = 30.seconds,
        )

        assertEquals(
            "v Lachenspitze  46 files, 60.3 MB down  40 photos, 9.8 MB up  51.2s",
            transferLine("v", "Lachenspitze", 46, 60_300_000, album, 51_200.milliseconds),
        )
        // A merge resumed after its commit sends nothing, and says only what came down.
        assertEquals(
            "< Toskana 2009  3 files, 12.0 MB down  1.5s",
            transferLine("<", "Toskana 2009", 3, 12_000_000, null, 1_500.milliseconds),
        )
    }
}
