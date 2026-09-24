package net.stho.photos.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album
import net.stho.photos.app.AlbumCache
import net.stho.photos.app.CacheAction
import net.stho.photos.app.DateRange
import net.stho.photos.app.ListEntry
import net.stho.photos.app.Scroll
import net.stho.photos.app.Thumbnails

/**
 * The list's two sizes (§6): the phone's, and the desktop viewer's sidebar (§11), which differs in
 * the cover alone. Everything else — the indents, the headers, the lines closing each group — is
 * what makes it this list, and is the same at both.
 */
public enum class ListSize(internal val cover: Dp) {
    Regular(54.dp),
    Compact(32.dp),
}

/**
 * The album list, and the container one level down — the same list either way.
 *
 * Every level is on it. A container is the header of its own group rather than a row that hides
 * what it holds: its sub-albums follow one indent in, a heavier line closes the group, and while
 * the group scrolls its header stays pinned — stacked above any header nested inside it.
 *
 * It is also the *only* list of albums in the app. The cache controls live here rather than on
 * a second list inside Settings, so nothing has to keep two renderings of the same 288 albums
 * consistent, the hierarchy comes free, and asking for an album happens where you are already
 * looking at it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun AlbumList(
    rows: List<ListEntry>,
    query: String,
    /** The date filter the field shows in place of the text, when one is applied. */
    range: DateRange?,
    searchable: Boolean,
    thumbnails: Thumbnails,
    /** Bumped when a pack lands, so a row already on screen swaps placeholder for photograph. */
    arrivals: Int,
    /** A first sync, with nothing to show yet: `fetched to total`, or null when not loading. */
    loading: Pair<Int, Int>?,
    /**
     * This row's cache state — the whole of what its strip draws. Null where there is no cache,
     * which is the desktop viewer (§11): no strip, and nothing to reveal.
     */
    cache: ((ListEntry.Row) -> AlbumCache)?,
    /** Which controls the row's state affords, revealed by a swipe or by tapping the strip. */
    actions: ((ListEntry.Row) -> List<CacheAction>)?,
    /**
     * Whatever decides the order — the sort, the query and the range. When it changes the list starts
     * again at the top: a keyed lazy list otherwise keeps the row that *was* first on screen, so after
     * a re-sort that row stayed put and everything now ahead of it sat scrolled away above.
     */
    order: Any?,
    /**
     * Where this level's list was left, which it stands at again when it comes back into view. The
     * model forgets it when [order] changes, so a new order still starts at the top.
     */
    scroll: Scroll?,
    /** Where a scroll came to rest: the first row on screen, by key and position, and how far past it. */
    onScrolled: (key: String?, index: Int, offset: Int) -> Unit,
    onSearch: (String) -> Unit,
    /** The field's calendar icon, or a tap on the range it shows. */
    onCalendar: () -> Unit,
    /** The ✕ beside a range. */
    onClearRange: () -> Unit,
    onOpen: (Album) -> Unit,
    onAction: (ListEntry.Row, CacheAction) -> Unit,
    /** Whether a sync is running, whoever started it. */
    syncing: Boolean = false,
    /** Pull-to-refresh: sync, and rebuild the catalog from what lands. */
    onRefresh: () -> Unit = {},
    size: ListSize = ListSize.Regular,
    /** The album a row is showing beside the list, marked as the active one (§11). */
    selected: Uuid? = null,
    /** What the loading state says, when it is not a first sync counting shards. */
    loadingText: String? = null,
    /** Lets a root move the keyboard into the search field — Ctrl+F on the desktop (§11). */
    searchFocus: FocusRequester? = null,
    /** Drawn under the field: the desktop's chip for a list narrowed to the map's view (§11). */
    beneathField: (@Composable () -> Unit)? = null,
    /** What an empty list says in place of the search's or the range's own line. */
    emptyText: String? = null,
    /**
     * Drawn over the list, beside its right edge: the desktop's scroll bar, which is a JVM-only
     * component, so the root that has one passes it in. The phone scrolls by touch and passes none.
     */
    scrollbar: (@Composable BoxScope.(LazyListState) -> Unit)? = null,
) {
    // The spinner answers a pull and nothing else. Every launch syncs too, and a spinner over the
    // list each time the app opens would read as the list not being ready when it is.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(syncing) { if (!syncing) pulled = false }
    Column(Modifier.fillMaxSize()) {
        if (searchable) SearchField(query, range, onSearch, onCalendar, onClearRange, searchFocus)
        beneathField?.invoke()
        PullToRefreshBox(
            isRefreshing = pulled,
            onRefresh = { pulled = true; onRefresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            ListBody(
                rows, query, range, thumbnails, arrivals, loading, cache, actions, order, scroll, onScrolled, onOpen, onAction,
                size, selected, loadingText, emptyText, scrollbar,
            )
        }
    }
}

@Composable
private fun ListBody(
    rows: List<ListEntry>,
    query: String,
    range: DateRange?,
    thumbnails: Thumbnails,
    arrivals: Int,
    loading: Pair<Int, Int>?,
    cache: ((ListEntry.Row) -> AlbumCache)?,
    actions: ((ListEntry.Row) -> List<CacheAction>)?,
    order: Any?,
    scroll: Scroll?,
    onScrolled: (key: String?, index: Int, offset: Int) -> Unit,
    onOpen: (Album) -> Unit,
    onAction: (ListEntry.Row, CacheAction) -> Unit,
    size: ListSize,
    selected: Uuid?,
    loadingText: String?,
    emptyText: String?,
    scrollbar: (@Composable BoxScope.(LazyListState) -> Unit)?,
) {
    Column(Modifier.fillMaxSize()) {
        if (rows.isEmpty() && loading != null) {
            // A first sync fetches every shard in the zone and takes tens of seconds (§4).
            // "No albums yet" during it is simply untrue, and untrue in the worst way: it
            // looks like an empty library rather than like work in progress.
            LoadingState(loading, loadingText)
        } else if (rows.isEmpty()) {
            EmptyState(
                when {
                    emptyText != null -> emptyText
                    range != null -> "No photos from ${range.label}"
                    query.isBlank() -> "No albums yet"
                    else -> "Nothing matches “$query”"
                },
            )
        } else {
            // No side padding on the list itself: the strip is a screen-edge mark and has to
            // reach the edge. The row's content carries the inset instead.
            val list = rememberLazyListState()
            val current by rememberUpdatedState(rows)
            val keys = remember { derivedStateOf { current.map { it.key } } }
            val report by rememberUpdatedState(onScrolled)
            LaunchedEffect(order, scroll?.moves) { list.follow(scroll, keys) { key, index, offset -> report(key, index, offset) } }
            // The one row whose actions are showing, held here rather than per row: while any row
            // is open, a tap anywhere in the list closes it instead of opening an album. Per row,
            // tapping the open row itself opened the album the person was about to act on.
            var revealed by remember { mutableStateOf<Uuid?>(null) }
            val row: @Composable (ListEntry.Row) -> Unit = { entry ->
                ListRow(
                    entry = entry,
                    revealed = revealed == entry.album.id,
                    anyRevealed = revealed != null,
                    onReveal = { revealed = entry.album.id },
                    onDismiss = { revealed = null },
                    thumbnails = thumbnails,
                    arrivals = arrivals,
                    cache = cache?.invoke(entry),
                    actions = actions?.invoke(entry).orEmpty(),
                    onAction = { onAction(entry, it) },
                    onOpen = { onOpen(entry.album) },
                    size = size,
                    selected = entry.album.id == selected,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = ROW_DIVIDER)
            }
            val headers = remember(rows) {
                rows.filterIsInstance<ListEntry.Row>().filter { it.header }.associateBy { it.album.id }
            }
            val slot = with(LocalDensity.current) { (HEADER_HEIGHT + ROW_DIVIDER).roundToPx() }
            val pinned by remember(rows, slot) { derivedStateOf { pinnedHeaders(list.layoutInfo, rows, slot) } }

            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = list) {
                    items(rows, key = { it.key }) { entry ->
                        when (entry) {
                            is ListEntry.Row -> row(entry)
                            is ListEntry.End -> HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline,
                                thickness = GROUP_DIVIDER,
                            )
                        }
                    }
                }
                // The same composable as the header in the list, so a pinned header is not a
                // picture of one: it opens its container and reveals its actions exactly as the
                // row beneath it would.
                Column(Modifier.fillMaxWidth()) {
                    pinned.forEach { id -> headers[id]?.let { header -> key(id) { row(header) } } }
                }
                scrollbar?.invoke(this, list)
            }
        }
    }
}

