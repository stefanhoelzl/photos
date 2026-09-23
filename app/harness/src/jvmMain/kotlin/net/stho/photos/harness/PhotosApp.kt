package net.stho.photos.harness

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
import net.stho.photos.storage.StorageUrl
import net.stho.photos.storage.retryStorageFailures
import net.stho.photos.ui.screens.VideoSurface
import net.stho.photos.media.ShimPreviewDecoder
import net.stho.photos.media.VlcVideoSurface
import net.stho.photos.app.AppModel
import net.stho.photos.app.BlobPreviews
import net.stho.photos.app.CacheQueue
import net.stho.photos.app.CatalogSyncer
import net.stho.photos.app.FileBlobStore
import net.stho.photos.app.MergedCatalogSource
import net.stho.photos.app.PackFetcher
import net.stho.photos.app.Session
import net.stho.photos.app.PreviewDecoder
import net.stho.photos.app.ForegroundUploader
import net.stho.photos.app.UploadModel
import net.stho.photos.app.Uploads

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
    storage: StorageUrl,
    password: String,
    cacheRoot: Path,
    decodeLibrary: String?,
    /** Overridden by the suite, which substitutes a surface that opens no player. */
    private val videoSurface: VideoSurface = VlcVideoSurface(),
    /** A directory standing in for the phone's photo library (§8). Without one, no upload. */
    galleryRoot: String? = null,
) : Session {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drivers = JdbcSqlDrivers()
    private val http = HttpClient(OkHttp) { retryStorageFailures() }
    private val s3 = S3Client(storage = storage, secretAccessKey = password, http = http)
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

    override val thumbnails: PackFetcher = PackFetcher(cacheRoot, drivers, sync.mergedPath, queue, store)

    private val blobs = BlobPreviews(
        cacheRoot = cacheRoot,
        // Without the shim, Skia alone: it reads JPEG and PNG, so a suite that never opens a
        // HEIC preview needs no native library at all.
        decoder = imaging?.let(::ShimPreviewDecoder) ?: PreviewDecoder { null },
        queue = queue,
        scope = scope,
    )

    override val model: AppModel = AppModel(
        catalog = catalog,
        syncer = CatalogSyncer(sync) { thumbnails.sweep(catalog.everyAlbum()) },
        thumbnails = thumbnails,
        previews = blobs,
        videos = blobs,
        queue = queue,
        scope = scope,
    )

    private val gallery = galleryRoot?.let { FolderGallery(java.io.File(it), imaging) }

    /** §8's upload. The phone's background session becomes a foreground PUT here, one at a time. */
    private val upload = gallery?.let {
        Uploads(it, ForegroundUploader(http, scope), sync, drivers, cacheRoot, scope, onLanded = { model.refresh() })
    }

    override val uploads: UploadModel? = if (gallery != null && upload != null) UploadModel(gallery, upload, catalog, scope) else null

    init {
        // The nav bar counts packs down as they land. The queue knows what is held; only this
        // root knows which of those ids are packs, so the counting happens here.
        scope.launch {
            // `everyAlbum()` opens the merged DB, so it is read at launch and then by each sweep
            // rather than per arrival: doing it per blob put a database open on the download
            // workers' own dispatcher several hundred times during a first run.
            thumbnails.seed(catalog.everyAlbum())
            // A StateFlow already conflates; the delay below is what coalesces a burst, since
            // emissions arriving while the collector is suspended replace one another.
            queue.held.collect { held ->
                thumbnails.noteArrivals(held)
                kotlinx.coroutines.delay(150)
            }
        }
    }

    override fun start() {
        model.start()
        upload?.resume()
    }

    override fun close() {
        scope.cancel()
        sync.close()
        imaging?.close()
        http.close()
    }
}
