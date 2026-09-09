@file:OptIn(ExperimentalForeignApi::class, ExperimentalUuidApi::class)

package net.stho.photos.adapter.linux

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.write
import photosimaging.pi_fixture_write_heic_with_exif
import photosimaging.pi_fixture_write_video
import photosimaging.pi_image_alloc

/**
 * Generated test inputs.
 *
 * Decision 16: nothing binary is committed. The real library is personal data, and a committed
 * corpus is a set of files that quietly stop representing anything — the same reasoning that
 * made milestone B forge a future shard rather than ship a `.db`.
 */

/**
 * A deterministic image with real structure in it.
 *
 * Not flat colour: a flat image compresses to almost nothing, so a size assertion on it would
 * pass no matter how badly the encoder was configured. The gradient plus a checker gives the
 * DCT something to do, so byte counts mean something.
 */
internal fun syntheticImage(width: Int, height: Int, alpha: Boolean = false): PixelImage {
    val channels = if (alpha) 4 else 3
    val image = PixelImage()
    try {
        imagingCall { err -> pi_image_alloc(image.raw.ptr, width, height, channels, err) }
    } catch (failure: Throwable) {
        image.close()
        throw failure
    }
    val pixels = image.pixels!!
    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = (y * width + x) * channels
            val checker = ((x / 16) + (y / 16)) % 2 == 0
            pixels[i] = (x * 255 / maxOf(width - 1, 1)).toUByte()
            pixels[i + 1] = (y * 255 / maxOf(height - 1, 1)).toUByte()
            pixels[i + 2] = if (checker) 230u else 40u
            // A horizontal alpha ramp, so flattening onto white is visible and testable rather
            // than a no-op.
            if (alpha) pixels[i + 3] = (x * 255 / maxOf(width - 1, 1)).toUByte()
        }
    }
    return image
}

/** A JPEG, via the pipeline's own encoder. */
internal fun syntheticJpeg(width: Int, height: Int, quality: Int = 90): ByteArray =
    syntheticImage(width, height).use { it.encodedJpeg(quality, optimize = true) }

/**
 * A JPEG carrying an EXIF orientation tag.
 *
 * Worth generating because its absence hid a real bug: libjpeg does not rotate, so without an
 * oriented fixture nothing proved the pipeline was baking orientation at all.
 */
internal fun syntheticOrientedJpeg(
    width: Int,
    height: Int,
    orientation: Int,
    quality: Int = 90,
): ByteArray = syntheticJpeg(width, height, quality).withApp1(exifApp1(orientation = orientation))

/** A HEIC, via the pipeline's own encoder. */
internal fun syntheticHeic(width: Int, height: Int, quality: Int = 70): ByteArray =
    syntheticImage(width, height).use { it.encodedHeic(quality) }

/**
 * A HEIC that actually carries EXIF.
 *
 * [syntheticHeic] encodes pixels and nothing else, so for a long time *every* synthetic HEIC
 * had no metadata — and a HEIF EXIF reader that returned nothing at all passed the entire suite
 * while dropping the date, GPS and Live Photo identifier of all 1,531 HEICs in the real library.
 */
internal fun writeSyntheticHeic(
    path: Path,
    width: Int,
    height: Int,
    orientation: Int? = null,
    model: String? = null,
) {
    val app1 = exifApp1(orientation, model)
    imagingCall { err ->
        app1.usePinned { pinned ->
            pi_fixture_write_heic_with_exif(
                path.toString(),
                width,
                height,
                pinned.addressOf(0).reinterpret<UByteVar>(),
                app1.size.convert(),
                err,
            )
        }
    }
}

/** A short HEVC/MP4 clip, optionally carrying a display matrix and a Live Photo identifier. */
internal fun writeSyntheticVideo(
    path: Path,
    width: Int = 64,
    height: Int = 48,
    frames: Int = 10,
    rotation: Int = 0,
    contentIdentifier: String? = null,
) {
    imagingCall { err ->
        pi_fixture_write_video(path.toString(), width, height, frames, rotation, contentIdentifier, err)
    }
}

/**
 * An EXIF APP1 payload — `"Exif\0\0"` followed by a little-endian TIFF carrying the tags given.
 * Shared by the oriented-JPEG and EXIF-bearing-HEIC fixtures.
 *
 * Only the shapes the pipeline actually reads: SHORT for orientation, ASCII for strings.
 */
