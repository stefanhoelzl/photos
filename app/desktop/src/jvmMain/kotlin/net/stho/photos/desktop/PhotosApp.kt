package net.stho.photos.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import net.stho.photos.app.AppModel
import net.stho.photos.app.BlobPreviews
import net.stho.photos.app.CacheQueue
import net.stho.photos.app.CatalogSyncer
import net.stho.photos.app.FileBlobStore
import net.stho.photos.app.MergedCatalogSource
import net.stho.photos.app.PackFetcher
import net.stho.photos.app.PreviewDecoder

/**
 * The composition root, as a value.
 *
 * §7's rule applied to the app: the only place that knows which adapter satisfies which port,
 * and the only place that constructs anything. What it constructs is mostly `:app:domain`'s --
 * the cache, the queue, the catalog source -- and what makes this root the *Linux* one is the
 * four lines that are not: a JDBC driver, OkHttp, libvlc, and the FFM decode shim.
 * It is a class rather than a block inside `main`
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

    /**
     * The queue's platform half. It also decides where a blob lands, so the pack queue below
     * declares its ids as packs before asking for them.
     */
    private val store = FileBlobStore(s3, cacheRoot)

    /** The scheduler itself lives in `:app:domain`; this is only the wiring. */
    public val queue: CacheQueue = CacheQueue(store = store, scope = scope)

    public val packs: PackFetcher = PackFetcher(cacheRoot, drivers, sync.mergedPath, queue, store)

    private val blobs = BlobPreviews(
        cacheRoot = cacheRoot,
        // Without the shim, Skia alone: it reads JPEG and PNG, so a suite that never opens a
        // HEIC preview needs no native library at all.
        decoder = imaging?.let(::ShimPreviewDecoder) ?: PreviewDecoder { null },
        queue = queue,
        scope = scope,
    )

    public val model: AppModel = AppModel(
        catalog = catalog,
        syncer = CatalogSyncer(sync) { packs.sweep(catalog.everyAlbum()) },
        thumbnails = packs,
        previews = blobs,
        videos = blobs,
        queue = queue,
        scope = scope,
    )

    init {
        // The nav bar counts packs down as they land. The queue knows what is held; only this
        // root knows which of those ids are packs, so the counting happens here.
        scope.launch {
            // `everyAlbum()` opens the merged DB, so it is read once rather than per arrival:
            // doing it per blob put a database open on the download workers' own dispatcher
            // several hundred times during a first run.
            var albums = catalog.everyAlbum()
            // A StateFlow already conflates; the delay below is what coalesces a burst, since
            // emissions arriving while the collector is suspended replace one another.
            queue.held.collect { held ->
                if (albums.isEmpty()) albums = catalog.everyAlbum()
                packs.noteArrivals(held, albums)
                kotlinx.coroutines.delay(150)
            }
        }
    }

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
