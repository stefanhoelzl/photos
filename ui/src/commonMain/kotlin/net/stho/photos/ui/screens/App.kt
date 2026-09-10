package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import net.stho.photos.ui.state.AppModel
import net.stho.photos.ui.state.Screen
import net.stho.photos.ui.state.SyncStatus
import net.stho.photos.ui.state.Thumbnails

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
public fun App(model: AppModel, thumbnails: Thumbnails) {
    val ui by model.state.collectAsState()
    val arrivals by thumbnails.arrivals.collectAsState()
    run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxSize()) {
                when (val screen = ui.screen) {
                    is Screen.Albums -> {
                        NavBar("Albums", ui.subtitle, onBack = null) {
                            BarButton(Icons.gear, "Settings", model::openSettings)
                            BarButton(Icons.sort, "Sort", model::cycleSort)
                        }
                        AlbumList(
                            ui.albums, ui.query, true, thumbnails, arrivals,
                            loading = (ui.sync as? SyncStatus.Running)
                                ?.takeIf { ui.albums.isEmpty() }
                                ?.let { it.fetched to it.total },
                            cache = ui::cacheOf,
                            actions = ui::actionsOf,
                            onSearch = model::search,
                            onOpen = model::open,
                            onAction = model::act,
                        )
                    }

                    is Screen.Container -> {
                        NavBar(screen.name, ui.subtitle, onBack = model::back) {
                            BarButton(Icons.gear, "Settings", model::openSettings)
                            BarButton(Icons.sort, "Sort", model::cycleSort)
                        }
                        AlbumList(
                            ui.albums, ui.query, false, thumbnails, arrivals,
                            loading = null,
                            cache = ui::cacheOf,
                            actions = ui::actionsOf,
                            onSearch = model::search,
                            onOpen = model::open,
                            onAction = model::act,
                        )
                    }

                    is Screen.Grid -> {
                        NavBar(screen.name, "${ui.photos.size} photos", onBack = model::back) {
                            BarButton(Icons.gear, "Settings", model::openSettings)
                            BarButton(Icons.sort, "Sort", model::cycleSort)
                        }
                        PhotoGrid(ui.photos, ui.thumbnails, ui.columns, model::density, model::openPhoto)
                    }

                    is Screen.Photo -> {
                        // Chrome over a photograph: back and gear only in E.1. Share is E.3's
                        // and set-as-cover is G's, so neither is drawn here yet.
                        NavBar(screen.name, null, onBack = model::back) {
                            BarButton(Icons.gear, "Settings", model::openSettings)
                        }
                        Viewer(
                            photos = ui.photos,
                            index = screen.index,
                            preview = ui.preview,
                            videoPath = ui.videoPath,
                            moving = ui.openPhotoMoving,
                            thumbnails = ui.thumbnails,
                            onSelect = model::showPhoto,
                        )
                    }

                    is Screen.Settings -> {
                        NavBar("Settings", null, onBack = model::back) {}
                        SettingsScreen(ui.sync, ui.totals, ui.storage)
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
}
