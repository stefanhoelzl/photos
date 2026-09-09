package net.stho.photos.fixtures

/**
 * A PNG, written by hand.
 *
 * Deflate's "stored" block type lets a valid PNG be produced with no compressor at all, which
 * is what keeps a PNG *encoder* out of decision 23's enumerated ffmpeg build for the sake of
 * test fixtures. The output is larger than a real PNG and entirely legal; what matters is that
 * ffmpeg's real PNG decoder reads it, alpha included.
 */
public fun syntheticPng(width: Int, height: Int, alpha: Boolean = true): ByteArray {
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

