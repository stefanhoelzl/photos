package net.stho.photos.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import net.stho.photos.adapter.linux.FfmImaging
import net.stho.photos.adapter.linux.JdbcSqlDrivers
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.asStorageUrl
import net.stho.photos.storage.retryStorageFailures
import net.stho.photos.ui.screens.App
import net.stho.photos.ui.screens.LocalVideoSurface
import net.stho.photos.ui.screens.PhotosTheme
import net.stho.photos.ui.screens.VideoSurface
import net.stho.photos.ui.state.AppModel

/**
 * The composition root, as a value.
 *
 * §7's rule applied to the app: the only place that knows which adapter satisfies which port,
 * and the only place that constructs anything. It is a class rather than a block inside `main`
 * so that `:tests:app` starts *this* — the same adapters, the same wiring — and only skips the
 * window, which is the one part needing a display.
 */
public class PhotosApp(
    endpoint: String,
    password: String,
    cacheRoot: Path,
    decodeLibrary: String?,
    /** Overridden by the suite, which substitutes a surface that opens no player. */
    private val videoSurface: VideoSurface = VlcVideoSurface(),
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drivers = JdbcSqlDrivers()
    private val http = HttpClient(OkHttp) { retryStorageFailures() }
    private val s3 = S3Client(storage = endpoint.asStorageUrl(), secretAccessKey = password, http = http)
    private val sync = CatalogSync(s3, cacheRoot, drivers)
    private val catalog = MergedCatalogSource(sync, drivers)
    private val imaging = decodeLibrary?.let { FfmImaging(it) }

    public val packs: PackFetcher = PackFetcher(s3, cacheRoot, drivers, sync.mergedPath, scope)

    private val blobs = BlobPreviews(
        s3 = s3,
        cacheRoot = cacheRoot,
        // Without the shim, Skia alone: it reads JPEG and PNG, so a suite that never opens a
        // HEIC preview needs no native library at all.
        decoder = imaging?.let(::ShimPreviewDecoder) ?: PreviewDecoder { null },
        scope = scope,
    )

    public val model: AppModel = AppModel(
        catalog = catalog,
        syncer = CatalogSyncer(sync) { packs.fetchAll(catalog.everyAlbum()) },
        thumbnails = packs,
        previews = blobs,
        videos = blobs,
        scope = scope,
    )

    /** What the window shows, and what `GET /screenshot` renders. */
    @Composable
    public fun Content() {
        PhotosTheme {
            CompositionLocalProvider(LocalVideoSurface provides videoSurface) {
                App(model, packs)
            }
        }
    }

    override fun close() {
        scope.cancel()
        sync.close()
        imaging?.close()
        http.close()
    }
}
