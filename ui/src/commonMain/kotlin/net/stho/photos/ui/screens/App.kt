package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

    // The ground runs under the status bar and home indicator; the content does not. On a
    // phone those insets are the notch and the bottom bar, and without this the nav bar's
    // gear sat under the clock (measured on an SE2). On the desktop they are zero.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            when (val screen = ui.screen) {
                is Screen.Albums -> {
                    NavBar("Albums", ui.subtitle, onBack = null) {
                        ListToggle(ui, model)
                        BarButton(Icons.gear, "Settings", model::openSettings)
                    }
                    if (ui.showingMap) {
                        LevelMap(ui, model, thumbnails, arrivals)
                    } else {
                        AlbumList(
                            ui.albums, ui.query, true, thumbnails, arrivals,
                            loading = (ui.sync as? SyncStatus.Running)
                                ?.takeIf { ui.albums.isEmpty() }
                                ?.let { it.fetched to it.total },
                            cache = ui::cacheOf,
                            actions = ui::actionsOf,
                            contents = ui::contentsOf,
                            order = ui.sort to ui.query,
                            onSearch = model::search,
                            onOpen = model::open,
                            onAction = model::act,
                        )
                    }
                }

                is Screen.Container -> {
                    NavBar(screen.name, ui.subtitle, onBack = model::back) {
                        ListToggle(ui, model)
                        BarButton(Icons.gear, "Settings", model::openSettings)
                    }
                    if (ui.showingMap) {
                        LevelMap(ui, model, thumbnails, arrivals)
                    } else {
                        AlbumList(
                            ui.albums, ui.query, false, thumbnails, arrivals,
                            loading = null,
                            cache = ui::cacheOf,
                            actions = ui::actionsOf,
                            contents = ui::contentsOf,
                            order = ui.sort to ui.query,
                            onSearch = model::search,
                            onOpen = model::open,
                            onAction = model::act,
                        )
                    }
                }

                is Screen.Grid -> {
                    // No sort here: an album's photos have one order, oldest first (§3). The
                    // icon on this screen used to cycle the *album* sort, which changed nothing
                    // visible and read as broken.
                    NavBar(screen.name, ui.photosSubtitle, onBack = model::back) {
                        if (ui.showingMap) BarButton(Icons.grid, "Grid", model::toggleMap)
                        else BarButton(Icons.map, "Map", model::toggleMap)
                        BarButton(Icons.gear, "Settings", model::openSettings)
                    }
                    if (ui.showingMap) {
                        LevelMap(ui, model, thumbnails, arrivals)
                    } else {
                        PhotoGrid(ui.photos, ui.thumbnails, ui.columns, model::density, model::openPhoto)
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
            }
        }
        ui.notice?.let { notice ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Toast(notice, onSettings = model::openSettings, onDismiss = model::dismissNotice)
            }
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
