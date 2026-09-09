package net.stho.photos.exif

/**
 * One EXIF value, as an imaging library hands it over.
 *
 * Deliberately not `Any`: the mapping has to make the same decision from the same input on both
 * platforms, and an untyped bag makes that a matter of what each library happened to box its
 * numbers as.
 */
public sealed interface ExifValue {
    public data class Text(val value: String) : ExifValue
    public data class Integer(val value: Long) : ExifValue
    public data class Real(val value: Double) : ExifValue

    /**
     * An EXIF rational, kept unreduced — GPS coordinates arrive as three of these and lose
     * precision if divided early.
     */
    public data class Rational(val numerator: Double, val denominator: Double) : ExifValue

    public data class Items(val values: List<ExifValue>) : ExifValue
}

public val ExifValue.asDouble: Double?
    get() = when (this) {
        is ExifValue.Integer -> value.toDouble()
        is ExifValue.Real -> value
        is ExifValue.Rational -> if (denominator == 0.0) null else numerator / denominator
        is ExifValue.Text -> value.toDoubleOrNull()
        is ExifValue.Items -> null
    }

public val ExifValue.asLong: Long?
    get() = when (this) {
        is ExifValue.Integer -> value
        is ExifValue.Real -> value.toLong()
        is ExifValue.Rational -> asDouble?.toLong()
        is ExifValue.Text -> value.toLongOrNull()
        is ExifValue.Items -> null
    }

public val ExifValue.asText: String? get() = (this as? ExifValue.Text)?.value

public val ExifValue.asList: List<ExifValue>? get() = (this as? ExifValue.Items)?.values

/**
 * The tags an [net.stho.photos.ports.ImageBackend] extracted from one file.
 *
 * Tag names are the EXIF/TIFF names, unprefixed: `DateTimeOriginal`, `GPSLatitude`,
 * `GPSLatitudeRef`, `Orientation`, `PixelXDimension`, and so on. Both backends normalise to
 * these before returning, so the mapping sees one vocabulary.
 */
public data class ExifTags(public val values: Map<String, ExifValue> = emptyMap()) {
    public operator fun get(tag: String): ExifValue? = values[tag]
}

public data class Coordinate(public val latitude: Double, public val longitude: Double)

public data class Dimensions(public val width: Int, public val height: Int)
