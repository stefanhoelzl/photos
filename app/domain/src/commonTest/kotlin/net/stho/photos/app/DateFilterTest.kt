package net.stho.photos.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The calendar arithmetic under the date filter, which nothing else in the app checks. */
class DateFilterTest {

    @Test
    fun theEpochIsDayZeroAndAThursday() {
        assertEquals(Day(0), Day.of(1970, 1, 1))
        assertEquals(3, Day(0).weekday, "Monday is 0")
        assertEquals(1, Day.of(2024, 3, 12).weekday, "12 March 2024 was a Tuesday")
    }

    @Test
    fun civilDatesRoundTripAcrossLeapYearsAndCenturies() {
        var epochDay = -800_000L
        while (epochDay <= 800_000L) {
            val day = Day(epochDay)
            assertEquals(day, Day.of(day.year, day.month, day.dayOfMonth), "epoch day $epochDay")
            epochDay += 997
        }
        assertEquals(Day.of(2024, 3, 1), Day.of(2024, 2, 29) + 1)
        assertEquals(Day.of(1900, 3, 1), Day.of(1900, 2, 28) + 1, "1900 was not a leap year")
        assertEquals(Day.of(2000, 3, 1), Day.of(2000, 2, 29) + 1, "2000 was")
    }

    /** `taken_at` is EXIF's zoneless local time stored as UTC, so its UTC day is the camera's (§3). */
    @Test
    fun aPhotosDayIsTheUtcDayOfItsTakenAt() {
        assertEquals(Day.of(2024, 3, 20), Day.of(Instant.parse("2024-03-20T23:59:59Z")))
        assertEquals(Day.of(2024, 3, 21), Day.of(Instant.parse("2024-03-21T00:00:00Z")))
        assertEquals(Day.of(1969, 12, 31), Day.of(Instant.parse("1969-12-31T23:59:59Z")), "floored, not truncated")
    }

    @Test
    fun aRangeCoversItsLastDayUpToTheMidnightAfterIt() {
        val range = DateRange(Day.of(2024, 3, 12), Day.of(2024, 3, 20))
        assertEquals(Instant.parse("2024-03-12T00:00:00Z"), range.from)
        assertEquals(Instant.parse("2024-03-21T00:00:00Z"), range.until)
        assertTrue(Day.of(2024, 3, 20) in range)
        assertFalse(Day.of(2024, 3, 21) in range)
    }

    @Test
    fun theLabelNamesWhatBothEndsShareOnce() {
        assertEquals("12 – 20 Mar 2024", DateRange(Day.of(2024, 3, 12), Day.of(2024, 3, 20)).label)
        assertEquals("28 Feb – 3 Mar 2024", DateRange(Day.of(2024, 2, 28), Day.of(2024, 3, 3)).label)
        assertEquals("30 Dec 2023 – 2 Jan 2024", DateRange(Day.of(2023, 12, 30), Day.of(2024, 1, 2)).label)
        assertEquals("12 Mar 2024", DateRange(Day.of(2024, 3, 12), Day.of(2024, 3, 12)).label)
    }

    @Test
    fun betweenPutsTheEarlierDayFirst() {
        val range = DateRange(Day.of(2024, 3, 12), Day.of(2024, 3, 20))
        assertEquals(range, DateRange.between(Day.of(2024, 3, 20), Day.of(2024, 3, 12)))
    }

    @Test
    fun parseReadsIsoAndRejectsWhatIsNotADay() {
        assertEquals(Day.of(2024, 3, 12), Day.parse("2024-03-12"))
        assertEquals("2024-03-12", Day.of(2024, 3, 12).toString())
        assertNull(Day.parse("2024-02-30"))
        assertNull(Day.parse("2023-02-29"))
        assertNull(Day.parse("2024-3-12"))
        assertNull(Day.parse("12 Mar 2024"))
    }

    @Test
    fun theCalendarSpansTheFirstDatedMonthToTheLastAndNoFurther() {
        val calendar = CalendarUi.of(mapOf(Day.of(2023, 11, 5) to 3, Day.of(2024, 2, 1) to 7, Day.of(2025, 1, 1) to 0))

        assertEquals(
            listOf(CalendarMonth(2023, 11), CalendarMonth(2023, 12), CalendarMonth(2024, 1), CalendarMonth(2024, 2)),
            calendar.months,
            "a day with no photos does not stretch the calendar",
        )
        assertEquals(3, calendar.photosIn(DateRange(Day.of(2023, 11, 1), Day.of(2024, 1, 31))))
        assertEquals(10, calendar.photosIn(DateRange(Day.of(2023, 11, 5), Day.of(2024, 2, 1))))
        assertTrue(CalendarUi.of(emptyMap()).months.isEmpty())
    }

    /** What a month title and a year heading pick, and the totals they show. */
    @Test
    fun aMonthAndAYearCoverEveryOneOfTheirDays() {
        assertEquals(DateRange(Day.of(2024, 2, 1), Day.of(2024, 2, 29)), CalendarMonth(2024, 2).days)
        assertEquals(DateRange(Day.of(2024, 1, 1), Day.of(2024, 12, 31)), DateRange.year(2024))
        assertEquals("1 Jan – 31 Dec 2024", DateRange.year(2024).label)

        val calendar = CalendarUi.of(mapOf(Day.of(2023, 12, 31) to 4, Day.of(2024, 2, 1) to 7, Day.of(2024, 2, 29) to 2))
        assertEquals(9, calendar.photosInMonth(CalendarMonth(2024, 2)))
        assertEquals(0, calendar.photosInMonth(CalendarMonth(2024, 1)))
        assertEquals(9, calendar.photosInYear(2024))
        assertEquals(4, calendar.photosInYear(2023))
    }

    @Test
    fun spanningCoversBothRanges() {
        val march = CalendarMonth(2024, 3).days
        val may = CalendarMonth(2024, 5).days
        assertEquals(DateRange(Day.of(2024, 3, 1), Day.of(2024, 5, 31)), may.spanning(march))
    }
}
