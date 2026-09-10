package net.stho.photos.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
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
        val early = assertNotNull(meter.finished(bytes = 1_000, source = 10_000, album = "A"))

        assertTrue("1/100" in early, early)
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
            line = meter.finished(bytes = 100_000, source = 1_000_000, album = "Rauhöd") ?: line
        }

        // 20 done: 2,000,000 bytes sent from 20,000,000 read. The remaining 80,000,000 project
        // to 8,000,000, for 10,000,000 in total — a tenth of the source, which is the ratio it
        // was fed and which no constant anywhere told it.
        //
        // Shown as "9.5 MB" because `formatBytes` divides by 1024 and labels the result MB, so
        // every figure the CLI prints is really the MiB of the same number.
        val shown = assertNotNull(line)
        assertTrue("20/100" in shown, shown)
        assertTrue("of ~9.5 MB" in shown, "projected the wrong total: $shown")
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
            line = meter.finished(bytes = 137_000, source = 1_000_000, album = "Rauhöd") ?: line
        }

        val shown = assertNotNull(line)
        // Half done, so the projection is twice what has been sent — and nowhere near the
        // 40 MB that came off the disk.
        assertTrue("of ~5.2 MB" in shown, shown)
        assertTrue("40.0 MB" !in shown, "the disk figure leaked into the upload total: $shown")
    }

    @Test
    fun aRunWithNoFilesSaysNothingAtAll() = runTest {
        val meter = ProgressMeter(SteppedClock())
        meter.start(files = 0, sourceBytes = 0)

        assertNull(meter.finished(bytes = 0, source = 0, album = "A"))
    }
}
