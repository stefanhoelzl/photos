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
public class Cr2Carve(
    /** The embedded JPEG, byte for byte: a bare stream, with no EXIF of its own. */
    public val jpeg: ByteArray,
    public val width: Int,
    public val height: Int,
    /** IFD0's EXIF orientation, 1–8; 1 when the CR2 names none. The stream is not rotated. */
    public val orientation: Int,
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
 * The carve itself, a byte-range copy with no native code in it — shared by the CLI's pipeline,
 * which grafts the CR2's EXIF onto the result, and the desktop viewer (§11), which only decodes it.
 */
public fun ByteArray.carveCr2(): Cr2Carve {
    if (size <= 16) throw Cr2Exception(Cr2Exception.Reason.NOT_A_TIFF)
    val bigEndian = when {
        this[0] == 0x4D.toByte() && this[1] == 0x4D.toByte() -> true
        this[0] == 0x49.toByte() && this[1] == 0x49.toByte() -> false
        else -> throw Cr2Exception(Cr2Exception.Reason.NOT_A_TIFF)
    }

    val reader = TiffReader(this, bigEndian)
    var offset = (reader.u32(4) ?: throw Cr2Exception(Cr2Exception.Reason.NOT_A_TIFF)).toInt()
    var index = 0
    // IFD0's Orientation, which the bare JPEG stream does not carry: a SHORT, held in the first
    // two bytes of the entry's value field whatever the byte order.
    var orientation: Int? = null

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
            if (index == 0 && tag == TAG_ORIENTATION) orientation = reader.u16(base + 8)?.takeIf { it in 1..8 }
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
            return Cr2Carve(
                jpeg = jpeg,
                width = (entries[TAG_IMAGE_WIDTH] ?: 0L).toInt(),
                height = (entries[TAG_IMAGE_LENGTH] ?: 0L).toInt(),
                orientation = orientation ?: 1,
            )
        }

        offset = (reader.u32(offset + 2 + count * 12) ?: break).toInt()
        index++
    }
    throw Cr2Exception(Cr2Exception.Reason.NO_EMBEDDED_JPEG)
}

private const val TAG_IMAGE_WIDTH = 0x0100
private const val TAG_IMAGE_LENGTH = 0x0101
private const val TAG_COMPRESSION = 0x0103
private const val TAG_ORIENTATION = 0x0112
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
