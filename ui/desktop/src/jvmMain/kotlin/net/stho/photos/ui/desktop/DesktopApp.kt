package net.stho.photos.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.stho.photos.app.DesktopModel
import net.stho.photos.app.DesktopUi
import net.stho.photos.app.Thumbnails
import net.stho.photos.app.TileSize
import net.stho.photos.ui.screens.AlbumList
import net.stho.photos.ui.screens.BarButton
import net.stho.photos.ui.screens.CalendarSheet
import net.stho.photos.ui.screens.EmptyState
import net.stho.photos.ui.screens.Icons
import net.stho.photos.ui.screens.ListSize
import net.stho.photos.ui.screens.LocalPlayToggle
import net.stho.photos.ui.screens.PlayToggle
import net.stho.photos.ui.screens.NavBar
import net.stho.photos.ui.screens.PhotoGrid
import net.stho.photos.ui.screens.Viewer

/**
 * The desktop viewer (§11): the album list on the left, one album on the right, and a photo open
 * over the album when one is.
 *
 * Built from `:ui:shared`'s components and nothing of the phone's: §6's list at its compact size,
 * its nav bar over both panes, its grid and its viewer. What is the desktop's own is the layout
 * and the keyboard.
 *
 * **The keyboard follows the pane it is in.** In the sidebar ↑/↓ change the album and Enter moves
 * into the grid; on the grid the arrows move a focus ring and Enter opens that photo; in the
 * viewer ←/→ page, Space plays or pauses a video or a Live Photo, and Esc goes back. Ctrl+F, F5,
 * Ctrl± and Esc work from anywhere.
 */
@Composable
public fun DesktopApp(
    model: DesktopModel,
    thumbnails: Thumbnails,
    /** What Space presses in the viewer; the open video or Live Photo listens to it. */
    play: PlayToggle = remember { PlayToggle() },
) {
    val ui by model.state.collectAsState()
    val sidebar = remember { FocusRequester() }
    val album = remember { FocusRequester() }
    val search = remember { FocusRequester() }
    var inGrid by remember { mutableStateOf(false) }
    // How many tiles a row holds, which is what ↑/↓ on the grid move by. The grid reports it as
    // it lays out: the column count follows the pane's width (§11).
    var columns by remember { mutableStateOf(1) }

    LaunchedEffect(Unit) { sidebar.requestFocus() }

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onPreviewKeyEvent { event -> event.isPress && anywhere(event, ui, model, search, sidebar) },
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(SIDEBAR_WIDTH).fillMaxHeight()
                    .focusOnPress(sidebar)
                    .focusRequester(sidebar)
                    .onFocusChanged { if (it.hasFocus) inGrid = false }
                    .onKeyEvent { event -> event.isPress && inSidebar(event, ui, model, album) }
                    .focusable(),
            ) {
                Sidebar(ui, model, thumbnails, search)
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .focusOnPress(album)
                    .focusRequester(album)
                    .onFocusChanged { if (it.hasFocus) inGrid = true }
                    .onKeyEvent { event -> event.isPress && inAlbum(event, ui, model, columns, sidebar, play) }
                    .focusable(),
            ) {
                CompositionLocalProvider(LocalPlayToggle provides play) {
                    AlbumPane(ui, model, focused = inGrid, onColumns = { columns = it })
                }
            }
        }
        ui.calendar?.let { calendar ->
            // Over the whole window, on a scrim, and as tall as it is: §6's sheet with room around it.
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .clickable(onClick = model::closeCalendar),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.sizeIn(maxWidth = CALENDAR_WIDTH).fillMaxHeight().padding(vertical = 24.dp)
                        .clip(RoundedCornerShape(14.dp))
                        // Taps inside the sheet are the sheet's, not the scrim's: taken here, so one
                        // on its empty ground does not fall through and close it.
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    CalendarSheet(calendar, ui.range, onApply = { model.applyRange(it) }, onClose = model::closeCalendar)
                }
            }
        }
    }
}

/** The album list, under its bar: sort and refresh over "Albums", the field beneath (§11). */
@Composable
private fun ColumnScope.Sidebar(ui: DesktopUi, model: DesktopModel, thumbnails: Thumbnails, search: FocusRequester) {
    NavBar("Albums", ui.albumsSubtitle, onBack = null) {
        BarButton(Icons.sort, "Sort", model::cycleSort)
        BarButton(Icons.refresh, "Refresh", model::refresh)
    }
    Problems(ui)
    Box(Modifier.weight(1f)) {
        AlbumList(
            rows = ui.rows,
            query = ui.query,
            range = ui.range,
            searchable = true,
            thumbnails = thumbnails,
            arrivals = 0,
            loading = if (ui.loading && ui.rows.isEmpty()) 0 to 0 else null,
            cache = null,
            actions = null,
            order = Triple(ui.sort, ui.query, ui.range),
            scroll = ui.listScroll,
            onScrolled = model::listScrolled,
            onSearch = model::search,
            onCalendar = model::openCalendar,
            onClearRange = model::clearRange,
            onOpen = model::select,
            onAction = { _, _ -> },
            syncing = ui.loading,
            onRefresh = model::refresh,
            size = ListSize.Compact,
            selected = ui.selected?.id,
            loadingText = "Reading the library…",
            searchFocus = search,
        )
    }
}

