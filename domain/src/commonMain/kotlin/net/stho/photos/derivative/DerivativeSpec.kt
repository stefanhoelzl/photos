package net.stho.photos.derivative

import kotlin.math.min

/**
 * What every derivative must aim at, on both platforms.
 *
 * The phone and the laptop encode with different libraries — ImageIO/AVFoundation there,
 * libjpeg-turbo/libheif/ffmpeg here — so the *bytes* they produce for one photo will never
 * match, and nothing in the system compares them: blobs are immutable and UUID-keyed, so two
 * encodings of the same photo are simply two different blobs.
 *
 * The *target* is a different matter. If one device produced 256px thumbs and the other 320px,
 * or one baked orientation and the other did not, the library would be visibly inconsistent
 * depending on which device happened to ingest an album. That is the same class of
 * laptop/phone disagreement §7 exists to prevent, so the numbers live here beside `ExifMapper`
 * rather than in either backend.
 */
public object DerivativeSpec {

    // ---------------------------------------------------------------- thumbnails

    /**
     * Thumbnails are square, and they are cropped rather than fitted.
     *
     * Every consumer in the app is a square cover-fit box — the grid, the 54pt album-list
     * cover, the 38pt map pin and search row, the 30pt filmstrip, the 26pt set-cover dialog.
     * Nothing displays a thumbnail at its own aspect ratio, so storing one stores pixels no
     * screen ever shows.
     *
     * Fitting into 256×256 leaves a 3:2 photo 256×171, and a 4-column grid tile on a 3× iPhone
     * is 287 device pixels — a 1.68× upscale, visibly soft. Filling it is 1.12×. Measured on
     * 120 photos from the real library: fitted 8.78 KB, cropped 11.59 KB.
     */
    public const val THUMBNAIL_EDGE: Int = 256

    /**
     * Quality 75 with optimized Huffman tables. Reproduces the 8.7 KB/photo the original
     * benchmark recorded, when applied to the geometry that benchmark actually used.
     */
    public const val THUMBNAIL_QUALITY: Int = 75

    // ---------------------------------------------------------------- previews

    /**
     * 2048px on the **long** edge, aspect preserved: the fullscreen viewer is the one
     * contain-fit surface in the app, so a preview's shape has to survive.
     */
    public const val PREVIEW_LONG_EDGE: Int = 2048

    /**
     * Never upscale. 25 of 120 sampled photos were already under 2048px and were re-encoded at
     * native size — that was part of the methodology behind the recorded 12.35 GB, and
     * inventing pixels would only inflate it.
     */
    public const val PREVIEW_UPSCALES: Boolean = false

    /**
     * libheif lossy quality for the HEIC preview.
     *
     * Derived by PSNR-matching against a 2048px JPEG q84 baseline, measured over 100 photos
     * spread across the real library:
     * ```
     *     JPEG q84 baseline   38.81 dB   427.3 KB/photo
     *     HEIC q45            37.91 dB   222.7 KB     below the baseline
     *     HEIC q50            39.01 dB   285.1 KB  ←  lowest that matches
     *     HEIC q55            40.64 dB   407.3 KB
     * ```
     * Following the stated method rather than the recorded number costs nothing in measured
     * fidelity and saves ~2.5 GB. If fullscreen ever looks soft, 55 is the conservative step.
     */
    public const val PREVIEW_QUALITY: Int = 50

    // ---------------------------------------------------------------- video

    /**
     * 1080p is a **ceiling, not a target**. 137 files in the library are 640×480 or smaller;
     * scaling those up is 20× the pixels for no added detail. Only the 14 files above 1080p
     * are actually scaled.
     */
    public const val VIDEO_MAX_HEIGHT: Int = 1080

    /**
     * HEVC rather than H.264. 223 of 550 videos are already HEVC, iOS 18 hardware-decodes it on
     * every supported device, and x265 is in the build regardless for HEIC previews — so this
     * is the encoder that was already there, and it drops libx264 from the stack. It is also
     * the only choice that does not *inflate* the 132 files already at 1080p.
     */
    public const val VIDEO_QUALITY: Int = 28

    /**
     * Where to take a video's poster still. Opening frames are often a fade from black, so one
     * second in is a better picture of the video than frame zero.
     */
    public fun posterTime(duration: Double): Double =
        if (duration.isFinite() && duration > 0) min(1.0, duration / 2) else 0.0

    // ---------------------------------------------------------------- colour

    /**
     * Thumbnails are converted to sRGB and carry no ICC profile.
     *
     * 44% of the library is tagged, and embedding those profiles costs 4.6–26.5% of an 11.6 KB
     * thumbnail — to correct something invisible in a 96pt tile. Untagged output is interpreted
     * as sRGB by every consumer, so converting is what makes that true.
     */
    public val THUMBNAIL_COLOR: ColorHandling = ColorHandling.CONVERT_TO_SRGB

    /**
     * Previews are never colour-converted; whatever profile the source carried is embedded
     * unchanged. ~8–9% of the library is Display P3, and fullscreen is exactly where gamut is
     * on show.
     */
    public val PREVIEW_COLOR: ColorHandling = ColorHandling.PASS_THROUGH

    public enum class ColorHandling { CONVERT_TO_SRGB, PASS_THROUGH }

    /**
     * Transparency is composited onto white before encoding — 22 PNGs and 4 TIFFs have an alpha
     * channel, and JPEG cannot represent one at all. White rather than black because the grid
     * is `#1a1a1a`: a mostly-transparent image flattened onto black dissolves into its own tile.
     */
    public val ALPHA_BACKGROUND: Rgb = Rgb(255u, 255u, 255u)

    public data class Rgb(val r: UByte, val g: UByte, val b: UByte)
}