internal fun exifApp1(orientation: Int? = null, model: String? = null): ByteArray {
    val entries = mutableListOf<TiffEntry>()
    var heap = ByteArray(0)
    if (orientation != null) {
        entries += TiffEntry(0x0112, type = 3, count = 1, inline = le16(orientation) + byteArrayOf(0, 0))
    }
    if (model != null) {
        heap = model.encodeToByteArray() + byteArrayOf(0)
        entries += TiffEntry(0x0110, type = 2, count = heap.size)
    }
    entries.sortBy(TiffEntry::tag)

    // Offsets are from the TIFF header, which sits at the start of the payload.
    val heapOffset = 8 + 2 + entries.size * 12 + 4

    var tiff = byteArrayOf(0x49, 0x49, 0x2A, 0x00) + le32(8) + le16(entries.size)
    for (entry in entries) {
        tiff += le16(entry.tag) + le16(entry.type) + le32(entry.count) +
            (entry.inline ?: le32(heapOffset))
    }
    tiff += le32(0) + heap

    return "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
}

/** One IFD entry, as far as the fixtures need to write one. */
private data class TiffEntry(
    val tag: Int,
    val type: Int,
    val count: Int,
    /** The four bytes that live in the entry itself, or null when the value lives at an offset. */
    val inline: ByteArray? = null,
)

/** Splices an APP1 segment in immediately after this JPEG's SOI. */
internal fun ByteArray.withApp1(app1: ByteArray): ByteArray {
    val length = app1.size + 2
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) +
        byteArrayOf((length ushr 8).toByte(), (length and 0xFF).toByte()) +
        app1 + copyOfRange(2, size)
}

/**
 * A CR2-shaped file wrapping this JPEG: a little-endian TIFF whose IFD0 announces "old-style
 * JPEG" and points at a real JPEG stream, exactly as Canon writes it.
 *
 * This is the shape decision 2 relies on — the full-resolution camera JPEG that makes
 * "developing" a raw a byte-range copy rather than a demosaic.
 *
 * [model] adds an ASCII tag to IFD0. It exists so the EXIF graft has something to carry:
 * without any tags the graft correctly produces nothing and that path is never exercised.
 */
internal fun ByteArray.wrappedInCr2(width: Int, height: Int, model: String? = null): ByteArray {
    val modelBytes = model?.let { it.encodeToByteArray() + byteArrayOf(0) } ?: ByteArray(0)
    val jpegLength = size
    val entries = buildList {
        add(TiffEntry(0x0100, type = 4, count = 1, inline = le32(width)))       // ImageWidth
        add(TiffEntry(0x0101, type = 4, count = 1, inline = le32(height)))      // ImageLength
        add(TiffEntry(0x0103, type = 3, count = 1, inline = le32(6)))           // old-style JPEG
        add(TiffEntry(0x0111, type = 4, count = 1))                             // StripOffsets
        add(TiffEntry(0x0117, type = 4, count = 1, inline = le32(jpegLength)))  // StripByteCounts
        // ASCII values longer than four bytes live outside the entry, at an offset.
        if (model != null) add(TiffEntry(0x0110, type = 2, count = modelBytes.size))
    }.sortedBy(TiffEntry::tag) // TIFF requires ascending tag order

    val modelOffset = 16 + 2 + entries.size * 12 + 4
    val jpegOffset = modelOffset + modelBytes.size

    var out = byteArrayOf(0x49, 0x49, 0x2A, 0x00) + // "II", 42
        le32(16) +                                  // IFD0 lives at offset 16
        byteArrayOf(0x43, 0x52, 0x02, 0x00) +       // CR2 magic at offset 8
        byteArrayOf(0, 0, 0, 0) +                   // pad out to 16
        le16(entries.size)
    for (entry in entries) {
        val value = entry.inline ?: when (entry.tag) {
            0x0111 -> le32(jpegOffset)
            else -> le32(modelOffset)
        }
        out += le16(entry.tag) + le16(entry.type) + le32(entry.count) + value
    }
    out += le32(0) // no next IFD
    out += modelBytes
    out += this
    return out
}

