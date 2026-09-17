package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.stho.photos.app.AppModel
import net.stho.photos.app.AppUi
import net.stho.photos.app.Screen
import net.stho.photos.app.SyncStatus
import net.stho.photos.app.Thumbnails
import net.stho.photos.app.UploadModel
import net.stho.photos.storage.StorageUrl

/**
 * The whole app: one back stack, one model, no tab bar (§6).
 *
 * The album list is the root; everything else is reached from its nav bar. Screens not yet
 * built say so plainly rather than pretending — E.1 is a milestone, not a stub farm.
 *
 * The theme is **not** applied here. It is chosen once, by whoever hosts this — the window, or
 * a test rendering both schemes — so that "follows the system theme" is a decision the root
 * makes rather than one buried where nothing can override it.
 */
@Composable
public fun App(
    model: AppModel,
    thumbnails: Thumbnails,
    /** Shown, masked, on Settings › Account — §1 says that screen is read-only. */
    storageUrl: StorageUrl,
    onLogOut: () -> Unit,
    /** §8's upload, when the root has a gallery to upload from. Without one there is no upload icon. */
    uploads: UploadModel? = null,
) {
    val ui by model.state.collectAsState()
    val arrivals by thumbnails.arrivals.collectAsState()

    // §6: only a photograph may be turned sideways. Every other screen is a phone column, and
    // leaving the viewer while rotated turns the device back.
    val orientation = LocalOrientationPolicy.current
    val viewing = ui.screen is Screen.Photo
    DisposableEffect(viewing) {
        orientation.allowLandscape(viewing)
        onDispose { orientation.allowLandscape(false) }
    }

    // The dialog starts where the upload did: the list on screen, or the album of photos on screen (§8).
    val startUpload: (() -> Unit)? = uploads?.let { upload ->
        { model.openUpload()?.let { upload.open(it.parent, it.addTo) } }
    }

    // The ground runs under the status bar and home indicator; the content does not. On a
    // phone those insets are the notch and the bottom bar, and without this the nav bar's
    // gear sat under the clock (measured on an SE2). On the desktop they are zero.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            when (val screen = ui.screen) {
                is Screen.Albums -> {
                    NavBar("Albums", ui.subtitle, onBack = null) {
                        // Leftmost, beyond the sort (§6's table reads from the right edge).
                        startUpload?.let { BarButton(Icons.upload, "Upload", it) }
                        ListToggle(ui, model)
                        BarButton(Icons.gear, "Settings", model::openSettings)
                    }
                    if (ui.showingMap) {
                        LevelMap(ui, model, thumbnails, arrivals)
                    } else {
                        AlbumList(
                            ui.rows, ui.query, ui.range, true, thumbnails, arrivals,
                            loading = (ui.sync as? SyncStatus.Running)
                                ?.takeIf { ui.rows.isEmpty() }
                                ?.let { it.fetched to it.total },
                            cache = { ui.cacheOf(it) },
                            actions = { ui.actionsOf(it) },
                            order = Triple(ui.sort, ui.query, ui.range),
                            scroll = ui.stack.scroll,
                            onScrolled = model::scrolled,
                            onSearch = model::search,
                            onCalendar = model::openCalendar,
                            onClearRange = model::clearRange,
                            onOpen = model::open,
                            onAction = { row, action -> model.act(row, action) },
                            syncing = ui.sync is SyncStatus.Running,
                            onRefresh = model::refresh,
                        )
                    }
                }

                is Screen.Container -> {
                    NavBar(screen.name, ui.subtitle, onBack = model::back) {
                        startUpload?.let { BarButton(Icons.upload, "Upload", it) }
                        ListToggle(ui, model)
                        BarButton(Icons.gear, "Settings", model::openSettings)
                    }
                    if (ui.showingMap) {
                        LevelMap(ui, model, thumbnails, arrivals)
                    } else {
                        // Its whole subtree, from the left edge: the title already names the
                        // container, so no header repeats it. No field, and so no filter: a query or
                        // a range kept for the album list does not narrow this one unseen.
                        //
                        // Keyed by the level: a container opened from a container is drawn at this
                        // same spot, and would otherwise inherit its parent's scroll — and report it
                        // back as its own. See [ScrollLevel].
                        ScrollLevel(ui) {
                            AlbumList(
                                ui.rows, "", null, false, thumbnails, arrivals,
                                loading = null,
                                cache = { ui.cacheOf(it) },
                                actions = { ui.actionsOf(it) },
                                order = ui.sort,
                                scroll = ui.stack.scroll,
                                onScrolled = model::scrolled,
                                onSearch = model::search,
                                onCalendar = {},
                                onClearRange = {},
                                onOpen = model::open,
                                onAction = { row, action -> model.act(row, action) },
                                syncing = ui.sync is SyncStatus.Running,
                                onRefresh = model::refresh,
                            )
                        }
                    }
                }

                is Screen.Grid -> {
                    // No sort here: an album's photos have one order, oldest first (§3). The
                    // icon on this screen used to cycle the *album* sort, which changed nothing
                    // visible and read as broken. Upload adds to this album: see [startUpload].
                    NavBar(screen.name, ui.photosSubtitle, onBack = model::back) {
                        startUpload?.let { BarButton(Icons.upload, "Upload", it) }
                        if (ui.showingMap) BarButton(Icons.grid, "Grid", model::toggleMap)
                        else BarButton(Icons.map, "Map", model::toggleMap)
                        BarButton(Icons.gear, "Settings", model::openSettings)
                    }
                    if (ui.showingMap) {
                        LevelMap(ui, model, thumbnails, arrivals)
                    } else {
                        ScrollLevel(ui) {
                            PhotoGrid(
                                ui.photos, ui.thumbnails, ui.columns, ui.stack.scroll, model::scrolled,
                                model::density, model::openPhoto,
                            )
                        }
                    }
                }

                is Screen.Photo -> {
                    // Chrome over a photograph: back and gear only in E.1. Share is E.3's
                    // and set-as-cover is G's, so neither is drawn here yet. In landscape the
                    // photograph has the whole screen and the viewer draws its own back button.
                    if (!landscape) {
                        NavBar(screen.name, null, onBack = model::back) {
                            BarButton(Icons.gear, "Settings", model::openSettings)
                        }
                    }
                    Viewer(
                        photos = ui.photos,
                        index = screen.index,
                        preview = ui.preview,
                        videoPath = ui.videoPath,
                        livePair = ui.livePair,
                        moving = ui.openPhotoMoving,
                        nearby = ui.nearby,
                        thumbnails = ui.thumbnails,
                        landscape = landscape,
                        onBack = model::back,
                        onSelect = model::showPhoto,
                    )
                }

                is Screen.Settings -> {
                    NavBar("Settings", null, onBack = model::back) {}
                    SettingsScreen(ui.sync, ui.totals, ui.storage, storageUrl, onLogOut)
                }

                is Screen.Upload -> {
                    if (uploads != null) {
                        UploadScreen(uploads, onClose = model::back)
                    } else {
                        NavBar("Upload", null, onBack = model::back) {}
                        EmptyState("There is no photo library to upload from here.")
                    }
                }
            }
        }
        // The date filter's calendar, over the whole screen and its nav bar: it carries its own ✕.
        ui.calendar?.let { calendar ->
            CalendarSheet(calendar, ui.range, onApply = { model.applyRange(it) }, onClose = model::closeCalendar)
        }
        // Along the bottom edge, the toast above the upload pill: both are transient, and neither
        // may cover the nav bar a person needs to get away from them.
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            ui.notice?.let { notice ->
                Toast(notice, onSettings = model::openSettings, onDismiss = model::dismissNotice)
            }
            if (uploads != null && !viewing) UploadProgress(uploads)
        }
    }
}

