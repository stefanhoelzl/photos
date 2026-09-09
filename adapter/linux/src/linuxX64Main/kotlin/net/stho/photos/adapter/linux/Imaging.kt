@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import net.stho.photos.derivative.DerivativeSpec
import net.stho.photos.pipeline.MediaFormat
import net.stho.photos.pipeline.VideoInfo
import net.stho.photos.ports.MediaProbe
import photosimaging.PI_FORMAT_CR2
import photosimaging.PI_FORMAT_HEIF
import photosimaging.PI_FORMAT_JPEG
import photosimaging.PI_FORMAT_PNG
import photosimaging.PI_FORMAT_TIFF
import photosimaging.PI_FORMAT_VIDEO
import photosimaging.pi_buffer
import photosimaging.pi_buffer_free
import photosimaging.pi_buffer_init
import photosimaging.pi_convert_to_srgb
import photosimaging.pi_decode
import photosimaging.pi_decode_memory
import photosimaging.pi_encode_heic
import photosimaging.pi_encode_jpeg
import photosimaging.pi_error
import photosimaging.pi_flatten_alpha
import photosimaging.pi_image
import photosimaging.pi_image_free
import photosimaging.pi_image_init
import photosimaging.pi_resize_fit
import photosimaging.pi_resize_square_crop
import photosimaging.pi_sniff
import photosimaging.pi_video_info
import photosimaging.pi_video_poster
import photosimaging.pi_video_probe

/**
 * A failure from the native imaging stack.
 *
 * Every one of these is per-file and recoverable by design: decision 15 says an undecodable
 * file is skipped and reported, never a reason to abandon a run. A corrupt JPEG is not the
 * library breaking its contract the way §7's byte-size mismatch is — it is just a bad file,
 * which is why this is deliberately *not* a `PhotosFailure`.
 */
public class ImagingException(
    public val code: Code,
    override val message: String,
) : Exception(message) {

    public enum class Code(public val raw: Int) {
        OPEN(1),
        DECODE(2),
        ENCODE(3),
        UNSUPPORTED(4),
        MEMORY(5),
        INVALID(6),
        UNKNOWN(-1),
        ;

        public companion object {
            private val byRaw = entries.associateBy(Code::raw)

            public fun of(raw: Int): Code = byRaw[raw] ?: UNKNOWN
        }
    }
}

internal fun imagingException(err: pi_error): ImagingException =
    ImagingException(ImagingException.Code.of(err.code), err.message.toKString())

/** Runs a shim call that fills a `pi_error`, turning a nonzero return into a thrown exception. */
internal inline fun imagingCall(body: MemScope.(CPointer<pi_error>) -> Int) {
    memScoped {
        val err = alloc<pi_error>()
        if (body(err.ptr) != 0) throw imagingException(err)
    }
}

/**
 * An owned decoded image, freed exactly once by [close].
 *
 * `pi_image` owns two heap allocations and Kotlin/Native has no deterministic destructor, so
 * this is an [AutoCloseable] rather than something the collector eventually gets to: a run
 * holding sixteen workers' worth of 500 MB panoramas cannot wait for a GC to notice.
 */
public class PixelImage internal constructor() : AutoCloseable {

    /**
     * The C struct stays internal: the tests and the pipeline need to decode, resize and
     * encode, but nothing outside this module should be handling `pi_image` lifetimes.
     */
    internal val raw: pi_image = nativeHeap.alloc<pi_image>().also { pi_image_init(it.ptr) }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        pi_image_free(raw.ptr)
        nativeHeap.free(raw)
    }

    public val width: Int get() = raw.width
    public val height: Int get() = raw.height
    public val channels: Int get() = raw.channels

    /**
     * The source photograph's display dimensions, orientation applied. Differs from [width] and
     * [height] whenever shrink-on-load produced a smaller buffer — which is most large JPEGs,
     * so this is what §3's stored dimensions must come from.
     */
    public val sourceWidth: Int get() = raw.source_width
    public val sourceHeight: Int get() = raw.source_height

    public val hasAlpha: Boolean get() = raw.channels == 4
    public val hasProfile: Boolean get() = raw.icc != null && raw.icc_len.toLong() > 0L

    /**
     * Borrowed access to the interleaved pixel buffer, for callers that need to compare two
     * images — the PSNR sweep, and tests that check a colour actually landed where it should.
     */
    internal val pixels: CPointer<UByteVar>? get() = raw.pixels

    public fun resizedFitting(longEdge: Int, allowUpscale: Boolean = false): PixelImage =
        produce { out, err -> pi_resize_fit(raw.ptr, longEdge, if (allowUpscale) 1 else 0, out, err) }

    public fun squareCropped(edge: Int): PixelImage =
        produce { out, err -> pi_resize_square_crop(raw.ptr, edge, out, err) }

    /**
     * Composites transparency onto an opaque background. 26 of the library's 36 PNG/TIFF files
     * have an alpha channel and JPEG cannot represent one at all.
     */
    public fun flattenAlpha(background: DerivativeSpec.Rgb) {
        imagingCall { err -> pi_flatten_alpha(raw.ptr, background.r, background.g, background.b, err) }
    }

    public fun convertToSrgb() {
        imagingCall { err -> pi_convert_to_srgb(raw.ptr, err) }
    }

    /** Applies one of [DerivativeSpec]'s two colour rules; pass-through embeds what came in. */
    public fun applyColorHandling(handling: DerivativeSpec.ColorHandling) {
        when (handling) {
            DerivativeSpec.ColorHandling.CONVERT_TO_SRGB -> convertToSrgb()
            DerivativeSpec.ColorHandling.PASS_THROUGH -> Unit
        }
    }

    public fun encodedJpeg(quality: Int, optimize: Boolean = true): ByteArray =
        encodedBytes { buffer, err ->
            pi_encode_jpeg(raw.ptr, quality, if (optimize) 1 else 0, buffer, err)
        }

    /**
     * [threads] bounds x265's own pool; 1 is right whenever the caller is already running one
     * worker per core, which is what the pipeline assumes.
     */
    public fun encodedHeic(quality: Int, threads: Int = 1): ByteArray =
        encodedBytes { buffer, err -> pi_encode_heic(raw.ptr, quality, threads, buffer, err) }

    public companion object {
        /**
         * Decodes a file, hinting the largest tier the caller will ask for.
         *
         * The hint is what bounds memory: libjpeg decodes straight out of the DCT coefficients
         * at N/8 scale, so the library's 27558×5973 panorama comes back at 1/8 — about 7.7 MB
         * rather than the 494 MB a full decode would need, times however many workers run.
         */
        public fun decode(path: String, maxLongEdge: Int): PixelImage =
            produce { out, err -> pi_decode(path, maxLongEdge, out, err) }

        /** In-memory decoding is JPEG only; the shim has nothing else to offer it. */
        public fun decodeJpeg(bytes: ByteArray, maxLongEdge: Int): PixelImage =
            produce { out, err ->
                bytes.usePinned { pinned ->
                    pi_decode_memory(
                        pinned.addressOf(0).reinterpret<UByteVar>(),
                        bytes.size.convert(),
                        maxLongEdge,
                        out,
                        err,
                    )
                }
            }

        /** The poster still for a video, with the display matrix already baked into the pixels. */
        public fun poster(path: String, seconds: Double): PixelImage =
            produce { out, err -> pi_video_poster(path, seconds, out, err) }
    }
}