/**
 * A PNG, written by hand.
 *
 * Deflate's "stored" block type lets a valid PNG be produced with no compressor at all, which
 * is what keeps a PNG *encoder* out of decision 23's enumerated ffmpeg build for the sake of
 * test fixtures. The output is larger than a real PNG and entirely legal; what matters is that
 * ffmpeg's real PNG decoder reads it, alpha included.
 */
internal fun syntheticPng(width: Int, height: Int, alpha: Boolean = true): ByteArray {
    val channels = if (alpha) 4 else 3
    val raw = ByteArray(height * (1 + width * channels))
    var at = 0
    for (y in 0 until height) {
        raw[at++] = 0 // filter type: none
        for (x in 0 until width) {
            raw[at++] = (x * 255 / maxOf(width - 1, 1)).toByte()
            raw[at++] = (y * 255 / maxOf(height - 1, 1)).toByte()
            raw[at++] = if (((x / 16) + (y / 16)) % 2 == 0) 230.toByte() else 40
            if (alpha) raw[at++] = (x * 255 / maxOf(width - 1, 1)).toByte()
        }
    }

    val ihdr = be32(width) + be32(height) +
        byteArrayOf(8, if (alpha) 6 else 2, 0, 0, 0) // depth, colour type, deflate/filter/interlace

    return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        pngChunk("IHDR", ihdr) +
        pngChunk("IDAT", zlibStored(raw)) +
        pngChunk("IEND", ByteArray(0))
}

private fun pngChunk(type: String, payload: ByteArray): ByteArray {
    val body = type.encodeToByteArray() + payload
    return be32(payload.size) + body + be32(crc32(body).toInt())
}

/** A zlib stream made only of stored (uncompressed) deflate blocks. */
private fun zlibStored(data: ByteArray): ByteArray {
    var out = byteArrayOf(0x78, 0x01) // deflate, 32K window, no dictionary
    if (data.isEmpty()) out += byteArrayOf(0x01, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
    var offset = 0
    while (offset < data.size) {
        val count = minOf(0xFFFF, data.size - offset)
        val end = offset + count
        out += byteArrayOf(
            if (end == data.size) 0x01 else 0x00,
            (count and 0xFF).toByte(),
            ((count shr 8) and 0xFF).toByte(),
            (count.inv() and 0xFF).toByte(),
            ((count.inv() shr 8) and 0xFF).toByte(),
        )
        out += data.copyOfRange(offset, end)
        offset = end
    }
    return out + be32(adler32(data).toInt())
}

private val crcTable = IntArray(256) { i ->
    var c = i
    repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
    c
}

private fun crc32(data: ByteArray): Long {
    var c = -1
    for (byte in data) c = crcTable[(c xor byte.toInt()) and 0xFF] xor (c ushr 8)
    return (c xor -1).toLong() and 0xFFFFFFFFL
}

private fun adler32(data: ByteArray): Long {
    var a = 1L
    var b = 0L
    for (byte in data) {
        a = (a + (byte.toInt() and 0xFF)) % 65521
        b = (b + a) % 65521
    }
    return (b shl 16) or a
}

internal fun le16(value: Int): ByteArray =
    byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

internal fun le32(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte(),
)

internal fun be32(value: Int): ByteArray = byteArrayOf(
    ((value shr 24) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
)

// ---------------------------------------------------------------- scratch files

/** A scratch directory that cleans up after itself. */
internal fun <R> withTemporaryDirectory(name: String, body: (Path) -> R): R {
    val directory = Path(SystemTemporaryDirectory, "photos-$name-${Uuid.random()}")
    SystemFileSystem.createDirectories(directory)
    try {
        return body(directory)
    } finally {
        removeRecursively(directory)
    }
}

private fun removeRecursively(path: Path) {
    val metadata = SystemFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemFileSystem.list(path).forEach(::removeRecursively)
    SystemFileSystem.delete(path, mustExist = false)
}

internal fun Path.write(bytes: ByteArray): Path {
    SystemFileSystem.sink(this).buffered().use { it.write(bytes) }
    return this
}

internal fun Path.readBytes(): ByteArray =
    SystemFileSystem.source(this).buffered().use { it.readByteArray() }