/**
 * The headers to pin, outermost first, for the rows now under the top of the list.
 *
 * Asked level by level: what sits just below the headers already pinned decides whether one more
 * goes on the stack. That is what makes a nested header join the stack only once its own group
 * reaches the top, and leave it once the line closing that group has scrolled beneath.
 */
internal fun pinnedHeaders(info: LazyListLayoutInfo, rows: List<ListEntry>, slot: Int): List<Uuid> {
    val visible = info.visibleItemsInfo
    if (visible.isEmpty() || slot <= 0) return emptyList()
    fun at(y: Int): ListEntry? = visible.firstOrNull { y < it.offset + it.size }?.let { rows.getOrNull(it.index) }
    fun chain(entry: ListEntry): List<Uuid> = when (entry) {
        is ListEntry.Row -> if (entry.header) entry.ancestors + entry.album.id else entry.ancestors
        is ListEntry.End -> entry.ancestors + entry.container
    }
    var stack = emptyList<Uuid>()
    while (true) {
        val beneath = at(stack.size * slot) ?: break
        val chain = chain(beneath)
        if (chain.size <= stack.size) break
        stack = chain.take(stack.size + 1)
    }
    return stack
}

/**
 * One line of the list: an album with its cover, or a container's header, and in both cases the
 * one strip that says everything about the cache.
 *
 * The row does not narrate itself. There is no size, no state and no byte count in the text,
 * because the strip carries all four readings — grey nothing held, part blue this much held,
 * part blue pulsing and moving now, full green the whole album is offline. That replaced a
 * separate gauge plus a line of prose on every row.
 */
