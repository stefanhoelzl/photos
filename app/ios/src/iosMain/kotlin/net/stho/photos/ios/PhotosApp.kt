package net.stho.photos.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import net.stho.photos.adapter.ios.ContainerPaths
import net.stho.photos.adapter.ios.NativeSqlDrivers
import net.stho.photos.app.AppModel
import net.stho.photos.app.BlobPreviews
import net.stho.photos.app.CacheQueue
import net.stho.photos.app.CatalogSyncer
import net.stho.photos.app.FileBlobStore
import net.stho.photos.app.MergedCatalogSource
import net.stho.photos.app.PackFetcher
import net.stho.photos.app.Session
import net.stho.photos.app.UploadModel
import net.stho.photos.app.Uploads
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.StorageUrl
import net.stho.photos.storage.retryStorageFailures
import platform.Foundation.NSFileManager

/**
 * The composition root, as a value — `:app:desktop`'s `PhotosApp` with four lines changed.
 *
 * §7's rule applied to the app: the only place that knows which adapter satisfies which port,
 * and the only place that constructs anything. Everything below is `:app:domain`'s; what makes
 * this root the *phone* one is SQLiter instead of JDBC, NSURLSession instead of OkHttp,
 * ImageIO instead of the FFM shim, and a container directory instead of an XDG one.
 *
 * That the two roots read almost identically is the point rather than a coincidence: it is what
 * `:app:domain` was extracted to make true.
 */
public class PhotosApp(
    storage: StorageUrl,
    password: String,
    cacheRoot: Path,
) : Session {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drivers = NativeSqlDrivers()
    private val http = HttpClient(Darwin) { retryStorageFailures() }
    private val s3 = S3Client(storage = storage, secretAccessKey = password, http = http)
    private val sync = CatalogSync(s3, cacheRoot, drivers)
    private val catalog = MergedCatalogSource(sync, drivers)

    private val store = FileBlobStore(s3, cacheRoot)

    /** The scheduler itself lives in `:app:domain`; this is only the wiring. */
    public val queue: CacheQueue = CacheQueue(store = store, scope = scope)

    override val thumbnails: PackFetcher = PackFetcher(cacheRoot, drivers, sync.mergedPath, queue, store)

    private val blobs = BlobPreviews(
        cacheRoot = cacheRoot,
        decoder = ImageIoPreviewDecoder(),
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

    private val gallery = PhotoKitGallery()

    /** §8's upload: PhotoKit to read from, a background `URLSession` to send through. */
    private val upload = Uploads(gallery, UrlSessionUploader, sync, drivers, cacheRoot, scope, onLanded = { model.refresh() })

    override val uploads: UploadModel = UploadModel(gallery, upload, scope)

    init {
        // Playback used to reach blobs through symbolic links here; blobs now carry their own
        // extension (`MediaFiles`), so what an earlier build left is only dead links.
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        NSFileManager.defaultManager.removeItemAtPath(Path(cacheRoot, "playable").toString(), error = null)

        // The nav bar counts packs down as they land. The queue knows what is held; only this
        // root knows which of those ids are packs, so the counting happens here.
        scope.launch {
            thumbnails.seed(catalog.everyAlbum())
            queue.held.collect { held ->
                thumbnails.noteArrivals(held)
                delay(150)
            }
        }
    }

    override fun start() {
        model.start()
        // Re-attaches to what a previous run left, silently (§8): a force-quit cancels every
        // background transfer, and only this relaunch can notice and send them again.
        upload.resume()
    }

    override fun close() {
        scope.cancel()
        sync.close()
        http.close()
    }

    public companion object {
        /**
         * Where §4's on-device layout goes, and the single reason `ContainerPaths` is a port
         * rather than a call: a test can point it somewhere writable.
         */
        public fun defaultCacheRoot(): Path = Path(ContainerPaths().cacheRoot)
    }
}
