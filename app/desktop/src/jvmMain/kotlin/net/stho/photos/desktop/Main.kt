package net.stho.photos.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.system.exitProcess
import kotlinx.io.files.Path
import net.stho.photos.control.ViewerControlServer
import net.stho.photos.media.VlcLivePhotoSurface
import net.stho.photos.media.VlcVideoSurface
import net.stho.photos.media.offscreen
import net.stho.photos.ui.desktop.DesktopApp
import net.stho.photos.ui.screens.LocalLivePhotoSurface
import net.stho.photos.ui.screens.LocalVideoSurface
import net.stho.photos.adapter.linux.Appearance
import net.stho.photos.media.SystemTheme
import net.stho.photos.media.applyDisplayScale

/**
 * The desktop viewer (§11): a window over the library `photos-cli sync` keeps on this machine.
 *
 * `./gradlew :app:desktop:run --args="--library-path ~/Pictures/Albums"`. The library root is
 * required, because every album's folder in the catalog is relative to it; the CLI's cache is
 * found where the CLI keeps it unless `--cli-cache` says otherwise.
 */
public fun main(args: Array<String>) {
    // First, before anything can start AWT: the one moment the JVM reads its scale.
    applyDisplayScale()
    val appearance = Appearance.ofSession()
    val options = runCatching { ViewerOptions.parse(args, System.getenv()) }.getOrElse {
        System.err.println("photos desktop: ${it.message}")
        System.err.println(ViewerOptions.USAGE)
        exitProcess(2)
    }
    val viewer = PhotosViewer(
        cliCache = options.cliCache,
        libraryRoot = options.libraryRoot,
        decodeLibrary = options.decodeLibrary,
        longEdge = screenLongEdge(),
    )
    val content: @Composable () -> Unit = {
        SystemTheme(appearance) {
            CompositionLocalProvider(
                LocalVideoSurface provides VlcVideoSurface(),
                LocalLivePhotoSurface provides VlcLivePhotoSurface(),
            ) {
                DesktopApp(viewer.model, viewer.thumbnails)
            }
        }
    }
    viewer.start()
    val control = options.controlPort?.let { port ->
        ViewerControlServer(port, viewer.model, screenshot = offscreen(content)).also(ViewerControlServer::start)
    }

    application {
        Window(
            onCloseRequest = {
                control?.stop()
                viewer.close()
                appearance.close()
                exitApplication()
            },
            title = "Photos",
            // Maximized, with the title bar: the whole screen, and the ✕ that closes it (§11).
            state = rememberWindowState(placement = WindowPlacement.Maximized),
        ) {
            content()
        }
    }
}

/** The largest screen's long edge in pixels: the most a photo is ever drawn at. */
private fun screenLongEdge(): Int = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .maxOf { maxOf(it.displayMode.width, it.displayMode.height) }
}.getOrNull()?.takeIf { it > 0 } ?: 3840

/** `--library-path`, `--cli-cache`, `--decode-library` and `--control-port`. */
internal data class ViewerOptions(
    val libraryRoot: Path,
    val cliCache: Path,
    /** `libphotosdecode.so`, which the Gradle run task points at. */
    val decodeLibrary: String?,
    val controlPort: Int?,
) {
    companion object {
        const val USAGE: String =
            "usage: --library-path <dir> [--cli-cache <dir>] [--decode-library <so>] [--control-port <port>]"

        fun parse(args: Array<String>, env: Map<String, String>): ViewerOptions {
            fun flag(name: String): String? =
                args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

            val home = env["HOME"] ?: System.getProperty("user.home")
            // `--args` reaches the JVM unexpanded, so a leading ~ is the shell's job done here.
            fun expand(path: String): String = if (path == "~" || path.startsWith("~/")) home + path.drop(1) else path

            val library = flag("--library-path")?.let(::expand)
                ?: throw IllegalArgumentException("--library-path is required: the folder photos-cli syncs")
            require(File(library).isDirectory) { "no library at $library" }
            // Where the CLI keeps it: `$XDG_CACHE_HOME/photos-cli`, else `~/.cache/photos-cli`.
            val cache = flag("--cli-cache")?.let(::expand)
                ?: "${env["XDG_CACHE_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.cache"}/photos-cli"
            val decode = flag("--decode-library") ?: System.getProperty("photos.decode.library")
            val port = flag("--control-port")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("--control-port $it is not a port") }
            return ViewerOptions(Path(library), Path(cache), decode, port)
        }
    }
}
