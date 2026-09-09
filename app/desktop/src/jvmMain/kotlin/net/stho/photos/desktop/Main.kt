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
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.asStorageUrl
import net.stho.photos.storage.retryStorageFailures
import net.stho.photos.ui.screens.App
import androidx.compose.runtime.CompositionLocalProvider
import net.stho.photos.ui.screens.LocalVideoSurface
import net.stho.photos.ui.screens.PhotosTheme
import net.stho.photos.ui.state.AppModel

/**
 * The composition root (§7's rule, applied to the app).
 *
 * The only place that knows which adapter satisfies which port, and the only place that
 * constructs anything: `:ui` never learns that the SQL driver is JDBC, that the HTTP engine is
 * OkHttp, or that a control server exists at all.
 *
 * Credentials come from the environment for now — the same `PHOTOS_ENDPOINT` /
 * `PHOTOS_PASSWORD` override the CLI already honours, so `secrets-env` runs this unchanged.
 * The setup screen writing them to the keyring is phase 5.
 */
public fun main(args: Array<String>) {
    val options = Options.parse(args)
    val environment = System.getenv()
    val endpoint = environment["PHOTOS_ENDPOINT"]
    val password = environment["PHOTOS_PASSWORD"]
    if (endpoint.isNullOrBlank() || password.isNullOrBlank()) {
        // §1 forbids opaque failures, and a missing credential before the setup screen exists
        // is a startup problem rather than something the UI can say anything useful about.
        System.err.println(
            "PHOTOS_ENDPOINT and PHOTOS_PASSWORD must be set until the setup screen lands.\n" +
                "  secrets-env ./gradlew :app:desktop:run",
        )
        kotlin.system.exitProcess(3)
    }

    val app = PhotosApp(
        endpoint = endpoint,
        password = password,
        cacheRoot = options.cacheRoot,
        decodeLibrary = options.decodeLibrary,
    )
    val control = options.controlPort?.let {
        ControlServer(it, app.model) { app.Content() }.also(ControlServer::start)
    }

    app.model.start()
    application {
        Window(
            onCloseRequest = {
                control?.stop()
                app.close()
                exitApplication()
            },
            title = "Photos",
            // The phone's proportions, so what is reviewed here is what a device would show.
            state = rememberWindowState(size = DpSize(430.dp, 890.dp)),
        ) {
            app.Content()
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