@Composable
private fun ListRow(
    entry: ListEntry.Row,
    /** This row's actions are showing. */
    revealed: Boolean,
    /** Some row's actions are showing, so a tap here dismisses them rather than opening. */
    anyRevealed: Boolean,
    onReveal: () -> Unit,
    onDismiss: () -> Unit,
    thumbnails: Thumbnails,
    arrivals: Int,
    /** Null where there is no cache: no strip then, and nothing for a swipe to reveal. */
    cache: AlbumCache?,
    actions: List<CacheAction>,
    onAction: (CacheAction) -> Unit,
    onOpen: () -> Unit,
    size: ListSize,
    /** The album shown beside the list: the one active row, so it takes the active colour (§6). */
    selected: Boolean,
) {
    val indent = 16.dp + INDENT * entry.depth
    Row(
        if (entry.header) {
            Modifier.fillMaxWidth().height(HEADER_HEIGHT).background(MaterialTheme.colorScheme.surfaceContainerLow)
        } else {
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .then(if (entry.header) Modifier.fillMaxHeight() else Modifier)
                .clickable { if (anyRevealed) onDismiss() else onOpen() }
                .padding(start = indent, top = if (entry.header) 0.dp else 7.dp, bottom = if (entry.header) 0.dp else 7.dp)
                // Swipe-left reveals the actions. Swipe-RIGHT is deliberately unused: it
                // collides with the interactive back gesture, which matters at every level of
                // this list rather than only at the root.
                .pointerInput(entry.album.id, actions) {
                    detectHorizontalDragGestures { _, delta ->
                        if (delta < -4f && actions.isNotEmpty()) onReveal()
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.header) HeaderContent(entry) else AlbumContent(entry, thumbnails, arrivals, cache?.moving == true, size)
        }

        if (cache == null) {
            // No cache, no strip: the row's content runs to the edge, with the inset it keeps at the start.
            Box(Modifier.width(16.dp))
        } else if (revealed) {
            // Icons only, no labels: which ones appear is the album's state, so each row offers
            // exactly what applies and there is nothing to read.
            actions.forEach { action ->
                RevealedAction(action) {
                    onAction(action)
                    onDismiss()
                }
            }
        } else {
            CacheStrip(
                cache,
                // The strip is also the tap route to the actions, so its hit area is grown to
                // the 44dp minimum without growing the 4dp mark. Being visible is what makes
                // this discoverable where the bare gesture is not: people tap what they see.
                Modifier.clickable(enabled = actions.isNotEmpty()) { onReveal() },
            )
        }
    }
}

/**
 * A container: no cover, its name set as a section title, and what the group beneath it holds.
 *
 * The name is measured first and the count gets what is left. Weighting the name instead let an
 * unweighted count take the width before it, and on a narrow screen a header drew no name at all.
 */
@Composable
private fun RowScope.HeaderContent(entry: ListEntry.Row) {
    Text(
        entry.album.name.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.7.sp,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        entry.contents,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp, end = 8.dp),
    )
}

