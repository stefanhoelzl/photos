package net.stho.photos.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.log10
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.stho.photos.app.CalendarMonth
import net.stho.photos.app.CalendarUi
import net.stho.photos.app.DateRange
import net.stho.photos.app.Day

/**
 * The date filter's calendar (§6): a full-screen sheet over the album list.
 *
 * Full screen rather than a dialog, because every day carries how many photos were taken on it and
 * a dialog's cells are too small to read a number in. Months scroll vertically from the first dated
 * photo to the last, the year strip jumps between years, and each day is tinted by its count so a
 * trip stands out while scrolling past it.
 *
 * The range being picked lives here until Apply, so ✕ leaves the list filtered exactly as it was.
 * Apply is refused for a range holding no photos: the list is never filtered down to nothing.
 */
@Composable
public fun CalendarSheet(
    calendar: CalendarUi,
    /** The range the list is filtered by now, which the sheet opens on. */
    applied: DateRange?,
    onApply: (DateRange) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf(applied?.let { RangeDraft(it) }) }
    val picked = draft?.range
    val photos = picked?.let(calendar::photosIn) ?: 0
    val entries = remember(calendar) { entriesOf(calendar.months) }
    val density = LocalDensity.current
    // A sticky header is drawn over whatever is first, so the sheet opens on the item *above* its
    // month, scrolled by all of that item but one heading's height: the month's title then sits just
    // below the year's. Placed in the initial state rather than scrolled to afterwards, because a
    // scroll lands a frame late and the first frame showed the title hidden under the heading.
    // Every item's height is fixed, so the offset is known before anything is measured.
    val list = remember(entries) {
        val month = applied?.let { CalendarMonth.of(it.start) } ?: calendar.months.lastOrNull()
        val index = month?.let { entries.indexOf(CalendarEntry.Month(it)) } ?: -1
        if (index < 1) return@remember LazyListState()
        val above = when (val item = entries[index - 1]) {
            is CalendarEntry.Year -> YEAR_HEADING_HEIGHT
            is CalendarEntry.Month -> MONTH_TITLE_HEIGHT + DAY_HEIGHT * weeksOf(item.month)
        }
        with(density) { LazyListState(index - 1, above.roundToPx() - YEAR_HEADING_HEIGHT.roundToPx()) }
    }
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize()
            .background(colors.surface)
            // Nothing beneath the sheet may take a tap that lands on it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(Icons.close, "Close", onClose)
            Text(
                "Dates",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            ApplyButton(enabled = picked != null && photos > 0) { picked?.let(onApply) }
        }
        Text(
            when {
                picked == null -> "Tap or drag days, months or years"
                photos == 0 -> "${picked.label} · no photos"
                else -> "${picked.label} · $photos photos"
            },
            fontSize = 13.sp,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        if (calendar.months.isEmpty()) {
            EmptyState("No photo has a date")
        } else {
            YearStrip(entries, list)
            WeekdayHeader()
            CalendarMonths(calendar, draft, { draft = it }, Modifier.weight(1f), list)
        }
    }
}

/**
 * A range while it is being picked. [open] while a first tap waits for the second one to end it.
 */
internal data class RangeDraft(val range: DateRange, val open: Boolean = false)

/** A plain tap on a day: the end of a range a first tap began, or else the start of a new one. */
internal fun RangeDraft?.tapped(day: Day): RangeDraft =
    if (this != null && open) RangeDraft(DateRange.between(range.start, day))
    else RangeDraft(DateRange(day, day), open = true)

/**
 * The day a drag starting on [day] holds still. On an end of a finished range that is the other
 * end, so the drag moves the one under the finger; anywhere else it is [day], and the drag picks a
 * new range from there.
 */
internal fun RangeDraft?.anchorFor(day: Day): Day = when {
    this == null || open || range.start == range.end -> day
    day == range.start -> range.end
    day == range.end -> range.start
    else -> day
}

/** One item of the months list: a year's heading, or one of the months beneath it. */
private sealed interface CalendarEntry {
    val year: Int
    val key: String

    data class Year(override val year: Int) : CalendarEntry {
        override val key: String get() = "y$year"
    }

    data class Month(val month: CalendarMonth) : CalendarEntry {
        override val year: Int get() = month.year
        override val key: String get() = "m${month.year}-${month.month}"
    }
}

private fun entriesOf(months: List<CalendarMonth>): List<CalendarEntry> = buildList {
    for (month in months) {
        if (lastOrNull()?.year != month.year) add(CalendarEntry.Year(month.year))
        add(CalendarEntry.Month(month))
    }
}

