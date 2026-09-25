package net.stho.photos.faces

import kotlin.math.max
import kotlin.math.min

/**
 * A face's box, as fractions of the photograph's display width and height (§12).
 *
 * Fractions rather than pixels because the pixels a face was found in are not the pixels anyone
 * else sees: the CLI detects on its own decode of the original, the viewer draws over a decode
 * fitted to the screen, and the phone would draw over the 3200px HEIC. A verdict carries one of
 * these, so it has to mean the same place in all three.
 */
public data class FaceBox(
    public val x: Float,
    public val y: Float,
    public val width: Float,
    public val height: Float,
) {
    public val area: Float get() = width * height

    /** Intersection over union: 1 for the same box, 0 for boxes that do not touch. */
    public fun overlap(other: FaceBox): Float {
        val left = max(x, other.x)
        val top = max(y, other.y)
        val right = min(x + width, other.x + other.width)
        val bottom = min(y + height, other.y + other.height)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = area + other.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}

/**
 * One face the detector found, and what the embedder made of it.
 *
 * [embedding] is L2-normalised, so the cosine similarity of two faces is their dot product.
 * [landmarks] are the detector's five points — eyes, nose tip, mouth corners — as (x, y) pairs in
 * the same fractions as [box]. They are what alignment used, kept so a later embedder can be run
 * against the same detections.
 */
public class DetectedFace(
    public val box: FaceBox,
    public val landmarks: FloatArray,
    public val score: Float,
    public val embedding: FloatArray,
    /**
     * The variance of the Laplacian of the aligned crop the embedding came from: low for a blurred
     * face and for a small one upscaled to the crop's size. NaN where it was never measured.
     */
    public val sharpness: Float = Float.NaN,
)

/** The dot product of two unit vectors, which is their cosine similarity. */
public fun FloatArray.cosine(other: FloatArray): Float {
    var sum = 0f
    for (i in indices) sum += this[i] * other[i]
    return sum
}