/** What the last rebuild could not do, said where the list is rather than hidden. */
@Composable
private fun Problems(ui: DesktopUi) {
    val lines = listOfNotNull(
        ui.failure?.let { "Could not read the library: $it" },
        ui.skipped.takeIf { it > 0 }?.let { "$it album(s) could not be read by this build" },
    )
    for (line in lines) {
        Text(
            line,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** One album: its grid under its bar, or the photo open over it under a one-row bar (§11). */
@Composable
private fun AlbumPane(ui: DesktopUi, model: DesktopModel, focused: Boolean, onColumns: (Int) -> Unit) {
    val album = ui.selected
    if (album == null) {
        EmptyState(if (ui.loading) "" else "Choose an album")
        return
    }
    val open = ui.open
    if (open == null) {
        NavBar(album.name, ui.photosSubtitle, onBack = null) {
            TileSwitch(ui.tile, model::tile)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The grid's own 2dp inset either side, then as many tiles as fit at the size asked for.
            val count = ((maxWidth - 4.dp) / ui.tile.dp.dp).toInt().coerceAtLeast(1)
            LaunchedEffect(count) { onColumns(count) }
            PhotoGrid(
                photos = ui.photos,
                thumbnails = ui.thumbnails,
                columns = count,
                scroll = ui.scroll,
                onScrolled = model::scrolled,
                onDensity = model::zoom,
                onOpen = model::openPhoto,
                focused = ui.focus.takeIf { focused },
            )
        }
    } else {
        ViewerBar(album.name, ui.viewerSubtitle, onBack = model::closePhoto)
        Viewer(
            photos = ui.photos,
            index = open,
            preview = ui.preview,
            videoPath = ui.videoPath,
            livePair = ui.livePair,
            thumbnails = ui.thumbnails,
            moving = false,
            nearby = ui.nearby,
            // The photograph alone — no filmstrip, no date line — with the bar above it.
            landscape = true,
            onBack = model::closePhoto,
            onSelect = model::showPhoto,
            backOverPhoto = false,
            thumbnailUntilPreview = true,
        )
    }
}

/**
 * The album's bar collapsed to one row while a photo is open (§11): back, and the album's name
 * over where this photo is in it, its file and when it was taken.
 */
@Composable
private fun ViewerBar(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.back, "Back", onBack)
        Column(Modifier.weight(1f).padding(end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The grid's S/M/L: three sizes, the chosen one in the active colour (§6). */
@Composable
private fun TileSwitch(current: TileSize, onPick: (TileSize) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (size in TileSize.entries) {
            val chosen = size == current
            Text(
                size.name.take(1),
                fontSize = 13.sp,
                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onPick(size) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------------- the keyboard

private val KeyEvent.isPress: Boolean get() = type == KeyEventType.KeyDown

/** What works wherever the keyboard is: search, refresh, the tile size, and Esc. */
private fun anywhere(
    event: KeyEvent,
    ui: DesktopUi,
    model: DesktopModel,
    search: FocusRequester,
    sidebar: FocusRequester,
): Boolean = when {
    event.isCtrlPressed && event.key == Key.F -> {
        runCatching { search.requestFocus() }
        true
    }
    event.key == Key.F5 -> {
        model.refresh()
        true
    }
    event.isCtrlPressed && (event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd) -> {
        model.zoom(closer = true)
        true
    }
    event.isCtrlPressed && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> {
        model.zoom(closer = false)
        true
    }
    event.key == Key.Escape -> when {
        ui.calendar != null -> {
            model.closeCalendar()
            true
        }
        ui.open != null -> {
            model.closePhoto()
            true
        }
        else -> {
            // Out of the grid, or out of the search field, and back to the list.
            sidebar.requestFocus()
            true
        }
    }
    ui.open != null && event.key == Key.DirectionLeft -> {
        model.step(forward = false)
        true
    }
    ui.open != null && event.key == Key.DirectionRight -> {
        model.step(forward = true)
        true
    }
    else -> false
}

/** ↑/↓ change the album, skipping headers; Enter moves into its grid. */
private fun inSidebar(event: KeyEvent, ui: DesktopUi, model: DesktopModel, album: FocusRequester): Boolean = when (event.key) {
    Key.DirectionUp -> {
        model.selectAdjacent(-1)
        true
    }
    Key.DirectionDown -> {
        model.selectAdjacent(1)
        true
    }
    Key.Enter, Key.NumPadEnter -> {
        if (ui.selected != null) album.requestFocus()
        ui.selected != null
    }
    else -> false
}

/** On the grid the arrows move the focus ring and Enter opens it. */
private fun inAlbum(
    event: KeyEvent,
    ui: DesktopUi,
    model: DesktopModel,
    columns: Int,
    sidebar: FocusRequester,
    play: PlayToggle,
): Boolean {
    // Space in the viewer is the open video's or Live Photo's play/pause; a still ignores it.
    if (ui.open != null && event.key == Key.Spacebar) {
        play.press()
        return true
    }
    if (ui.open != null || ui.selected == null) return false
    val delta = when (event.key) {
        Key.DirectionLeft -> -1
        Key.DirectionRight -> 1
        Key.DirectionUp -> -columns
        Key.DirectionDown -> columns
        Key.Enter, Key.NumPadEnter -> {
            model.openFocused()
            return true
        }
        else -> return false
    }
    // Left from the first tile is back into the list.
    if (delta == -1 && (ui.focus ?: 0) == 0 && ui.focus != null) {
        sidebar.requestFocus()
        return true
    }
    model.moveFocus(delta)
    return true
}

/** A press anywhere in a pane puts the keyboard there, without taking the press from what is under it. */
private fun Modifier.focusOnPress(requester: FocusRequester): Modifier = pointerInput(requester) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        runCatching { requester.requestFocus() }
    }
}

private val SIDEBAR_WIDTH = 340.dp
private val CALENDAR_WIDTH = 760.dp
