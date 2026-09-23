package net.stho.photos.adapter.linux

public data class Cr2Extraction(
    /** The embedded JPEG, with an EXIF APP1 grafted in from the CR2's own IFDs. */
    public val jpeg: ByteArray,
    public val width: Int,
    public val height: Int,
)

/**
 * Reads the whole file, since the JPEG at IFD0 is a couple of megabytes into a ~23 MB file and
 * the EXIF graft wants the IFDs too. 139 files, once.
 */
public fun carveEmbeddedJpeg(path: String): Cr2Extraction = readAllBytes(path).carveEmbeddedJpeg()

/** [carveCr2], with the CR2's own metadata grafted onto the stream. */
public fun ByteArray.carveEmbeddedJpeg(): Cr2Extraction {
    val carved = carveCr2()
    return Cr2Extraction(jpeg = graftExif(into = carved.jpeg, from = this), width = carved.width, height = carved.height)
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

