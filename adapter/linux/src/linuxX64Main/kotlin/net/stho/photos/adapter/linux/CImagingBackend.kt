@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import net.stho.photos.exif.ExifTags
import net.stho.photos.exif.ExifValue
import net.stho.photos.pipeline.MediaFormat
import net.stho.photos.ports.ImageBackend
import net.stho.photos.ports.MediaUnreadable
import photosimaging.pi_exif_read

/**
 * §7's `ImageBackend`, implemented with libexif and libheif.
 *
 * This is the extraction half only. What a tag *means* — that `"2013:07:04 18:22:11"` is a UTC
 * instant, that `GPSLatitudeRef 'S'` makes a latitude negative — is the shared EXIF mapping in
 * `:domain`, because that is where a laptop/phone disagreement would corrupt the catalog.
 */
public class CImagingBackend(private val probe: CImagingProbe = CImagingProbe()) : ImageBackend {

    override fun rawTags(path: String): ExifTags {
        // Videos keep their metadata in the container rather than an EXIF block, so they take
        // the probe path and are given the same tag names as everything else.
        if (probe.sniff(path) == MediaFormat.VIDEO) return videoTags(path)

        val values = mutableMapOf<String, ExifValue>()
        val holder = StableRef.create(values)
        try {
            imagingCall { err -> pi_exif_read(path, exifTagCollector, holder.asCPointer(), err) }
        } catch (e: ImagingException) {
            // Re-thrown as the port's declared failure. `ImagingException` is not visible in the
            // domain, so a caller there could only catch `Exception` — which would swallow a
            // fatal `PhotosFailure` and report it as "this file has no tags".
            throw MediaUnreadable("could not read EXIF from $path", e)
        } finally {
            holder.dispose()
        }
        return ExifTags(values)
    }

    /**
     * A video's tags, in the same vocabulary as a photo's, so the shared mapping needs no
     * special case and the catalog row is built the same way for both.
     */
    private fun videoTags(path: String): ExifTags {
        val info = probeVideo(path)
        // Rotation is baked into the poster and the transcode, so the dimensions reported here
        // are display dimensions — which is what §3 stores.
        val transposed = info.rotation == 90 || info.rotation == 270
        return ExifTags(
            buildMap {
                put("PixelXDimension", ExifValue.Integer((if (transposed) info.height else info.width).toLong()))
                put("PixelYDimension", ExifValue.Integer((if (transposed) info.width else info.height).toLong()))
                info.contentIdentifier?.let { put("AppleContentIdentifier", ExifValue.Text(it)) }
            },
        )
    }
}

/**
 * The shim's per-tag callback.
 *
 * First writer wins: IFD0 and IFD1 (the embedded thumbnail's own directory) both carry
 * Orientation and ImageWidth, and IFD1's describe a 160px thumbnail, not the photograph.
 */
private val exifTagCollector = staticCFunction { context: kotlinx.cinterop.COpaquePointer?,
    tag: kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>?,
    value: kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>?,
    ->
    if (context != null && tag != null && value != null) {
        val values = context.asStableRef<MutableMap<String, ExifValue>>().get()
        val name = tag.toKString()
        if (name !in values) values[name] = value.toKString().asExifValue()
    }
}

/**
 * Parses the shim's canonical rendering back into a typed value.
 *
 * The shim renders integers as decimal, rationals unreduced as `num/den`, and repeats either
 * separated by spaces — so a GPS coordinate arrives as three exact rationals rather than a
 * number some library already divided and rounded.
 *
 * Anything whose tokens do not all parse as numbers is text. That test is what keeps
 * `"2013:07:04 18:22:11"` — which has spaces, and would otherwise look like a list — a date
 * rather than a mangled array.
 */
internal fun String.asExifValue(): ExifValue {
    val tokens = split(' ').filter(String::isNotEmpty)
    if (tokens.isEmpty()) return ExifValue.Text(this)
    val parsed = tokens.map { it.asExifScalar() ?: return ExifValue.Text(this) }
    return parsed.singleOrNull() ?: ExifValue.Items(parsed)
}

private fun String.asExifScalar(): ExifValue? {
    val slash = indexOf('/')
    if (slash >= 0) {
        val numerator = substring(0, slash).toDoubleOrNull() ?: return null
        val denominator = substring(slash + 1).toDoubleOrNull() ?: return null
        return ExifValue.Rational(numerator, denominator)
    }
    toLongOrNull()?.let { return ExifValue.Integer(it) }
    // A bare float is not something EXIF stores, but a backend is free to hand one over.
    toDoubleOrNull()?.let { return ExifValue.Real(it) }
    return null
}