/** What a finger is on: the days it stands for, and the one day when it is a day's cell. */
private data class Target(val span: DateRange, val day: Day?)

/**
 * The months, and the gesture that picks a range across them.
 *
 * Three things can be picked, each by a tap: **a day**, **a month** by its title, and **a year** by
 * its heading — which stays pinned above the months while any of that year's are on screen, so the
 * whole year is one tap from anywhere inside it. The year strip only jumps; it never picks.
 *
 * A plain tap on a day sets a start and waits for a second tap as the end; a second tap before the
 * first simply becomes the start. Once a range is finished a tap begins a new one, and a drag that
 * starts on either end moves that end. **Dragging** picks everything from what it starts on to what
 * is under the finger, whole: from one month's title to another's is every day of both and of the
 * months between, and the same for years. Holding near the top or bottom edge scrolls on.
 *
 * On a touch screen a drag is told from a scroll exactly as the upload picker tells them apart, and
 * for the same reason events are read on the initial pass, ahead of the list: a drag that starts
 * sideways, or a press held still, picks; a drag that starts vertically is the list's scroll. A
 * mouse picks with any drag, since a mouse scrolls with its wheel.
 *
 * What is under the finger comes from the list's own layout and this file's fixed sizes: a year's
 * heading is [YEAR_HEADING_HEIGHT], and a month is a [MONTH_TITLE_HEIGHT] title over rows of
 * [DAY_HEIGHT] between [CALENDAR_GUTTER]s. The pinned heading is the list's own sticky header, so
 * the layout reports it where it is drawn — first, ahead of the month scrolling beneath it — and
 * neither heading nor title takes pointer input of its own, so every event reaches the one gesture.
 *
 * A sticky header rather than a heading drawn over the list: an overlay has to wait for the list's
 * layout to know which year to show, and so was missing from the first frame — which is the frame
 * a render, and a person opening the sheet, sees.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CalendarMonths(
    calendar: CalendarUi,
    draft: RangeDraft?,
    onDraft: (RangeDraft) -> Unit,
    modifier: Modifier = Modifier,
    list: LazyListState = rememberLazyListState(),
) {
    val entries = remember(calendar) { entriesOf(calendar.months) }
    // The gesture outlives a composition, so it reads these afresh on every event.
    val latestEntries by rememberUpdatedState(entries)
    val latestDraft by rememberUpdatedState(draft)
    val latestOnDraft by rememberUpdatedState(onDraft)
    val drag = remember { RangeDrag() }
    val density = LocalDensity.current
    val titlePx = with(density) { MONTH_TITLE_HEIGHT.toPx() }
    val dayPx = with(density) { DAY_HEIGHT.toPx() }
    val gutterPx = with(density) { CALENDAR_GUTTER.toPx() }
    val edge = with(density) { CALENDAR_AUTOSCROLL_EDGE.toPx() }

    fun targetAt(at: Offset): Target? {
        val layout = list.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { at.y >= it.offset && at.y < it.offset + it.size }
            ?: return null
        return when (val entry = latestEntries.getOrNull(item.index)) {
            is CalendarEntry.Year -> Target(DateRange.year(entry.year), null)
            is CalendarEntry.Month -> {
                val month = entry.month
                val below = at.y - item.offset - titlePx
                if (below < 0) return Target(month.days, null)
                val columnWidth = (layout.viewportSize.width - 2 * gutterPx) / 7
                val column = ((at.x - gutterPx) / columnWidth).toInt().coerceIn(0, 6)
                val cell = (below / dayPx).toInt() * 7 + column - month.first.weekday
                if (cell !in 0 until month.length) return null
                val day = month.first + cell
                Target(DateRange(day, day), day)
            }
            null -> null
        }
    }

    fun extendTo(target: Target) = latestOnDraft(RangeDraft(drag.anchor.spanning(target.span)))

    // Holding near an edge while picking scrolls, and the range follows what comes under the finger.
    LaunchedEffect(drag.point != null) {
        while (true) {
            val point = drag.point ?: break
            val height = list.layoutInfo.viewportSize.height.toFloat()
            val step = when {
                point.y < edge -> -(edge - point.y) / 3
                point.y > height - edge -> (point.y - (height - edge)) / 3
                else -> 0f
            }
            if (step != 0f) {
                list.scrollBy(step)
                targetAt(point)?.let(::extendTo)
            }
            delay(16)
        }
    }

    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val pressed = targetAt(down.position) ?: return@awaitEachGesture
                val mouse = down.type == PointerType.Mouse
                // true: a drag that picks. false: not this gesture's — a vertical scroll.
                // Still undecided when the long-press timeout passes: a press held still, which picks.
                var picking: Boolean? = null
                var released = false
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (picking == null && !released) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            released = true
                        } else {
                            val moved = change.position - down.position
                            if (moved.getDistance() > viewConfiguration.touchSlop) {
                                picking = mouse || abs(moved.x) > abs(moved.y)
                            }
                        }
                    }
                }
                if (released) {
                    // A month's title or a year's heading picks all of it at once.
                    latestOnDraft(pressed.day?.let { latestDraft.tapped(it) } ?: RangeDraft(pressed.span))
                    return@awaitEachGesture
                }
                if (picking == false) return@awaitEachGesture

                drag.anchor = pressed.day?.let { latestDraft.anchorFor(it).let { day -> DateRange(day, day) } }
                    ?: pressed.span
                extendTo(pressed)
                drag.point = down.position
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == down.id } ?: break
                    // Consumed, so the list does not scroll under a range being picked.
                    change.consume()
                    if (!change.pressed) break
                    drag.point = change.position
                    targetAt(change.position)?.let(::extendTo)
                }
                drag.point = null
            }
        },
    ) {
        // In [entries]' order, so a list index is an index into [entries].
        for (entry in entries) {
            when (entry) {
                is CalendarEntry.Year -> stickyHeader(key = entry.key) {
                    YearHeading(entry.year, calendar.photosInYear(entry.year), draft?.range)
                }
                is CalendarEntry.Month -> item(key = entry.key) {
                    MonthGrid(entry.month, calendar, draft?.range)
                }
            }
        }
    }
}

/** Where a drag is: the finger, while one is down, and what it holds still. */
private class RangeDrag {
    var point: Offset? by mutableStateOf(null)
    var anchor: DateRange = DateRange(Day(0), Day(0))
}