@Composable
private fun AlbumContent(entry: ListEntry.Row, thumbnails: Thumbnails, arrivals: Int, moving: Boolean, size: ListSize) {
    // Read lazily, per visible row: resolving a cover opens that album's pack, and doing it for
    // all 288 up front would be 288 file reads for the six rows anyone can actually see.
    val cover = remember(entry.album.id, arrivals) { thumbnails.cover(entry.album) }
    AlbumCover(cover, moving = moving, size = size.cover)
    Column(Modifier.padding(start = 12.dp)) {
        Text(
            entry.album.name,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            entry.contents,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The cover, or a photograph-shaped mark standing in for one that has not arrived.
 *
 * A glyph rather than an abstract texture, so the tile says what it is going to be — and it
 * pulses only while a worker is on this album, which is the same rule the strip uses.
 */
@Composable
private fun AlbumCover(cover: ByteArray?, moving: Boolean, size: Dp) {
    Box(
        Modifier.size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Thumbnail(cover, Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(size * 0.45f).alpha(pulseAlpha(moving)),
            )
        }
    }
}

/**
 * The one mark that carries the whole cache vocabulary.
 *
 * Full green when every blob is on disk; otherwise a grey track filled from the bottom in
 * proportion to the **bytes** held, not the count — an album whose one video is missing is not
 * nearly done, and a count would say it was. It pulses only while a worker is actually on this
 * album, which is what keeps a pulsing row worth looking at in a list of 288.
 */
@Composable
private fun CacheStrip(cache: AlbumCache, modifier: Modifier = Modifier) {
    // The hit area is 44dp so the actions have a real touch target; the mark itself is 7dp and
    // sits at the far end of it, flush with the screen edge and running the full height of the
    // row, because that is where a status mark for the whole row belongs.
    Box(modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier.width(7.dp).fillMaxHeight()
                // `alpha` BEFORE `background`, not after: modifiers wrap left-to-right, so a
                // background declared first is drawn outside the alpha layer and the fade never
                // touches it. That is why this looked static however the transition behaved.
                .alpha(pulseAlpha(cache.moving))
                .background(
                    if (cache.complete) MaterialTheme.colorScheme.start
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // The whole mark pulses, not just the filled part: a download that has not landed
            // its first blob is 0% filled, and pulsing only the fill meant the one moment you
            // most want to see movement showed none at all.
            if (!cache.complete && cache.fraction > 0f) {
                Box(
                    Modifier.fillMaxWidth()
                        .fillMaxHeight(cache.fraction)
                        .background(MaterialTheme.colorScheme.active),
                )
            }
        }
    }
}

@Composable
private fun RevealedAction(action: CacheAction, onClick: () -> Unit) {
    val tint = when (action) {
        CacheAction.Download -> MaterialTheme.colorScheme.start
        CacheAction.Pause -> MaterialTheme.colorScheme.active
        CacheAction.Clear -> MaterialTheme.colorScheme.error
    }
    val icon = when (action) {
        CacheAction.Download -> Icons.download
        CacheAction.Pause -> Icons.pause
        CacheAction.Clear -> Icons.trash
    }
    Box(
        Modifier.width(52.dp).fillMaxHeight().background(tint).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = action.name, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/**
 * Motion means one thing everywhere in the app: bytes are moving for this item right now.
 *
 * The transition is created **unconditionally** and only its value is gated. An earlier version
 * returned early when nothing was moving, which meant `rememberInfiniteTransition` was called in
 * some compositions and not others — a conditional composable call, which breaks positional
 * memoization, and the animation simply never ran.
 */
@Composable
internal fun pulseAlpha(moving: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "moving")
    val fade by transition.animateFloat(
        initialValue = 1f,
        targetValue = PULSE_FLOOR,
        // Eased rather than linear, and reversed: a linear ramp turns hard at both ends and
        // reads as a flicker, while easing in and out makes it breathe. The floor is high
        // enough that the mark never looks like it is disappearing.
        animationSpec = infiniteRepeatable(
            tween(PULSE_MS, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    return if (moving) fade else 1f
}

private const val PULSE_MS = 620
private const val PULSE_FLOOR = 0.42f

/** A header's fixed height, which is also one slot of the pinned stack. */
private val HEADER_HEIGHT = 40.dp

/** One level of nesting. */
private val INDENT = 22.dp

private val ROW_DIVIDER = 0.5.dp

/** Heavier than a row's divider, so the end of a group reads as the end of the group. */
private val GROUP_DIVIDER = 2.5.dp

/**
 * The field owns its text and cursor, and the model hears each change. Fed back from the model's
 * state instead, a keystroke's value arrived a recomposition late and on iOS put the cursor back
 * before the character just typed. Nothing but typing changes the query while the field exists: a
 * range applied from the calendar takes the field's place and clears the query, so the field comes
 * back empty when the range goes.
 *
 * The calendar is the field's trailing icon rather than a fifth icon on the bar (§6): it belongs to
 * the search, and the bar's four are fixed. With a range applied the field shows it, read-only — a
 * tap reopens the calendar on it, and ✕ clears it.
 */
@Composable
public fun SearchField(
    query: String,
    range: DateRange?,
    onSearch: (String) -> Unit,
    onCalendar: () -> Unit,
    onClearRange: () -> Unit,
    focus: FocusRequester?,
    /** What the empty field says: the phone's searches albums, the desktop's people too (§12). */
    placeholder: String = "Search albums",
) {
    val frame = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        .height(48.dp).clip(RoundedCornerShape(10.dp))
        .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
    if (range != null) {
        Row(
            frame.background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onCalendar).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.calendar, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                range.label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            FieldIcon(Icons.close, "Clear dates", onClearRange)
        }
    } else {
        var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
        TextField(
            value = field,
            onValueChange = {
                field = it
                onSearch(it.text)
            },
            singleLine = true,
            placeholder = { Text(placeholder, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.search, contentDescription = null, Modifier.size(16.dp)) },
            trailingIcon = { FieldIcon(Icons.calendar, "Filter by date", onCalendar) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = frame,
        )
    }
}

/** An icon inside the field, with a touch target the 18dp glyph alone would not give it. */
@Composable
private fun FieldIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/**
 * The cold start §4 describes: shards first, then the catalog is built.
 *
 * Determinate wherever it can be — the sync knows how many shards it is fetching — and
 * indeterminate for the moment before the LIST comes back, because a bar sitting at zero says
 * less than a bar that is moving.
 */
@Composable
private fun LoadingState(progress: Pair<Int, Int>, text: String?) {
    val (fetched, total) = progress
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (total > 0) {
            LinearProgressIndicator(
                progress = { fetched.toFloat() / total },
                modifier = Modifier.fillMaxWidth(0.6f),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(0.6f))
        }
        Text(
            text ?: if (total > 0) "Fetching the catalog · $fetched of $total albums" else "Reading the zone…",
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * §10 requires the app to render an album with zero photos: emptying a directory leaves one.
 * It is a message, never an empty grid, so it cannot be mistaken for a pack that has not
 * arrived yet.
 */
@Composable
public fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
