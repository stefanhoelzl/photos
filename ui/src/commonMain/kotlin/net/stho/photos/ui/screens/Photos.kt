package net.stho.photos.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import net.stho.photos.app.Launch
import net.stho.photos.app.Launcher

/**
 * What either root shows: the setup screen, or the app.
 *
 * §1 allows two states and one transition in each direction, and this is the whole of it. The
 * decision itself is `Launcher`'s, in `:app:domain`, so that "log out returns to setup and
 * closes the old session" is exercised by a test rather than only by looking at a window.
 *
 * The theme is **not** applied here, for the same reason `App` does not apply it: it is chosen
 * once by whoever hosts this — a window, a view controller, or a test rendering both schemes.
 */
@Composable
public fun Photos(launcher: Launcher) {
    val launch by launcher.state.collectAsState()
    when (val current = launch) {
        Launch.Setup -> SetupScreen(onSave = launcher::save)
        is Launch.Blocked -> BlockedScreen(current.reason)
        is Launch.Running -> App(
            model = current.session.model,
            thumbnails = current.session.thumbnails,
            storageUrl = current.storage,
            onLogOut = launcher::logOut,
        )
    }
}
