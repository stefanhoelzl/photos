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

    // ---------------------------------------------------------------- the viewing image

    /**
     * The profile that produced an album's image blobs.
     *
     * Stored per album as `album_info.encoding_version`; `sync` re-derives any album below the
     * value this build carries. **0 is reserved** for "as uploaded, never encoded here", which
     * is what a phone-uploaded album holds until the laptop claims it (§7), so profiles number
     * from 1.
     *
     * Bump this and every album re-encodes on the next run, one album per commit, draining
     * over as many runs as it takes. That is the whole migration mechanism: there is no
     * separate pass and no second verb.
     */
    public const val ENCODING_VERSION: Int = 1

    /**
     * 3200px on the **long** edge, aspect preserved.
     *
     * This is a *viewing* tier, not an archive: the laptop library holds the originals, and
     * nothing in the zone is one. So the size is set by the largest screen that will ever
     * display it rather than by what the camera captured — 2.4× pinch zoom on a 3× iPhone,
     * and a 1.2× upscale on a 4K desktop panel.
     *
     * Measured against the real library: the median original is 3264px on the long edge and
     * 69% exceed 3200px, holding 88% of the bytes. Capping there takes the still-image tier
     * from ~100 GiB to ~14 GiB.
     */
    public const val IMAGE_LONG_EDGE: Int = 3200

    /**
     * Never upscale. 17.3% of the library is already at or below the cap and is re-encoded at
     * native size; inventing pixels would only inflate the tier.
     */
    public const val IMAGE_UPSCALES: Boolean = false

    /**
     * libheif lossy quality for the viewing image.
     *
     * **Chosen by eye on 1:1 crops, not by PSNR**, because HEVC intra artifacts are structured
     * rather than noise-like and PSNR does not see them the way a person does. The ladder was
     * judged on already-delivered 2000px files — faces and hair, compressed once already by
     * the photographer, which is the hardest case for visible loss:
     * ```
     *     q30    62 KiB   16% of source   33.8 dB
     *     q45   168 KiB   44%             37.6 dB   ←  chosen
     *     q60   302 KiB   78%             40.4 dB
     *     q70   397 KiB  103%             41.2 dB
     * ```
     * Note where that ladder stops paying: above roughly q60 the encoder is spending bytes
     * reproducing the source JPEG's own artifacts, and PSNR plateaus at ~41 dB because the
     * reference is itself lossy.
     *
     * Over the representative 150-file sample this stores **13.7% of source bytes, 424 KiB
     * per photo**. If fullscreen ever looks soft, 50 is the conservative step — and bumping
     * [ENCODING_VERSION] alongside it is what makes the library follow.
     */
    public const val IMAGE_QUALITY: Int = 45

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
     * The viewing image is never colour-converted; whatever profile the source carried is
     * embedded unchanged. ~8–9% of the library is Display P3, and fullscreen is exactly where
     * gamut is on show.
     */
    public val IMAGE_COLOR: ColorHandling = ColorHandling.PASS_THROUGH

    public enum class ColorHandling { CONVERT_TO_SRGB, PASS_THROUGH }

    /**
     * Transparency is composited onto white before encoding — 22 PNGs and 4 TIFFs have an alpha
     * channel, and JPEG cannot represent one at all. White rather than black because the grid
     * is `#1a1a1a`: a mostly-transparent image flattened onto black dissolves into its own tile.
     */
    public val ALPHA_BACKGROUND: Rgb = Rgb(255u, 255u, 255u)

    public data class Rgb(val r: UByte, val g: UByte, val b: UByte)
}
