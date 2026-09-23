package net.stho.photos.adapter.linux

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * `native/CImaging`'s decode path, bound from the JVM through Panama (§6's preview tier).
 *
 * The same C the pipeline uses, so the app and ingest cannot disagree about what a photograph
 * looks like — and no glue layer, which is the value cinterop already earns on the native side.
 * §5 chose HEIC for previews because it is 15% smaller than JPEG and hardware-decoded on the
 * phone; on Linux that means libheif, and libheif is what this reaches.
 *
 * Only *decoding* is bound. The shared object deliberately excludes swscale and x265, whose
 * hand-written assembly cannot live in a `.so` at all — neither is needed to read an image.
 */
public class FfmImaging(
    libraryPath: String,
    /** Where the shim's own diagnosis goes. Defaults to stderr, like the app's other reports. */
    private val onError: (message: String, code: Int) -> Unit = { message, code ->
        System.err.println("decode failed ($code): $message")
    },
) : AutoCloseable {

    private val arena = Arena.ofShared()
    private val lookup = SymbolLookup.libraryLookup(libraryPath, arena)
    private val linker = Linker.nativeLinker()

    /** `int pi_decode_memory(const uint8_t*, size_t, int, pi_image*, pi_error*)` */
    private val decodeMemory: MethodHandle = handle(
        "pi_decode_memory",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ),
    )

    /** `int pi_decode(const char*, int, pi_image*, pi_error*)` */
    private val decodePath: MethodHandle = handle(
        "pi_decode",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ),
    )

    /** `int pi_convert_to_srgb(pi_image*, pi_error*)` — through the image's own ICC profile. */
    private val convertToSrgb: MethodHandle = handle(
        "pi_convert_to_srgb",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )

    /** `int pi_image_apply_orientation(pi_image*, int, pi_error*)` — EXIF's 1–8. */
    private val applyOrientation: MethodHandle = handle(
        "pi_image_apply_orientation",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )

    private val imageFree: MethodHandle =
        handle("pi_image_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

    private val imageInit: MethodHandle =
        handle("pi_image_init", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

    /**
     * Decodes [encoded] to interleaved 8-bit pixels.
     *
     * [maxLongEdge] is passed straight through: it is what bounds decode memory, since libjpeg
     * uses it to pick a shrink-on-load denominator. 0 means full resolution.
     *
     * Returns null rather than throwing when the bytes are not an image this stack can read: a
     * preview that will not decode is a missing picture, not a failed run.
     */
    /**
     * Decodes the file at [path].
     *
     * **The one to use for a preview.** The shim's in-memory entry point handles JPEG only —
     * it says so itself, `PI_ERR_UNSUPPORTED: "in-memory decode supports JPEG only"` — while
     * §5's whole preview tier is HEIC. Reading from a path costs nothing here: browse-to-cache
     * has already put the blob on disk.
     */
    public fun decodeFile(path: String, maxLongEdge: Int = 0, srgb: Boolean = false): Decoded? = Arena.ofConfined().use { call ->
        val image = call.allocate(IMAGE)
        val error = call.allocate(ERROR)
        imageInit.invokeExact(image)
        val result = decodePath.invokeExact(call.allocateFrom(path), maxLongEdge, image, error) as Int
        if (result != PI_OK) {
            onError(error.errorMessage(), result)
            return null
        }
        try {
            image.finish(orientation = 1, srgb = srgb, error = error)
        } finally {
            imageFree.invokeExact(image)
        }
    }

    /**
     * Decodes a bare JPEG stream — a CR2's carved one (§11) — turned by [orientation], which such a
     * stream does not carry itself, and converted to sRGB when [srgb] asks.
     */
    public fun decodeJpeg(encoded: ByteArray, maxLongEdge: Int, orientation: Int, srgb: Boolean): Decoded? =
        Arena.ofConfined().use { call ->
            val input = call.allocate(encoded.size.toLong()).apply {
                MemorySegment.copy(encoded, 0, this, ValueLayout.JAVA_BYTE, 0, encoded.size)
            }
            val image = call.allocate(IMAGE)
            val error = call.allocate(ERROR)
            imageInit.invokeExact(image)
            val result = decodeMemory.invokeExact(input, encoded.size.toLong(), maxLongEdge, image, error) as Int
            if (result != PI_OK) {
                onError(error.errorMessage(), result)
                return null
            }
            try {
                image.finish(orientation, srgb, error)
            } finally {
                imageFree.invokeExact(image)
            }
        }

    /**
     * The decoded image turned and colour-converted in place, then copied out. A step that fails
     * is reported and skipped: a photograph drawn unturned or in its own colours is still the
     * photograph, where null is none at all.
     */
    private fun MemorySegment.finish(orientation: Int, srgb: Boolean, error: MemorySegment): Decoded {
        if (orientation != 1) {
            val turned = applyOrientation.invokeExact(this, orientation, error) as Int
            if (turned != PI_OK) onError(error.errorMessage(), turned)
        }
        if (srgb) {
            val converted = convertToSrgb.invokeExact(this, error) as Int
            if (converted != PI_OK) onError(error.errorMessage(), converted)
        }
        return toDecoded()
    }

    public fun decode(encoded: ByteArray, maxLongEdge: Int = 0): Decoded? = Arena.ofConfined().use { call ->
        val input = call.allocate(encoded.size.toLong()).apply {
            MemorySegment.copy(encoded, 0, this, ValueLayout.JAVA_BYTE, 0, encoded.size)
        }
        val image = call.allocate(IMAGE)
        val error = call.allocate(ERROR)
        imageInit.invokeExact(image)
        val result = decodeMemory.invokeExact(
            input, encoded.size.toLong(), maxLongEdge, image, error,
        ) as Int
        // The shim's ownership rule: PI_OK is zero, and on a nonzero return nothing was
        // allocated -- so there is deliberately nothing to free on this path.
        if (result != PI_OK) {
            // The C side fills `pi_error` with a message from whichever library refused. §1's
            // no-opaque-errors rule applies to a binding too: discarding it leaves "the
            // picture did not appear" as the only symptom.
            onError(error.errorMessage(), result)
            return null
        }
        try {
            image.toDecoded()
        } finally {
            // The buffer belongs to the C side; the copy above is what outlives this call.
            imageFree.invokeExact(image)
        }
    }

    private fun MemorySegment.toDecoded(): Decoded {
        val width = get(ValueLayout.JAVA_INT, IMAGE.byteOffset(width_))
        val height = get(ValueLayout.JAVA_INT, IMAGE.byteOffset(height_))
        val channels = get(ValueLayout.JAVA_INT, IMAGE.byteOffset(channels_))
        val pixels = get(ValueLayout.ADDRESS, IMAGE.byteOffset(pixels_))
            .reinterpret(width.toLong() * height * channels)
        return Decoded(width, height, channels, pixels.toArray(ValueLayout.JAVA_BYTE))
    }

    override fun close(): Unit = arena.close()

    /** `pi_error.message` is a NUL-terminated 256-byte buffer following the code. */
    private fun MemorySegment.errorMessage(): String {
        val bytes = ByteArray(256)
        MemorySegment.copy(this, ValueLayout.JAVA_BYTE, ERROR.byteOffset(message_), bytes, 0, 256)
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return bytes.decodeToString(0, end)
    }

    private fun handle(symbol: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(
            lookup.find(symbol).orElseThrow { UnsatisfiedLinkError("no $symbol in the decode shim") },
            descriptor,
        )

    /** Interleaved 8-bit RGB or RGBA, top-down, stride = width * channels. */
    public class Decoded(
        public val width: Int,
        public val height: Int,
        public val channels: Int,
        public val pixels: ByteArray,
    )

    private companion object {
        const val PI_OK = 0

        /** Mirrors `pi_image` in photos_imaging.h. */
        val IMAGE: MemoryLayout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("pixels"),
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height"),
            ValueLayout.JAVA_INT.withName("channels"),
            ValueLayout.JAVA_INT.withName("source_width"),
            ValueLayout.JAVA_INT.withName("source_height"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("icc"),
            ValueLayout.JAVA_LONG.withName("icc_len"),
        )

        /** `pi_error`: an int and a 256-byte message. */
        val ERROR: MemoryLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("code"),
            MemoryLayout.sequenceLayout(256, ValueLayout.JAVA_BYTE).withName("message"),
        )

        val pixels_: MemoryLayout.PathElement = MemoryLayout.PathElement.groupElement("pixels")
        val width_: MemoryLayout.PathElement = MemoryLayout.PathElement.groupElement("width")
        val height_: MemoryLayout.PathElement = MemoryLayout.PathElement.groupElement("height")
        val channels_: MemoryLayout.PathElement = MemoryLayout.PathElement.groupElement("channels")
        val message_: MemoryLayout.PathElement = MemoryLayout.PathElement.groupElement("message")
    }
}