/**
 * A year's heading: its number and photos, and the button-shaped mark saying a tap picks all of it.
 * Filled once the whole year is the range. Drawn in the list above the year's first month, and
 * pinned over the top while its months scroll by.
 */
@Composable
private fun YearHeading(year: Int, photos: Int, picked: DateRange?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val whole = picked == DateRange.year(year)
    val ink = if (whole) colors.onPrimary else colors.onSurface
    Row(
        modifier.fillMaxWidth().height(YEAR_HEADING_HEIGHT)
            .background(if (whole) colors.primary else colors.surfaceContainerLow)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$year", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
        Text(
            "$photos photos",
            fontSize = 11.sp,
            color = if (whole) colors.onPrimary else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp),
        )
        Text(
            if (whole) "✓ Whole year" else "Pick year",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = ink,
            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                .background(if (whole) colors.onPrimary.copy(alpha = 0.22f) else colors.surfaceContainerHigh)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** How many week rows [month] is drawn in: its days, after the blanks before its first weekday. */
private fun weeksOf(month: CalendarMonth): Int = (month.first.weekday + month.length + 6) / 7

@Composable
private fun MonthGrid(month: CalendarMonth, calendar: CalendarUi, picked: DateRange?) {
    val lead = month.first.weekday
    val weeks = weeksOf(month)
    Column(Modifier.fillMaxWidth().padding(horizontal = CALENDAR_GUTTER)) {
        MonthTitle(month, calendar.photosInMonth(month), picked)
        for (week in 0 until weeks) {
            Row(Modifier.fillMaxWidth().height(DAY_HEIGHT).padding(vertical = 2.dp)) {
                for (column in 0 until 7) {
                    val cell = week * 7 + column - lead
                    if (cell in 0 until month.length) {
                        val day = month.first + cell
                        DayCell(day, calendar.photosOn(day), picked?.takeIf { day in it }, Modifier.weight(1f).fillMaxHeight())
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * A month's title, drawn as the button it is: a tap picks the whole month. Filled when the month is
 * exactly the range, outlined when it lies inside a longer one.
 */
@Composable
private fun MonthTitle(month: CalendarMonth, photos: Int, picked: DateRange?) {
    val colors = MaterialTheme.colorScheme
    val whole = picked == month.days
    val inside = !whole && picked != null && month.first >= picked.start && month.last <= picked.end
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.fillMaxWidth().height(MONTH_TITLE_HEIGHT).padding(vertical = 4.dp)
            .clip(shape)
            .background(
                when {
                    whole -> colors.primary
                    inside -> Color.Transparent
                    else -> colors.surfaceContainerHigh
                },
            )
            .then(if (inside) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(month.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (whole) colors.onPrimary else colors.onSurface)
        if (photos > 0) {
            Text(
                if (whole) "✓ $photos" else "$photos",
                fontSize = 11.sp,
                color = if (whole) colors.onPrimary else colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * A day: its number, how many photos were taken on it, and a tint that grows with that count. A day
 * with none is dimmed and carries no number, but can still end a range. The range's two ends are
 * filled; the days between are outlined, so their tints still read.
 */
@Composable
private fun DayCell(day: Day, photos: Int, within: DateRange?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val end = within != null && (day == within.start || day == within.end)
    val shape = when {
        within == null -> RoundedCornerShape(7.dp)
        within.start == within.end -> RoundedCornerShape(9.dp)
        day == within.start -> RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp)
        day == within.end -> RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp)
        else -> RectangleShape
    }
    Column(
        modifier.clip(shape)
            .background(
                when {
                    end -> colors.primary
                    photos > 0 -> colors.primary.copy(alpha = heat(photos))
                    else -> Color.Transparent
                },
            )
            .then(if (within != null && !end) Modifier.border(1.5.dp, colors.primary, shape) else Modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${day.dayOfMonth}",
            fontSize = 13.sp,
            fontWeight = if (end) FontWeight.Bold else FontWeight.Normal,
            color = when {
                end -> colors.onPrimary
                photos > 0 -> colors.onSurface
                else -> colors.onSurfaceVariant.copy(alpha = 0.55f)
            },
        )
        if (photos > 0) {
            Text(
                if (photos < 1_000) "$photos" else "${photos / 1_000}.${photos % 1_000 / 100}k",
                fontSize = 9.sp,
                color = if (end) colors.onPrimary else colors.onSurface.copy(alpha = 0.8f),
            )
        }
    }
}

/** Logarithmic, so a day of 1,200 photos does not wash out a day of 12. */
private fun heat(photos: Int): Float = (0.10f + log10(photos + 1f) * 0.20f).coerceAtMost(0.60f)

@Composable
private fun ApplyButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (enabled) colors.primary else colors.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            "Apply",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
        )
    }
}

/**
 * Every year the calendar spans; the one being scrolled through is marked. A tap jumps to that
 * year's heading and nothing more — picking a year is its heading's job, so a year never has two
 * targets that do different things.
 */
@Composable
private fun YearStrip(entries: List<CalendarEntry>, list: LazyListState) {
    val colors = MaterialTheme.colorScheme
    val years = remember(entries) { entries.filterIsInstance<CalendarEntry.Year>().map { it.year } }
    val showing by remember(entries) { derivedStateOf { entries.getOrNull(list.firstVisibleItemIndex)?.year } }
    val scope = rememberCoroutineScope()
    val strip = rememberLazyListState()
    LaunchedEffect(showing) {
        val at = years.indexOf(showing)
        if (at >= 0) strip.animateScrollToItem((at - 2).coerceAtLeast(0))
    }
    HorizontalDivider(color = colors.outlineVariant, thickness = 0.5.dp)
    LazyRow(
        state = strip,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(years) { year ->
            val current = year == showing
            Text(
                "$year",
                fontSize = 13.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current) colors.onSurface else colors.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (current) colors.surfaceContainerHigh else Color.Transparent)
                    .clickable { scope.launch { list.scrollToItem(entries.indexOf(CalendarEntry.Year(year))) } }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    HorizontalDivider(color = colors.outlineVariant, thickness = 0.5.dp)
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = CALENDAR_GUTTER, vertical = 4.dp)) {
        for (name in listOf("M", "T", "W", "T", "F", "S", "S")) {
            Text(
                name,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
}

/** A year's heading, in the list and pinned over it. */
internal val YEAR_HEADING_HEIGHT = 40.dp

/** A month's title, above its weeks. */
internal val MONTH_TITLE_HEIGHT = 36.dp

/** One week's row of days. */
internal val DAY_HEIGHT = 44.dp

/** Either side of the seven columns. */
internal val CALENDAR_GUTTER = 10.dp

/** How close to the top or bottom a held finger has to be for the months to scroll on. */
private val CALENDAR_AUTOSCROLL_EDGE = 56.dp
