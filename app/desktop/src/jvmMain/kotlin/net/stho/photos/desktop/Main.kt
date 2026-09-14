package net.stho.photos.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.files.Path
import net.stho.photos.adapter.linux.FfmImaging
import net.stho.photos.adapter.linux.JdbcSqlDrivers
import net.stho.photos.adapter.linux.desktopKeyring
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.retryStorageFailures
import androidx.compose.runtime.CompositionLocalProvider
import net.stho.photos.ui.screens.LocalVideoSurface
import net.stho.photos.ui.screens.PhotosTheme
import androidx.compose.runtime.Composable
import net.stho.photos.app.Account
import net.stho.photos.app.Launch
import net.stho.photos.app.Launcher
import net.stho.photos.control.ControlServer
import net.stho.photos.ui.screens.Photos

/**
 * The composition root (§7's rule, applied to the app).
 *
 * The only place that knows which adapter satisfies which port, and the only place that
 * constructs anything: `:ui` never learns that the SQL driver is JDBC, that the HTTP engine is
 * OkHttp, or that a control server exists at all.
 *
 * Credentials resolve exactly as the CLI's do — environment, then keyring — and a machine with
 * neither now gets §1's setup screen rather than an error. So there are three ways in and they
 * agree: `secrets-env ./gradlew :app:desktop:run`, a `photos-cli login` done earlier, or typing
 * the two values once into the screen this root now hosts.
 */
public fun main(args: Array<String>) {
    val options = Options.parse(args)
    val account = Account(desktopKeyring(), System.getenv())
    // The factory is the only part of a session a root owns: which SQL driver, which HTTP
    // engine, which decoder. `Launcher` decides *when* to build one.
    val launcher = Launcher(account) { storage, password ->
        PhotosApp(
            storage = storage,
            password = password,
            cacheRoot = options.cacheRoot,
            decodeLibrary = options.decodeLibrary,
        )
    }
    val content: @Composable () -> Unit = {
        PhotosTheme {
            CompositionLocalProvider(LocalVideoSurface provides VlcVideoSurface()) {
                Photos(launcher)
            }
        }
    }
    // Before the control server, not after: the launcher's state starts as `Setup` until `start()`
    // has read the environment and the keyring, and a server already listening would report
    // "setup" for an app that is set up. The iOS suite caught the same order there.
    launcher.start()
    val control = options.controlPort?.let { port ->
        ControlServer(port, launcher, screenshot = offscreen(content)).also(ControlServer::start)
    }

    application {
        Window(
            onCloseRequest = {
                control?.stop()
                (launcher.state.value as? Launch.Running)?.session?.close()
                exitApplication()
            },
            title = "Photos",
            // The phone's proportions, so what is reviewed here is what a device would show.
            state = rememberWindowState(size = DpSize(430.dp, 890.dp)),
        ) {
            content()
        }
    }
}

/** `--cache-root` and `--control-port`, and nothing else yet. */
internal data class Options(
    val cacheRoot: Path,
    val controlPort: Int?,
    /** `libphotosdecode.so`, which the Gradle run task points at. */
    val decodeLibrary: String,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            fun flag(name: String): String? =
                args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

            // Inside the working tree, not $HOME: a checkout is what a run belongs to, and
            // parallel worktrees would otherwise share one cache and re-fetch each other's
            // shards. Gitignored, so clearing it is `rm -rf .cache`.
            //
            // Deliberately *not* the CLI's `~/.cache/photos-cli` either: sharing would inherit
            // its shards, but the CLI guards that cache with a flock this app does not take,
            // and an hourly timer writing while the app reads is undesigned rather than rare.
            val cache = flag("--cache-root")
                ?: System.getProperty("photos.cache.root")
                ?: "${System.getProperty("user.dir")}/.cache/desktop"
            val decode = flag("--decode-library")
                ?: System.getProperty("photos.decode.library")
                ?: error("--decode-library or -Dphotos.decode.library must point at libphotosdecode.so")
            return Options(Path(cache), flag("--control-port")?.toIntOrNull(), decode)
        }
    }
}
