package net.stho.photos.fixtures

/**
 * Generated test inputs.
 *
 * Decision 16: nothing binary is committed. The real library is personal data, and a committed
 * corpus is a set of files that quietly stop representing anything -- the same reasoning that
 * made milestone B forge a future shard rather than ship a `.db`.
 */

/**
 * A deterministic image with real structure in it, as interleaved 8-bit samples.
 *
 * Not flat colour: a flat image compresses to almost nothing, so a size assertion on it would
 * pass no matter how badly the encoder was configured. The gradient plus a checker gives the
 * DCT something to do, so byte counts mean something.
 *
 * This is the one definition of the pattern. The adapter wraps it in a `PixelImage` for tests
 * that compare buffers; everything else here encodes it.
 */
public fun syntheticPixels(width: Int, height: Int, channels: Int): ByteArray {
    val out = ByteArray(width * height * channels)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = (y * width + x) * channels
            out[i] = (x * 255 / maxOf(width - 1, 1)).toByte()
            out[i + 1] = (y * 255 / maxOf(height - 1, 1)).toByte()
            out[i + 2] = if (((x / 16) + (y / 16)) % 2 == 0) 230.toByte() else 40
            // A horizontal alpha ramp, so flattening onto white is visible and testable rather
            // than a no-op.
            if (channels == 4) out[i + 3] = (x * 255 / maxOf(width - 1, 1)).toByte()
        }
    }
    return out
}
