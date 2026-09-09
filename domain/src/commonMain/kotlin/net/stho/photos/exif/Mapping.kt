package net.stho.photos.exif

import kotlin.math.abs
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow

/**
 * What an EXIF tag *means*.
 *
 * Every judgement lives here: date parsing, the sign convention on GPS references,
 * rational-to-degrees conversion, and the orientation-driven dimension swap. This is the code
 * §7 means when it says a laptop/phone disagreement would corrupt the catalog — extraction is
 * each platform's own library, interpretation is shared and is this file.
 */

private val dateTags = listOf("DateTimeOriginal", "DateTimeDigitized", "DateTime")
private val widthTags = listOf("PixelXDimension", "ImageWidth", "PixelWidth")
private val heightTags = listOf("PixelYDimension", "ImageLength", "ImageHeight", "PixelHeight")

/**
 * When the photograph was taken, to whole seconds — EXIF's own resolution, so nothing is lost
 * that was ever really there.
 *
 * EXIF dates are `YYYY:MM:DD HH:MM:SS` in **local time with no zone**, and the standard offers
 * no way to know which zone that was. Reading them as UTC is the only stable choice: it gives
 * the same instant on every device, in every locale, forever. The alternative — the reader's
 * current zone — would move every photo in the library when you cross a border.
 */
public fun ExifTags.takenAt(): Instant? =
    dateTags.firstNotNullOfOrNull { this[it]?.asText?.let(::parseExifDate) }

private fun parseExifDate(text: String): Instant? {
    // Hand-parsed rather than through a formatter: the format is fixed, and a formatter would
    // drag in locale and calendar behaviour that has to be pinned anyway.
    val digits = text.take(19)
    if (digits.length != 19) return null
    val parts = digits.split(':', '-', ' ').filter { it.isNotEmpty() }
    if (parts.size != 6) return null
    val n = parts.mapNotNull(String::toIntOrNull)
    if (n.size != 6) return null

    // A camera with a dead clock writes all zeroes; that is a default, not a date.
    if (n[0] <= 0 || n[1] <= 0 || n[2] <= 0) return null

    return runCatching { LocalDateTime(n[0], n[1], n[2], n[3], n[4], n[5]).toInstant(TimeZone.UTC) }
        .getOrNull()
}

/**
 * Where the photograph was taken, in signed degrees, or null if either half is absent or
 * unusable.
 *
 * Both halves must be present: half a coordinate places a photo on the null island, which
 * would then drag its album's centroid there too.
 */
public fun ExifTags.coordinate(): Coordinate? {
    // GPSStatus 'V' is EXIF for "measurement void" — the camera wrote a GPS block while having
    // no position. 1,028 photos in this library carry one, all with latitude identical to
    // longitude and both wildly out of range. The range check below would reject those anyway,
    // but only by luck: a void block holding plausible numbers would sail through and put a
    // photo somewhere it has never been.
    if (this["GPSStatus"]?.asText?.trim()?.uppercase()?.firstOrNull() == 'V') return null

    val latitude = degrees(this["GPSLatitude"], this["GPSLatitudeRef"]?.asText, negative = 'S') ?: return null
    val longitude = degrees(this["GPSLongitude"], this["GPSLongitudeRef"]?.asText, negative = 'W') ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

    // Exactly (0, 0) is in the Gulf of Guinea and is overwhelmingly a zeroed GPS field rather
    // than a photograph taken there.
    if (latitude == 0.0 && longitude == 0.0) return null
    return Coordinate(latitude, longitude)
}

/**
 * EXIF gives degrees/minutes/seconds as three rationals plus a hemisphere letter; some
 * libraries hand back a single already-signed number instead. Both are accepted.
 */
private fun degrees(value: ExifValue?, ref: String?, negative: Char): Double? {
    if (value == null) return null

    val magnitude = value.asList?.let { parts ->
        val d = parts.getOrNull(0)?.asDouble ?: return null
        val m = parts.getOrNull(1)?.asDouble ?: 0.0
        val s = parts.getOrNull(2)?.asDouble ?: 0.0
        d + m / 60 + s / 3600
    } ?: value.asDouble ?: return null

    if (!magnitude.isFinite()) return null

    // "S"/"W" makes it negative. When the library already signed the number and gave no ref,
    // that sign is respected as-is.
    val hemisphere = ref?.trim()?.uppercase()?.firstOrNull() ?: return magnitude
    return if (hemisphere == negative) -abs(magnitude) else abs(magnitude)
}

/**
 * Display dimensions — already rotated, per §3, so nothing downstream applies orientation.
 * Orientations 5–8 transpose the image, so width and height swap.
 */
public fun ExifTags.dimensions(): Dimensions? {
    val width = widthTags.firstNotNullOfOrNull { this[it]?.asLong }?.toInt() ?: return null
    val height = heightTags.firstNotNullOfOrNull { this[it]?.asLong }?.toInt() ?: return null
    if (width <= 0 || height <= 0) return null

    val orientation = this["Orientation"]?.asLong?.toInt() ?: 1
    return if (orientation in 5..8) Dimensions(height, width) else Dimensions(width, height)
}

/**
 * Builds a catalog row from these tags.
 *
 * What the caller supplies rather than EXIF: the filename, the byte size, the media type and
 * the object ids — all of which come from the filesystem or from the upload, never from a tag.
 *
 * [sourceFilename] equal to [filename] is dropped: the column means *"this row's blob is not
 * the file that was ingested"*, so storing the same string twice would say nothing.
 */
@OptIn(ExperimentalUuidApi::class)
public fun ExifTags.toPhotoRow(
    id: Uuid,
    filename: String,
    sourceFilename: String? = null,
    bytes: Long? = null,
    sourceBytes: Long? = null,
    contentHash: String? = null,
    mediaType: MediaType = MediaType.PHOTO,
    imageId: Uuid? = null,
    liveStillId: Uuid? = null,
    liveVideoId: Uuid? = null,
    videoId: Uuid? = null,
): PhotoRow {
    val coordinate = coordinate()
    val size = dimensions()
    return PhotoRow(
        id = id,
        filename = filename,
        sourceFilename = sourceFilename.takeIf { it != filename },
        takenAt = takenAt(),
        latitude = coordinate?.latitude,
        longitude = coordinate?.longitude,
        width = size?.width,
        height = size?.height,
        bytes = bytes,
        sourceBytes = sourceBytes,
        contentHash = contentHash,
        mediaType = mediaType,
        imageId = imageId,
        liveStillId = liveStillId,
        liveVideoId = liveVideoId,
        videoId = videoId,
    )
}
