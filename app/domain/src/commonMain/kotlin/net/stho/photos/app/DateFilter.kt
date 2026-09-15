package net.stho.photos.app

import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * One calendar day, as a photo's date means it (§3).
 *
 * EXIF records the camera's local time with no zone, and ingest stores that as if it were UTC — so
 * the UTC day of `taken_at` is the day on the camera's clock, and no time zone is applied anywhere
 * here. Applying the device's would move a photo taken at 23:30 in Reykjavík onto the next day
 * whenever the list is read in Berlin.
 *
 * Its own type over epoch days rather than kotlinx-datetime's `LocalDate`: the calendar needs a
 * day's number, its month and its weekday, which is Howard Hinnant's two civil-calendar
 * conversions rather than a dependency in both apps.
 */
@JvmInline
public value class Day(public val epochDay: Long) : Comparable<Day> {
    public val year: Int get() = civil(epochDay).year
    public val month: Int get() = civil(epochDay).month
    public val dayOfMonth: Int get() = civil(epochDay).day

    /** Monday 0 … Sunday 6: the calendar's weeks start on Monday. 1 January 1970 was a Thursday. */
    public val weekday: Int get() = ((epochDay + 3) % 7 + 7).toInt() % 7

    /** The midnight this day starts at, in `taken_at`'s own terms. */
    public val midnight: Instant get() = Instant.fromEpochSeconds(epochDay * SECONDS_PER_DAY)

    public operator fun plus(days: Int): Day = Day(epochDay + days)

    override fun compareTo(other: Day): Int = epochDay.compareTo(other.epochDay)

    /** ISO 8601, `2024-03-12`: what the control server reads and writes. */
    override fun toString(): String =
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${dayOfMonth.toString().padStart(2, '0')}"

    public companion object {
        public fun of(year: Int, month: Int, day: Int): Day {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 399) / 400
            val yearOfEra = y - era * 400
            val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return Day(era * 146_097 + dayOfEra - 719_468)
        }

        /** The day [instant] falls on. Floored, so a photo from before 1970 lands on its own day. */
        public fun of(instant: Instant): Day = Day(instant.epochSeconds.floorDiv(SECONDS_PER_DAY))

        /** `2024-03-12`, or null for anything that is not a real day in that form. */
        public fun parse(text: String): Day? {
            val match = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(text) ?: return null
            val (year, month, day) = match.destructured.toList().map(String::toInt)
            if (month !in 1..12 || day !in 1..daysIn(year, month)) return null
            return of(year, month, day)
        }

        public fun daysIn(year: Int, month: Int): Int = when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

private const val SECONDS_PER_DAY = 86_400L

private data class Civil(val year: Int, val month: Int, val day: Int)

private fun civil(epochDay: Long): Civil {
    val z = epochDay + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val shifted = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * shifted + 2) / 5 + 1
    val month = if (shifted < 10) shifted + 3 else shifted - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return Civil(year.toInt(), month.toInt(), day.toInt())
}

/**
 * The date filter: every day from [start] to [end], both included. A range picks days, never
 * times — so a photo taken in the last second of the last day is in it.
 */
public data class DateRange(val start: Day, val end: Day) {
    init {
        require(start <= end) { "a range ends on or after its start: $start – $end" }
    }

    public operator fun contains(day: Day): Boolean = day >= start && day <= end

    /** The first instant a photo's `taken_at` may be. */
    public val from: Instant get() = start.midnight

    /** The midnight after the last day, which a photo's `taken_at` must be before. */
    public val until: Instant get() = (end + 1).midnight

    /**
     * How the search field writes it, naming what both ends share once: "12 – 20 Mar 2024",
     * "28 Feb – 3 Mar 2024", "30 Dec 2023 – 2 Jan 2024", and "12 Mar 2024" for a single day.
     */
    public val label: String
        get() = when {
            start == end -> full(start)
            start.year != end.year -> "${full(start)} – ${full(end)}"
            start.month != end.month ->
                "${start.dayOfMonth} ${MONTHS[start.month - 1]} – ${end.dayOfMonth} ${MONTHS[end.month - 1]} ${end.year}"
            else -> "${start.dayOfMonth} – ${end.dayOfMonth} ${MONTHS[end.month - 1]} ${end.year}"
        }

    /** The smallest range holding both this one and [other] — what a drag from one to the other picks. */
    public fun spanning(other: DateRange): DateRange = DateRange(minOf(start, other.start), maxOf(end, other.end))

    public companion object {
        /** The range between two days picked in either order. */
        public fun between(one: Day, other: Day): DateRange =
            if (one <= other) DateRange(one, other) else DateRange(other, one)

        /** 1 January to 31 December of [year]. */
        public fun year(year: Int): DateRange = DateRange(Day.of(year, 1, 1), Day.of(year, 12, 31))
    }
}

private fun full(day: Day) = "${day.dayOfMonth} ${MONTHS[day.month - 1]} ${day.year}"

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/** One month of the calendar sheet. */
public data class CalendarMonth(val year: Int, val month: Int) {
    public val first: Day get() = Day.of(year, month, 1)
    public val length: Int get() = Day.daysIn(year, month)
    public val last: Day get() = first + (length - 1)

    /** Every day of the month, which is what tapping its title picks. */
    public val days: DateRange get() = DateRange(first, last)

    /** "March": the calendar names the year once, in the heading pinned above its months. */
    public val name: String get() = MONTH_NAMES[month - 1]

    /** "March 2024". */
    public val title: String get() = "$name $year"

    public fun next(): CalendarMonth = if (month == 12) CalendarMonth(year + 1, 1) else CalendarMonth(year, month + 1)

    public companion object {
        public fun of(day: Day): CalendarMonth = CalendarMonth(day.year, day.month)
    }
}

/**
 * What the calendar sheet shows (§6): how many photos were taken on each day, across the whole
 * library, and the months that spans.
 *
 * The whole library rather than the list's current matches, so a day's number never changes with
 * what was typed. The months run from the first dated photo's to the last's and no further — an
 * endless empty future is only something to scroll past.
 */
public data class CalendarUi(val days: Map<Day, Int>, val months: List<CalendarMonth>) {
    public fun photosOn(day: Day): Int = days[day] ?: 0

    public fun photosIn(range: DateRange): Int = days.entries.sumOf { (day, photos) -> if (day in range) photos else 0 }

    /** A month title's number. Summed once per calendar, not per title drawn. */
    public fun photosInMonth(month: CalendarMonth): Int = byMonth[month] ?: 0

    /** A year heading's number. */
    public fun photosInYear(year: Int): Int = byYear[year] ?: 0

    private val byMonth: Map<CalendarMonth, Int> by lazy {
        days.entries.groupingBy { CalendarMonth.of(it.key) }.fold(0) { total, (_, photos) -> total + photos }
    }

    private val byYear: Map<Int, Int> by lazy {
        byMonth.entries.groupingBy { it.key.year }.fold(0) { total, (_, photos) -> total + photos }
    }

    public companion object {
        public fun of(days: Map<Day, Int>): CalendarUi {
            val dated = days.filterValues { it > 0 }
            val first = dated.keys.minOrNull() ?: return CalendarUi(dated, emptyList())
            val last = CalendarMonth.of(dated.keys.max())
            val months = generateSequence(CalendarMonth.of(first)) { if (it == last) null else it.next() }.toList()
            return CalendarUi(dated, months)
        }
    }
}
