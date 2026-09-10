package net.stho.photos.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.blobKey
import net.stho.photos.model.PhotoRow
import net.stho.photos.storage.S3Client
import net.stho.photos.ui.state.Preview
import net.stho.photos.ui.state.Previews
import net.stho.photos.ui.state.Videos
import net.stho.photos.adapter.linux.FfmImaging
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * §6's browse-to-cache, for the 2048px preview tier.
 *
 * Nothing is downloaded ahead of time and nothing is auto-evicted: a blob fetched once stays in
 * `blobs/` until it is cleared, which is E.2's button. Previews live there rather than beside
 * the packs precisely because the policies differ — packs are always kept, blobs are not.
 *
 * Decoded images are held in a small LRU so that swiping back and forth does not re-decode: a
 * 2048px frame is ~16 MB of pixels, so this is deliberately shallow.
 */
public class BlobPreviews(
    private val s3: S3Client,
    cacheRoot: Path,
    private val decoder: PreviewDecoder,
    private val scope: CoroutineScope,
) : Previews, Videos {

    private val directory = Path(cacheRoot, "blobs")
    private val decoded = Collections.synchronizedMap(
        object : LinkedHashMap<Uuid, Preview>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Uuid, Preview>) = size > LRU
        },
    )
    private var prefetching: Job? = null

    /**
     * One download per blob, however many callers want it.
     *
     * Without this, opening a photo and prefetching its neighbours both start the *same*
     * fetch, both write the same scratch file, and whichever renames it second fails on a file
     * that is no longer there. Sharing one `Deferred` is also the only way the second caller
     * gets the picture rather than an error.
     */
    private val inFlight = mutableMapOf<Uuid, Deferred<Preview?>>()
    private val guard = Mutex()

    init {
        SystemFileSystem.createDirectories(directory)
    }

    override fun cached(photo: PhotoRow): Preview? = decoded[photo.id]

    override suspend fun load(photo: PhotoRow): Preview? {
        decoded[photo.id]?.let { return it }
        if (photo.imageId == null) return null
        val shared = guard.withLock {
            inFlight.getOrPut(photo.id) { scope.async { fetchAndDecode(photo) } }
        }
        return try {
            shared.await()
        } finally {
            guard.withLock { if (inFlight[photo.id] === shared && shared.isCompleted) inFlight -= photo.id }
        }
    }

    private suspend fun fetchAndDecode(photo: PhotoRow): Preview? {
        val blob = photo.imageId ?: return null
        val file = Path(directory, blob.toString())
        if (!SystemFileSystem.exists(file)) {
            // A scratch name of its own per attempt, so two downloads of the same blob can
            // never rename each other's file out from under themselves.
            val partial = Path(directory, "$blob.${Uuid.random()}.part")
            try {
                s3.download(blob.blobKey, to = partial)
                SystemFileSystem.atomicMove(partial, file)
            } catch (cancelled: CancellationException) {
                // Cancellation is not a failure and must not be swallowed: leaving the album
                // abandons a prefetch by design, and reporting it as an error would bury the
                // one line that means something.
                SystemFileSystem.delete(partial, mustExist = false)
                throw cancelled
            } catch (failure: Exception) {
                SystemFileSystem.delete(partial, mustExist = false)
                System.err.println("preview ${photo.filename}: $failure")
                return null
            }
        }
        val image = decoder.decode(file) ?: return null
        return Preview(photo.id, image).also { decoded[photo.id] = it }
    }

    /**
     * §6's ±3, and no more.
     *
     * Egress is free (§10), so the ceiling here is time and battery rather than money — but a
     * whole-album prefetch belongs to E.2, which also brings the button that clears what it
     * leaves behind.
     */
    override fun prefetch(photos: List<PhotoRow>, index: Int) {
        prefetching?.cancel()
        prefetching = scope.launch {
            val window = (index - NEIGHBOURS)..(index + NEIGHBOURS)
            for (position in window) {
                if (position == index) continue
                photos.getOrNull(position)?.let { load(it) }
            }
        }
    }

    /**
     * §5 keeps video *originals* on the laptop, so what the zone holds — and what this
     * fetches — is the transcode. Same cache and same policy as a preview: browse-to-cache.
     */
    override suspend fun localFile(photo: PhotoRow): String? {
        val blob = photo.videoId ?: return null
        val file = Path(directory, blob.toString())
        if (!SystemFileSystem.exists(file)) {
            val partial = Path(directory, "$blob.${Uuid.random()}.part")
            try {
                s3.download(blob.blobKey, to = partial)
                SystemFileSystem.atomicMove(partial, file)
            } catch (cancelled: CancellationException) {
                SystemFileSystem.delete(partial, mustExist = false)
                throw cancelled
            } catch (failure: Exception) {
                SystemFileSystem.delete(partial, mustExist = false)
                System.err.println("video ${photo.filename}: $failure")
                return null
            }
        }
        return file.toString()
    }

    /** Leaving the album abandons the queue *and* whatever it had already started. */
    override fun cancelPrefetch() {
        prefetching?.cancel()
        prefetching = null
        scope.launch { guard.withLock { inFlight.values.forEach { it.cancel() }; inFlight.clear() } }
    }

    private companion object {
        const val NEIGHBOURS = 3
        const val LRU = 8
    }
}

/**
 * Bytes on disk to pixels.
 *
 * Skia decodes JPEG and PNG without help; **HEIC it does not**, and §5's whole preview tier is
 * HEIC — chosen because it is 15% smaller than JPEG and hardware-decoded on the phone. So this
 * is the seam the FFM binding to `native/CImaging` fills, and until it lands a preview simply
 * does not appear.
 */
public fun interface PreviewDecoder {
    fun decode(file: Path): ImageBitmap?
}

/**
 * libheif through the FFM binding, with Skia behind it.
 *
 * The order matters: the shim is the same C the pipeline used to *write* these previews, so
 * asking it first means the app and ingest cannot disagree about what a photograph looks like.
 * Skia catches whatever the shim will not take, which in practice is nothing the zone holds —
 * it is there so that a preview tier that ever changed format still renders.
 */
public class ShimPreviewDecoder(private val imaging: FfmImaging) : PreviewDecoder {
    override fun decode(file: Path): ImageBitmap? {
        imaging.decodeFile(file.toString())?.let { return it.toImageBitmap() }
        // Skia only if the shim would not take it -- and from bytes, since that is its API.
        val bytes = runCatching { java.io.File(file.toString()).readBytes() }.getOrNull() ?: return null
        return runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }

    /**
     * C pixels to a Skia bitmap.
     *
     * The shim hands back interleaved RGB or RGBA, top-down; Skia wants a colour type named up
     * front, so the channel count picks one rather than being assumed.
     */
    private fun FfmImaging.Decoded.toImageBitmap(): ImageBitmap {
        val info = ImageInfo(
            width = width,
            height = height,
            colorType = if (channels == 4) ColorType.RGBA_8888 else ColorType.RGB_888X,
            alphaType = ColorAlphaType.UNPREMUL,
        )
        val rgba = if (channels == 4) pixels else pixels.toRgbx()
        return Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
    }

    /** Skia has no 24-bit raster type, so three-channel pixels get an opaque fourth. */
    private fun ByteArray.toRgbx(): ByteArray {
        val out = ByteArray(size / 3 * 4)
        var source = 0
        var target = 0
        while (source < size) {
            out[target] = this[source]
            out[target + 1] = this[source + 1]
            out[target + 2] = this[source + 2]
            out[target + 3] = 0xFF.toByte()
            source += 3
            target += 4
        }
        return out
    }
}
