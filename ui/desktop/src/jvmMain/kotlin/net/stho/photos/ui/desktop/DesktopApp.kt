package net.stho.photos.ui.desktop

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.utf16CodePoint
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
import net.stho.photos.app.Showing
import net.stho.photos.app.Thumbnails
import net.stho.photos.app.TileSize
import net.stho.photos.ui.screens.AlbumList
import net.stho.photos.ui.screens.BarButton
import net.stho.photos.ui.screens.CalendarSheet
import net.stho.photos.ui.screens.Icons
import net.stho.photos.ui.screens.ListSize
import net.stho.photos.ui.screens.MapCanvas
import net.stho.photos.ui.screens.LocalPlayToggle
import net.stho.photos.ui.screens.PlayToggle
import net.stho.photos.ui.screens.NavBar
import net.stho.photos.ui.screens.PhotoGrid
import net.stho.photos.ui.screens.SearchField
import net.stho.photos.ui.screens.Viewer

/**
 * The desktop viewer (§11): the album list on the left; on the right the library's map until an
 * album is selected, then that album as a grid or on its map, and a photo open over it when one is.
 *
 * Built from `:ui:shared`'s components and nothing of the phone's: §6's list at its compact size,
 * its nav bar over both panes, its grid and its viewer. What is the desktop's own is the layout
 * and the keyboard.
 *
 * **The keyboard follows the pane it is in.** In the sidebar ↑/↓ change the album and Enter moves
 * into the grid; on the grid the arrows move a focus ring and Enter opens that photo; in the
 * viewer ←/→ page, Space plays or pauses a video or a Live Photo, and Esc goes back — from a photo
 * to its album, from an album to the library's map. Ctrl+F, F5, Ctrl± and Esc work from anywhere.
 * The maps take no keys: a drag, the wheel and a click drive them.
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
    val naming = remember { FocusRequester() }
    var inGrid by remember { mutableStateOf(false) }
    // How many tiles a row holds, which is what ↑/↓ on the grid move by. The grid reports it as
    // it lays out: the column count follows the pane's width (§11).
    var columns by remember { mutableStateOf(1) }

    LaunchedEffect(Unit) { sidebar.requestFocus() }

    // Compose's default thumb is black at 12%, which on the dark scheme's black ground is no thumb
    // at all: the scroll bars take the theme's text colour instead.
    val text = MaterialTheme.colorScheme.onSurface
    val scrollbars = remember(text) {
        ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 8.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 300,
            unhoverColor = text.copy(alpha = 0.30f),
            hoverColor = text.copy(alpha = 0.60f),
        )
    }
    CompositionLocalProvider(LocalScrollbarStyle provides scrollbars) {
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
                        .onKeyEvent { event -> event.isPress && inAlbum(event, ui, model, columns, sidebar, play, naming) }
                        .focusable(),
                ) {
                    CompositionLocalProvider(LocalPlayToggle provides play) {
                        AlbumPane(ui, model, thumbnails, focused = inGrid, naming = naming, onColumns = { columns = it })
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
}

/**
 * The album list, under its bar: sort, the library's map and refresh over "Albums", the field
 * beneath, and under it the chip while the map's view narrows the list (§11).
 */
