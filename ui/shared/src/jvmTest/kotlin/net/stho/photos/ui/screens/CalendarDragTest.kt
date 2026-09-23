package net.stho.photos.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.stho.photos.app.CalendarMonth
import net.stho.photos.app.CalendarUi
import net.stho.photos.app.DateRange
import net.stho.photos.app.Day

/**
 * The calendar's gesture, performed: synthetic touches on the real months, rendered offscreen, as
 * [PickerDragTest] does for the picker whose rule this gesture shares.
 *
 * At density 1 the months are 400 px wide: a 10 px gutter either side of seven 380/7 px columns.
 * The list opens on 2024's 40 px heading — pinned over the top, where the list's own copy sits —
 * then March's 36 px title and its weeks in rows of 44 px. March 2024 begins on a Friday, so the
 * 1st is in the fifth column and the 11th begins the third row; five weeks put April's title at 296.
 */
class CalendarDragTest {

    private val spring = CalendarUi.of(
        (1..31).associate { Day.of(2024, 3, it) to 1 } + (1..30).associate { Day.of(2024, 4, it) to 1 },
    )

    @Test
    fun oneTapWaitsForASecond() = months {
        tap(day(12))
        assertEquals(RangeDraft(march(12, 12), open = true), draft)
    }

    @Test
    fun aSecondTapEndsTheRange() = months {
        tap(day(12))
        tap(day(20))
        assertEquals(RangeDraft(march(12, 20)), draft)
    }

    @Test
    fun aSecondTapBeforeTheFirstBecomesTheStart() = months {
        tap(day(20))
        tap(day(12))
        assertEquals(RangeDraft(march(12, 20)), draft)
    }

    @Test
    fun aSidewaysDragPicksARangeInOneGesture() = months {
        press(day(12))
        move(day(14))
        release(day(14))
        assertEquals(RangeDraft(march(12, 14)), draft)
    }

    @Test
    fun aPressHeldStillThenDraggedDownPicksAcrossWeeks() = months {
        press(day(12))
        hold()
        move(day(26))
        release(day(26))
        assertEquals(RangeDraft(march(12, 26)), draft)
    }

    @Test
    fun aVerticalDragWithoutHoldingPicksNothing() = months {
        press(day(5))
        move(day(19))
        release(day(19))
        assertNull(draft)
    }

    @Test
    fun aTapAfterAFinishedRangeStartsANewOne() = months(initially = RangeDraft(march(12, 20))) {
        tap(day(5))
        assertEquals(RangeDraft(march(5, 5), open = true), draft)
    }

    @Test
    fun draggingTheEndOfAFinishedRangeMovesTheEnd() = months(initially = RangeDraft(march(12, 20))) {
        press(day(20))
        move(day(23))
        release(day(23))
        assertEquals(RangeDraft(march(12, 23)), draft)
    }

    @Test
    fun draggingTheStartOfAFinishedRangeMovesTheStart() = months(initially = RangeDraft(march(12, 20))) {
        press(day(12))
        move(day(9))
        release(day(9))
        assertEquals(RangeDraft(march(9, 20)), draft)
    }

    // ------------------------------------------------------------------------ whole months and years

    @Test
    fun tappingAMonthsTitlePicksTheWholeMonth() = months(initially = RangeDraft(march(12, 12), open = true)) {
        tap(MARCH_TITLE)
        assertEquals(RangeDraft(CalendarMonth(2024, 3).days), draft, "whatever a first tap began")
    }

    @Test
    fun tappingThePinnedYearHeadingPicksTheWholeYear() = months {
        tap(PINNED_HEADING)
        assertEquals(RangeDraft(DateRange.year(2024)), draft)
    }

    @Test
    fun draggingFromOneMonthsTitleToAnothersPicksBothWhole() = months {
        press(MARCH_TITLE)
        hold()
        move(APRIL_TITLE)
        release(APRIL_TITLE)
        assertEquals(RangeDraft(DateRange(Day.of(2024, 3, 1), Day.of(2024, 4, 30))), draft)
    }

    /** December 2023 (five weeks) under 2023's heading, then 2024's heading at 296. */
    @Test
    fun draggingFromOneYearsHeadingToAnothersPicksBothYears() = months(
        calendar = CalendarUi.of(mapOf(Day.of(2023, 12, 5) to 3, Day.of(2024, 1, 10) to 2)),
    ) {
        press(PINNED_HEADING)
        hold()
        move(Offset(200f, 316f))
        release(Offset(200f, 316f))
        assertEquals(RangeDraft(DateRange(Day.of(2023, 1, 1), Day.of(2024, 12, 31))), draft)
    }

    private fun march(from: Int, to: Int) = DateRange(Day.of(2024, 3, from), Day.of(2024, 3, to))

    /** The centre of the [day]th of March 2024: its column and row, below the heading and the title. */
    private fun day(day: Int): Offset {
        val cell = day - 1 + FIRST_WEEKDAY
        val column = 380f / 7
        return Offset(x = 10f + (cell % 7) * column + column / 2, y = 76f + (cell / 7) * 44f + 22f)
    }

    // ------------------------------------------------------------------------------ harness

    private inner class Touches(private val scope: TestScope, private val scene: ImageComposeScene) {
        val draft: RangeDraft? get() = requireNotNull(current).value
        private var time = 0L
        private var last = Offset.Zero

        fun tap(at: Offset) {
            press(at)
            release(at)
        }

        fun press(at: Offset) = send(PointerEventType.Press, at)

        /** Moves in small steps, as a finger reports, so the gesture sees the slop crossed on the way. */
        fun move(to: Offset) {
            val from = last
            for (step in 1..STEPS) send(PointerEventType.Move, from + (to - from) * (step / STEPS.toFloat()))
        }

        fun release(at: Offset) = send(PointerEventType.Release, at)

        /** Past the long-press timeout, with the finger still. */
        fun hold() {
            time += 1_000
            scope.testScheduler.advanceTimeBy(1_000)
            scope.testScheduler.runCurrent()
            scene.render()
        }

        private fun send(type: PointerEventType, at: Offset) {
            time += 16
            last = at
            scene.sendPointerEvent(eventType = type, position = at, timeMillis = time, type = PointerType.Touch)
            scope.testScheduler.runCurrent()
            scene.render(time * 1_000_000)
        }
    }

    private var current: MutableState<RangeDraft?>? = null

    private fun months(
        calendar: CalendarUi = spring,
        initially: RangeDraft? = null,
        body: Touches.() -> Unit,
    ) = runTest {
        val state = mutableStateOf(initially)
        current = state
        val scene = ImageComposeScene(
            width = 400,
            height = 800,
            density = Density(1f),
            coroutineContext = StandardTestDispatcher(testScheduler),
        ) {
            CalendarMonths(calendar, state.value, { state.value = it }, Modifier.fillMaxSize())
        }
        try {
            scene.render()
            testScheduler.runCurrent()
            scene.render()
            Touches(this, scene).body()
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val STEPS = 6

        /** 1 March 2024 was a Friday: Monday is column 0. */
        const val FIRST_WEEKDAY = 4

        val PINNED_HEADING = Offset(200f, 20f)
        val MARCH_TITLE = Offset(200f, 58f)
        val APRIL_TITLE = Offset(200f, 314f)
    }
}
