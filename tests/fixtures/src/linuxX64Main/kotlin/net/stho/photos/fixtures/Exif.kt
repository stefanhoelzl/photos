package net.stho.photos.fixtures

/**
 * EXIF and container shapes, written by hand.
 *
 * The pipeline reads metadata with libexif; these produce the bytes it reads, so a fixture and
 * the real thing cannot disagree about what a tag looks like on the wire.
 */

/**
 * An EXIF APP1 payload — `"Exif\0\0"` followed by a little-endian TIFF carrying the tags given.
 * Shared by the oriented-JPEG and EXIF-bearing-HEIC fixtures.
 *
 * Only the shapes the pipeline actually reads: SHORT for orientation, ASCII for strings.
 */
public fun exifApp1(orientation: Int? = null, model: String? = null): ByteArray {
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
public fun ByteArray.withApp1(app1: ByteArray): ByteArray {
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
public fun ByteArray.wrappedInCr2(width: Int, height: Int, model: String? = null): ByteArray {
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
