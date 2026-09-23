package net.stho.photos.desktop

import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.stho.photos.adapter.linux.FfmImaging
import net.stho.photos.adapter.linux.carveCr2
import net.stho.photos.app.LivePair
import net.stho.photos.app.LocalLibrary
import net.stho.photos.app.Preview
import net.stho.photos.app.Previews
import net.stho.photos.app.Videos
import net.stho.photos.media.toImageBitmap
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import org.jetbrains.skia.Image

/**
 * The viewer's pictures: the library's originals, decoded (§11).
 *
 * Through the same shim the CLI encodes with, so the viewer and the zone cannot disagree about
 * what a photograph looks like — orientation applied, and converted through the file's own ICC
 * profile to sRGB. A JPEG shrinks on load to the screen's long edge, which is what keeps a
 * 164-megapixel panorama from costing half a gigabyte to look at; a CR2 is carved, as the CLI
 * carves it, and its camera JPEG decoded. A video has no still to decode: the viewer draws its
 * thumbnail until libvlc's first frame covers it.
 *
 * Decoded photos are kept for the few either side of the one on screen, and no more: a decoded
 * 4K frame is ~35 MB.
 *
 * Owns the shim once handed it. A decode is a blocking native call that cancelling a coroutine
 * does not stop, so decodes and [close] take one lock: closing waits for the decode in flight
 * rather than freeing the library out from under it.
 */
public class OriginalPreviews(
    private val library: LocalLibrary,
    /** Null without the shim, and then Skia alone, which reads JPEG and PNG. */
    private val imaging: FfmImaging?,
    /** The longest edge worth decoding to: the screen's, in pixels. */
    private val longEdge: Int,
) : Previews, Videos, AutoCloseable {

    private val native = Any()
    private var closed = false

    private val decoded = object : LinkedHashMap<kotlin.uuid.Uuid, Preview>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<kotlin.uuid.Uuid, Preview>?): Boolean = size > KEPT
    }

    override fun cached(photo: PhotoRow): Preview? = synchronized(decoded) { decoded[photo.id] }

    override suspend fun load(photo: PhotoRow): Preview? {
        cached(photo)?.let { return it }
        if (photo.mediaType == MediaType.VIDEO) return null
        val file = library.original(photo) ?: return null
        val image = withContext(Dispatchers.IO) {
            synchronized(native) { if (closed) null else decode(File(file.toString())) }
        } ?: return null
        val preview = Preview(photo.id, image)
        synchronized(decoded) { decoded[photo.id] = preview }
        return preview
    }

    /** The model decodes the neighbours itself; there is nothing to fetch ahead. */
    override fun prefetch(photos: List<PhotoRow>, index: Int): Unit = Unit

    override fun cancelPrefetch(): Unit = Unit

    override suspend fun localFile(photo: PhotoRow): String? = library.original(photo)?.toString()

    override suspend fun livePair(photo: PhotoRow): LivePair? {
        val still = library.original(photo) ?: return null
        val video = library.liveVideo(photo) ?: return null
        return LivePair(still.toString(), video.toString())
    }

    override fun close() {
        synchronized(native) {
            closed = true
            imaging?.close()
        }
    }

    private fun decode(file: File): androidx.compose.ui.graphics.ImageBitmap? {
        val head = runCatching { file.inputStream().use { it.readNBytes(HEAD) } }.getOrNull() ?: return null
        val shim = imaging
        if (shim != null) {
            // Only what the host library decodes whole. Its PNG and TIFF path goes through
            // ffmpeg, which the `.so` cannot link (swscale's assembly will not relocate), so a call
            // down it would end the process on an unresolved symbol rather than fail.
            val pixels = when {
                head.isCr2() -> runCatching { file.readBytes().carveCr2() }.getOrNull()
                    ?.let { shim.decodeJpeg(it.jpeg, longEdge, it.orientation, srgb = true) }
                head.isJpeg() || head.isHeif() -> shim.decodeFile(file.path, longEdge, srgb = true)
                else -> null
            }
            pixels?.let { return it.toImageBitmap() }
        }
        // Skia for the rest — PNG, GIF, WebP — and for everything when there is no shim.
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }

    private fun ByteArray.isJpeg(): Boolean = size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()

    /** An ISO-BMFF `ftyp` box naming one of HEIF's brands. */
    private fun ByteArray.isHeif(): Boolean {
        if (size < 12 || decodeToString(4, 8) != "ftyp") return false
        return decodeToString(8, 12) in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif")
    }

    /** "II*\0" then "CR": a TIFF that says it is a Canon raw, whatever its name. */
    private fun ByteArray.isCr2(): Boolean =
        size >= 10 && this[0] == 'I'.code.toByte() && this[1] == 'I'.code.toByte() &&
            this[8] == 'C'.code.toByte() && this[9] == 'R'.code.toByte()

    private companion object {
        /** The open photo and its two neighbours, and room for the ones a swipe back will want. */
        const val KEPT = 5

        /** Enough of a file to say what it is. */
        const val HEAD = 16
    }
}