@Composable
private fun ColumnScope.Sidebar(ui: DesktopUi, model: DesktopModel, thumbnails: Thumbnails, search: FocusRequester) {
    // "Photos": the sidebar holds people and albums both, each under its own heading.
    NavBar("Photos", ui.albumsSubtitle, onBack = null) {
        BarButton(Icons.sort, "Sort", model::cycleSort)
        BarButton(Icons.map, "Map", model::showLibrary)
        BarButton(Icons.refresh, "Refresh", model::refresh)
    }
    Problems(ui)
    // At the top, over people and albums both: one field filters the two lists (§12).
    SearchField(ui.query, ui.range, model::search, model::openCalendar, model::clearRange, search, placeholder = "Search people and albums")
    // With the albums folded away the people take the whole height; beside them, a share of it.
    PeopleSection(ui, model, if (ui.albumsOpen) Modifier.heightIn(max = PEOPLE_HEIGHT) else Modifier.weight(1f, fill = false))
    SectionHeader("ALBUMS", open = ui.albumsOpen, onClick = model::toggleAlbums)
    if (ui.albumsOpen) Box(Modifier.weight(1f)) {
        AlbumList(
            rows = ui.rows,
            query = ui.query,
            range = ui.range,
            // The field is above the people, not above the albums alone.
            searchable = false,
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
            beneathField = if (ui.inView != null) ({ InViewChip(model::clearInView) }) else null,
            emptyText = if (ui.inView != null) "No albums in this part of the map" else null,
            scrollbar = { list ->
                VerticalScrollbar(
                    rememberScrollbarAdapter(list),
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            },
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

/**
 * The list narrowed to the map's view, said where the field is, as a date range is — and ✕ to
 * widen it again, which also frames the map on the whole library once more (§11).
 */
@Composable
private fun InViewChip(onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.map, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            "In map view",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.close, contentDescription = "Show every album", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * The album pane: the library's map while no album is selected; otherwise the album's grid or its
 * map under its bar, or the photo open over it under a one-row bar (§11).
 */
@Composable
private fun AlbumPane(
    ui: DesktopUi,
    model: DesktopModel,
    thumbnails: Thumbnails,
    focused: Boolean,
    naming: FocusRequester,
    onColumns: (Int) -> Unit,
) {
    // A person or a group, before the album or the map (§12).
    if (ui.showing != null && ui.open == null) {
        FacesPane(ui, model, focused, naming, onColumns)
        return
    }
    val album = ui.selected
    if (album == null) {
        // No icons of its own, but the row they would sit in keeps the title level with the sidebar's.
        NavBar("Map", ui.librarySubtitle, onBack = null) { Spacer(Modifier.size(32.dp)) }
        MapCanvas(
            view = ui.libraryView,
            map = ui.libraryMap,
            photoThumbnails = emptyMap(),
            thumbnails = thumbnails,
            arrivals = 0,
            onViewport = model::mapViewport,
            onCameraMoved = model::cameraMoved,
            onTap = model::tapMap,
        )
        return
    }
    val open = ui.open
    if (open == null && ui.photoMap) {
        // The toggle shows the view it switches to, and a map has no tile size to pick (§6).
        NavBar(album.name, ui.photosSubtitle, onBack = null) {
            BarButton(Icons.grid, "Grid", model::togglePhotoMap)
        }
        // Keyed by the album, so no camera carries from one album's map to the next.
        key(album.id) {
            MapCanvas(
                view = ui.albumView,
                map = ui.albumMap,
                photoThumbnails = ui.thumbnails,
                thumbnails = thumbnails,
                arrivals = 0,
                onViewport = model::mapViewport,
                onCameraMoved = model::cameraMoved,
                onTap = model::tapMap,
            )
        }
    } else if (open == null) {
        NavBar(album.name, ui.photosSubtitle, onBack = null) {
            BarButton(Icons.map, "Map", model::togglePhotoMap)
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
                scrollbar = { grid ->
                    VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                },
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
            overlay = { photo -> FaceBoxes(ui, model, photo) },
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
    // Ctrl+Z takes back the last decision about a face (§12) — but not while a name is being
    // typed, where it is the text field's own.
    event.isCtrlPressed && event.key == Key.Z && ui.naming == null && ui.faceMenu == null -> {
        model.undo()
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
        ui.naming != null && ui.open == null -> {
            model.editName(null)
            true
        }
        // A drawn box waiting for its name first, then drawing itself, before the photo closes.
        ui.drawnBox != null -> {
            model.cancelDrawn()
            true
        }
        ui.drawing -> {
            model.toggleDrawing()
            true
        }
        ui.showing != null && ui.open == null && model.clearFaceSelection() -> true
        ui.open != null -> {
            model.closePhoto()
            true
        }
        else -> {
            // Out of the album, back to the library's map; out of the grid or the search field,
            // back to the list.
            model.showLibrary()
            sidebar.requestFocus()
            true
        }
    }
    // The open photo's face boxes (§12), and drawing one the detector missed.
    ui.open != null && event.key == Key.F && !event.isCtrlPressed && ui.drawnBox == null -> {
        model.toggleFaceBoxes()
        true
    }
    ui.open != null && event.key == Key.D && !event.isCtrlPressed && ui.drawnBox == null -> {
        model.toggleDrawing()
        true
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
    // "Unknown" opens and closes like a tree (§12).
    Key.DirectionRight -> {
        model.expandUnknown(true)
        true
    }
    Key.DirectionLeft -> {
        model.expandUnknown(false)
        true
    }
    Key.Enter, Key.NumPadEnter -> {
        val something = ui.selected != null || ui.showing != null
        if (something) album.requestFocus()
        something
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
    naming: FocusRequester,
): Boolean {
    // Space in the viewer is the open video's or Live Photo's play/pause; a still ignores it.
    if (ui.open != null && event.key == Key.Spacebar) {
        play.press()
        return true
    }
    if (ui.showing != null && ui.open == null) return inFaces(event, ui, model, columns, sidebar, naming)
    // The maps take no keys: only the grid has a focus ring to move.
    if (ui.open != null || ui.selected == null || ui.photoMap) return false
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
    model.moveFocus(delta, columns)
    return true
}

/**
 * A person's or an unknown group's faces (§12). Arrows move, Shift+arrows select a range from
 * where it started, Ctrl+A selects all. On a person Enter confirms the selection as them — or,
 * all confirmed already, withdraws it — N says it is not them, I ignores it, and Space opens the
 * focused face's photo. On a group Space takes the focused face in or out of the selection,
 * typing names it (Enter in the field), Enter on the grid opens the photo, and Delete ignores,
 * since every letter there is the name's.
 */
private fun inFaces(
    event: KeyEvent,
    ui: DesktopUi,
    model: DesktopModel,
    columns: Int,
    sidebar: FocusRequester,
    naming: FocusRequester,
): Boolean {
    val group = ui.showing is Showing.Group
    val delta = when (event.key) {
        Key.DirectionLeft -> -1
        Key.DirectionRight -> 1
        Key.DirectionUp -> -columns
        Key.DirectionDown -> columns
        else -> null
    }
    if (delta != null) {
        if (delta == -1 && (ui.faceFocus ?: 0) == 0 && ui.faceFocus != null && !event.isShiftPressed) {
            sidebar.requestFocus()
        } else {
            model.moveFaceFocus(delta, extend = event.isShiftPressed)
        }
        return true
    }
    when {
        // The naming menu at the focused face: Ctrl+Enter, or the desktop's own menu keys.
        (event.isCtrlPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter)) ||
            event.key == Key.Menu || (event.isShiftPressed && event.key == Key.F10) -> model.contextFace()
        event.isCtrlPressed && event.key == Key.A -> model.selectAllFaces()
        // On a person Space opens and Enter confirms; on a group Space picks and Enter names.
        !group && event.key == Key.Spacebar -> model.openFace()
        !group && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> model.confirmChosen()
        group && event.key == Key.Spacebar -> model.toggleFocused()
        group && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> model.openFace()
        event.key == Key.Delete || event.key == Key.Backspace -> model.ignoreChosen()
        !group && event.key == Key.N -> model.rejectChosen()
        !group && event.key == Key.I -> model.ignoreChosen()
        group && !event.isCtrlPressed && event.utf16CodePoint.toChar().isLetterOrDigit() -> {
            model.editName((ui.naming ?: "") + event.utf16CodePoint.toChar())
            runCatching { naming.requestFocus() }
        }
        else -> return false
    }
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
private val PEOPLE_HEIGHT = 360.dp
private val CALENDAR_WIDTH = 760.dp
