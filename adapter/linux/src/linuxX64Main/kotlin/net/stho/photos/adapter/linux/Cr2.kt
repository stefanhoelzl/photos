package net.stho.photos.adapter.linux

/**
 * Carving the full-resolution JPEG out of a Canon CR2.
 *
 * §5 calls this "developing the RAW", which suggests a demosaic. It is not one. Every CR2 in
 * the library carries a complete, full-resolution baseline JPEG at IFD0 — 5184×3456 at about
 * 2.5 MB, the camera's own rendering — so the derivative §5 wants already exists inside the
 * file and extracting it is a byte-range copy.
 *
 * That is what kept LibRaw, a demosaic pass and a set of development parameters out of
 * milestone C entirely, and it revises the projected cost of the 139 CR2s from ~1.2 GB to
 * ~0.35 GB. What it gives up is the latitude a real raw development would have recovered; for a
 * 2012 archive of holiday photographs, the camera's own JPEG is the picture that would have
 * been taken had the camera been set to JPEG.
 *
 * IFD3 holds the actual sensor data and IFD2 a 660×441 preview; neither is touched.
 */
public data class Cr2Extraction(
    /** The embedded JPEG, with an EXIF APP1 grafted in from the CR2's own IFDs. */
    public val jpeg: ByteArray,
    public val width: Int,
    public val height: Int,
)

/** A CR2 that cannot be carved. Per-item and recoverable, like [ImagingException]. */
public class Cr2Exception(public val reason: Reason) : Exception(reason.detail) {
    public enum class Reason(public val detail: String) {
        NOT_A_TIFF("not a TIFF-rooted file"),
        NO_EMBEDDED_JPEG("no full-resolution JPEG at IFD0"),
        TRUNCATED("file ends inside the embedded JPEG"),
    }
}

/**
 * Reads the whole file, since the JPEG at IFD0 is a couple of megabytes into a ~23 MB file and
 * the EXIF graft wants the IFDs too. 139 files, once.
 */
public fun carveEmbeddedJpeg(path: String): Cr2Extraction = readAllBytes(path).carveEmbeddedJpeg()

public fun ByteArray.carveEmbeddedJpeg(): Cr2Extraction {
    if (size <= 16) throw Cr2Exception(Cr2Exception.Reason.NOT_A_TIFF)
    val bigEndian = when {
        this[0] == 0x4D.toByte() && this[1] == 0x4D.toByte() -> true
        this[0] == 0x49.toByte() && this[1] == 0x49.toByte() -> false
        else -> throw Cr2Exception(Cr2Exception.Reason.NOT_A_TIFF)
    }

    val reader = TiffReader(this, bigEndian)
    var offset = (reader.u32(4) ?: throw Cr2Exception(Cr2Exception.Reason.NOT_A_TIFF)).toInt()
    var index = 0

    // IFD0 is the one that carries the full-resolution JPEG. Walking the chain rather than
    // assuming a fixed layout, because "IFD0 is first" is a convention of Canon's writer, not a
    // guarantee of the format.
    while (offset > 0 && offset + 2 <= size && index < 8) {
        val count = reader.u16(offset) ?: break
        val entries = mutableMapOf<Int, Long>()
        for (i in 0 until count) {
            val base = offset + 2 + i * 12
            val tag = reader.u16(base) ?: break
            reader.u16(base + 2) ?: break // type, read for its bounds check only
            reader.u32(base + 4) ?: break // count, likewise
            val value = reader.u32(base + 8) ?: break
            entries[tag] = value
        }

        // Compression 6 == "old-style JPEG", which is how the CR2 stores its full-size preview.
        // StripOffsets/StripByteCounts then bound the JPEG exactly.
        val start = entries[TAG_STRIP_OFFSETS]
        val length = entries[TAG_STRIP_BYTE_COUNTS]
        if (entries[TAG_COMPRESSION] == COMPRESSION_OLD_JPEG && start != null && length != null &&
            length > 0
        ) {
            if (start + length > size) throw Cr2Exception(Cr2Exception.Reason.TRUNCATED)
            val jpeg = copyOfRange(start.toInt(), (start + length).toInt())
            if (jpeg.size <= 3 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
                throw Cr2Exception(Cr2Exception.Reason.NO_EMBEDDED_JPEG)
            }
            return Cr2Extraction(
                jpeg = graftExif(into = jpeg, from = this),
                width = (entries[TAG_IMAGE_WIDTH] ?: 0L).toInt(),
                height = (entries[TAG_IMAGE_LENGTH] ?: 0L).toInt(),
            )
        }

        offset = (reader.u32(offset + 2 + count * 12) ?: break).toInt()
        index++
    }
    throw Cr2Exception(Cr2Exception.Reason.NO_EMBEDDED_JPEG)
}

/**
 * Inserts an EXIF APP1 segment built from the CR2's own TIFF IFDs.
 *
 * The extracted stream has no APP1 of its own — it is a bare JPEG living inside a TIFF, so its
 * date, GPS and orientation are all in the CR2's directories rather than in the bytes being
 * copied out. Every other original in the bucket is uploaded byte-for-byte with its metadata
 * intact; this is the only original the pipeline synthesises, and without the graft it would be
 * the only one that arrives blank.
 *
 * A failed graft is not fatal: the catalog carries the date and coordinates regardless, so the
 * worst case is an uploaded JPEG that is merely less self-describing.
 */
internal fun graftExif(into: ByteArray, from: ByteArray): ByteArray {
    val app1 = exifApp1FromTiff(from)?.takeIf { it.isNotEmpty() } ?: return into
    // APP1's length is a 16-bit field covering itself, so a large block cannot be written.
    if (app1.size + 2 > 0xFFFF) return into

    val length = app1.size + 2
    val header = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), // SOI
        0xFF.toByte(), 0xE1.toByte(), // APP1
        (length ushr 8).toByte(), (length and 0xFF).toByte(),
    )
    // Everything after the original SOI.
    return header + app1 + into.copyOfRange(2, into.size)
}

private const val TAG_IMAGE_WIDTH = 0x0100
private const val TAG_IMAGE_LENGTH = 0x0101
private const val TAG_COMPRESSION = 0x0103
private const val TAG_STRIP_OFFSETS = 0x0111
private const val TAG_STRIP_BYTE_COUNTS = 0x0117
private const val COMPRESSION_OLD_JPEG = 6L

/** TIFF's two byte orders, read only as far as the carve needs. */
private class TiffReader(private val data: ByteArray, private val bigEndian: Boolean) {

    fun u16(offset: Int): Int? {
        if (offset < 0 || offset + 2 > data.size) return null
        val a = data[offset].toInt() and 0xFF
        val b = data[offset + 1].toInt() and 0xFF
        return if (bigEndian) (a shl 8) or b else (b shl 8) or a
    }

    fun u32(offset: Int): Long? {
        if (offset < 0 || offset + 4 > data.size) return null
        val bytes = LongArray(4) { data[offset + it].toLong() and 0xFF }
        return if (bigEndian) {
            (bytes[0] shl 24) or (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]
        } else {
            (bytes[3] shl 24) or (bytes[2] shl 16) or (bytes[1] shl 8) or bytes[0]
        }
    }
}