/**
 * Fills a fresh [PixelImage], destroying it again if the shim call fails — so a thrown error
 * never leaves a half-built image for the caller to close.
 */
private inline fun produce(body: (CPointer<pi_image>, CPointer<pi_error>) -> Int): PixelImage {
    val image = PixelImage()
    try {
        imagingCall { err -> body(image.raw.ptr, err) }
    } catch (failure: Throwable) {
        image.close()
        throw failure
    }
    return image
}

/** Runs an encode into a `pi_buffer` and copies the result out, freeing the buffer either way. */
private inline fun encodedBytes(body: (CPointer<pi_buffer>, CPointer<pi_error>) -> Int): ByteArray =
    memScoped {
        val buffer = alloc<pi_buffer>()
        pi_buffer_init(buffer.ptr)
        try {
            val err = alloc<pi_error>()
            if (body(buffer.ptr, err.ptr) != 0) throw imagingException(err)
            buffer.bytes?.readBytes(buffer.len.toInt()) ?: ByteArray(0)
        } finally {
            pi_buffer_free(buffer.ptr)
        }
    }

/** Builds a JPEG APP1 payload out of a bare TIFF header, or null when there is nothing to say. */
internal fun exifApp1FromTiff(tiff: ByteArray): ByteArray? {
    if (tiff.isEmpty()) return null
    return memScoped {
        val buffer = alloc<pi_buffer>()
        pi_buffer_init(buffer.ptr)
        try {
            val err = alloc<pi_error>()
            val ok = tiff.usePinned { pinned ->
                photosimaging.pi_exif_app1_from_tiff(
                    pinned.addressOf(0).reinterpret<UByteVar>(),
                    tiff.size.convert(),
                    buffer.ptr,
                    err.ptr,
                ) == 0
            }
            if (!ok || buffer.len.toLong() <= 0L) null else buffer.bytes?.readBytes(buffer.len.toInt())
        } finally {
            pi_buffer_free(buffer.ptr)
        }
    }
}

/** What a video's container says about it. Throws [ImagingException] for a file ffmpeg refuses. */
public fun probeVideo(path: String): VideoInfo = memScoped {
    val info = alloc<pi_video_info>()
    imagingCall { err -> pi_video_probe(path, info.ptr, err) }
    val identifier = info.content_identifier.toKStringOrEmpty()
    VideoInfo(
        width = info.width,
        height = info.height,
        duration = info.duration,
        rotation = info.rotation,
        hasAudio = info.has_audio != 0,
        isInterlaced = info.interlaced != 0,
        contentIdentifier = identifier.ifEmpty { null },
    )
}

private fun CPointer<ByteVar>?.toKStringOrEmpty(): String = this?.toKString() ?: ""

/** §7's [MediaProbe], over the C shim's bounded header read and ffmpeg's demuxer. */
public class CImagingProbe : MediaProbe {

    override fun sniff(path: String): MediaFormat = when (pi_sniff(path)) {
        PI_FORMAT_JPEG -> MediaFormat.JPEG
        PI_FORMAT_HEIF -> MediaFormat.HEIF
        PI_FORMAT_PNG -> MediaFormat.PNG
        PI_FORMAT_TIFF -> MediaFormat.TIFF
        PI_FORMAT_CR2 -> MediaFormat.CR2
        PI_FORMAT_VIDEO -> MediaFormat.VIDEO
        else -> MediaFormat.UNKNOWN
    }

    override fun videoInfo(path: String): VideoInfo? = try {
        probeVideo(path)
    } catch (_: ImagingException) {
        null
    }
}