/**
 * A list's sort, then its representation toggle — drawn left of the gear.
 *
 * The bar is right-aligned, so reading from the edge it is gear, toggle, sort (§6). The sort goes
 * while the map is showing — a map has no order, and an icon that changes nothing visible reads as
 * broken — and being leftmost, its going moves nothing else: the toggle stays under the thumb
 * that just tapped it. The toggle shows the view you switch *to*.
 */
@Composable
private fun ListToggle(ui: AppUi, model: AppModel) {
    if (ui.showingMap) {
        BarButton(Icons.list, "List", model::toggleMap)
    } else {
        BarButton(Icons.sort, "Sort", model::cycleSort)
        BarButton(Icons.map, "Map", model::toggleMap)
    }
}

/**
 * A list or grid, as the level it stands for. Its scroll state lives as long as the level does on
 * screen, and a different level drawn at the same spot starts from where that level was left.
 */
@Composable
private fun ScrollLevel(ui: AppUi, content: @Composable () -> Unit) {
    key(ui.stack.screens.size) { content() }
}

/** The current level as its map. Keyed by the level, so a camera never carries from one to the next. */
@Composable
private fun LevelMap(ui: AppUi, model: AppModel, thumbnails: Thumbnails, arrivals: Int) {
    key(ui.stack.screens.size, ui.screen) {
        MapScreen(
            ui = ui,
            thumbnails = thumbnails,
            arrivals = arrivals,
            contents = ui::contentsOf,
            onViewport = model::mapViewport,
            onCameraMoved = model::cameraMoved,
            onTap = model::tapMap,
            onOpenFromSheet = model::openFromSheet,
            onDismissSheet = model::dismissSheet,
        )
    }
}
