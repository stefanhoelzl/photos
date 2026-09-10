package net.stho.photos.exif

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import net.stho.photos.catalog.blobId
import net.stho.photos.model.MediaType

private fun tags(vararg pairs: Pair<String, ExifValue>) = ExifTags(mapOf(*pairs))

private fun text(value: String) = ExifValue.Text(value)
private fun int(value: Long) = ExifValue.Integer(value)
private fun real(value: Double) = ExifValue.Real(value)

/** Degrees, minutes and seconds as EXIF actually stores them: three rationals. */
private fun dms(d: Double, m: Double, s: Double) = ExifValue.Items(
    listOf(ExifValue.Rational(d, 1.0), ExifValue.Rational(m, 1.0), ExifValue.Rational(s, 1.0)),
)

class TakenAtTest {

    @Test
    fun readsTheCanonicalExifTimestamp() {
        val taken = tags("DateTimeOriginal" to text("2013:07:04 18:12:11")).takenAt()
        assertEquals(1_372_961_531L, assertNotNull(taken).epochSeconds)
    }

    /** Parsed as UTC, so the same tag is the same instant on every machine and in every locale. */
    @Test
    fun isIndependentOfTheMachinesZone() {
        val taken = tags("DateTimeOriginal" to text("2013:07:04 18:12:11")).takenAt()
        assertEquals(1_372_961_531L, assertNotNull(taken).epochSeconds)
    }

    @Test
    fun fallsBackThroughTheOtherDateTagsInOrder() {
        assertNotNull(tags("DateTimeDigitized" to text("2013:07:04 18:12:11")).takenAt())
        assertNotNull(tags("DateTime" to text("2013:07:04 18:12:11")).takenAt())
    }

    @Test
    fun prefersDateTimeOriginalWhenSeveralArePresent() {
        val taken = tags(
            "DateTimeOriginal" to text("2013:07:04 18:12:11"),
            "DateTime" to text("2020:01:01 00:00:00"),
        ).takenAt()
        assertEquals(1_372_961_531L, assertNotNull(taken).epochSeconds)
    }

    @Test
    fun rejectsGarbage() {
        // All zeroes is what a camera with a dead clock writes: a default, not a date.
        for (bad in listOf("", "not a date", "0000:00:00 00:00:00", "2013:07:04", "13:7:4 18:12:11")) {
            assertNull(tags("DateTimeOriginal" to text(bad)).takenAt(), "should reject '$bad'")
        }
    }

    @Test
    fun noDateTagAtAllIsNullNotAnError() {
        assertNull(ExifTags().takenAt())
    }
}

class CoordinateTest {

    @Test
    fun turnsDegreesMinutesSecondsIntoSignedDegrees() {
        val c = tags(
            "GPSLatitude" to dms(47.0, 59.0, 44.06),
            "GPSLatitudeRef" to text("N"),
            "GPSLongitude" to dms(12.0, 16.0, 6.68),
            "GPSLongitudeRef" to text("E"),
        ).coordinate()

        assertNotNull(c)
        assertTrue(abs(c.latitude - 47.995572) < 0.0001)
        assertTrue(abs(c.longitude - 12.268522) < 0.0001)
    }

    @Test
    fun southAndWestAreNegative() {
        val c = tags(
            "GPSLatitude" to dms(36.0, 51.0, 0.0),
            "GPSLatitudeRef" to text("S"),
            "GPSLongitude" to dms(174.0, 45.0, 0.0),
            "GPSLongitudeRef" to text("E"),
        ).coordinate()

        assertNotNull(c)
        assertTrue(c.latitude < 0)
        assertTrue(c.longitude > 0)
        assertTrue(abs(c.latitude - -36.85) < 0.001)
    }

    @Test
    fun aLowercaseRefStillCounts() {
        val c = tags(
            "GPSLatitude" to real(10.0), "GPSLatitudeRef" to text("s"),
            "GPSLongitude" to real(20.0), "GPSLongitudeRef" to text("w"),
        ).coordinate()

        assertNotNull(c)
        assertEquals(-10.0, c.latitude)
        assertEquals(-20.0, c.longitude)
    }

    @Test
    fun anAlreadySignedScalarWithNoRefIsTakenAsGiven() {
        val c = tags("GPSLatitude" to real(-36.85), "GPSLongitude" to real(174.76)).coordinate()
        assertNotNull(c)
        assertEquals(-36.85, c.latitude)
        assertEquals(174.76, c.longitude)
    }

    /** Half a coordinate would put the photo on the null island, and its album's centroid too. */
    @Test
    fun halfACoordinateIsNoCoordinate() {
        assertNull(tags("GPSLatitude" to real(47.9)).coordinate())
    }

    @Test
    fun zeroedFieldsAreNotTheGulfOfGuinea() {
        assertNull(tags("GPSLatitude" to real(0.0), "GPSLongitude" to real(0.0)).coordinate())
    }

    @Test
    fun outOfRangeValuesAreRefused() {
        assertNull(tags("GPSLatitude" to real(120.0), "GPSLongitude" to real(12.0)).coordinate())
    }

    @Test
    fun aZeroDenominatorDoesNotProduceANaNPin() {
        val voidRational = ExifValue.Items(listOf(ExifValue.Rational(47.0, 0.0)))
        assertNull(tags("GPSLatitude" to voidRational, "GPSLongitude" to real(12.0)).coordinate())
    }

    /** GPSStatus 'V' is EXIF for "measurement void" — a fix the camera never actually had. */
    @Test
    fun aVoidFixIsRejectedEvenWhenTheNumbersLookPlausible() {
        assertNull(
            tags(
                "GPSStatus" to text("V"),
                "GPSLatitude" to real(47.9),
                "GPSLongitude" to real(12.2),
            ).coordinate(),
        )
    }

    @Test
    fun anActiveFixIsAccepted() {
        assertNotNull(
            tags(
                "GPSStatus" to text("A"),
                "GPSLatitude" to real(47.9),
                "GPSLongitude" to real(12.2),
            ).coordinate(),
        )
    }
}

class DimensionsTest {

    private fun sized(orientation: Long?) = tags(
        *listOfNotNull(
            "PixelXDimension" to int(4000),
            "PixelYDimension" to int(3000),
            orientation?.let { "Orientation" to int(it) },
        ).toTypedArray(),
    )

    @Test
    fun orientationsFiveToEightTranspose() {
        for (orientation in 5L..8L) {
            val size = assertNotNull(sized(orientation).dimensions(), "orientation $orientation")
            assertEquals(Dimensions(3000, 4000), size, "orientation $orientation")
        }
    }

    @Test
    fun orientationsOneToFourLeaveThemAlone() {
        for (orientation in 1L..4L) {
            val size = assertNotNull(sized(orientation).dimensions(), "orientation $orientation")
            assertEquals(Dimensions(4000, 3000), size, "orientation $orientation")
        }
    }

    @Test
    fun aMissingOrientationMeansUpright() {
        assertEquals(Dimensions(4000, 3000), sized(orientation = null).dimensions())
    }

    @Test
    fun theTiffSpellingsAreAcceptedToo() {
        val size = tags("ImageWidth" to int(1920), "ImageLength" to int(1080)).dimensions()
        assertEquals(Dimensions(1920, 1080), size)
    }

    @Test
    fun zeroOrMissingDimensionsAreNullNotZero() {
        assertNull(ExifTags().dimensions())
        assertNull(tags("PixelXDimension" to int(0), "PixelYDimension" to int(0)).dimensions())
    }
}

@OptIn(ExperimentalUuidApi::class)
class ToPhotoRowTest {

    @Test
    fun aFullTagSetBecomesAFullRow() {
        val imageId = blobId()
        val row = tags(
            "DateTimeOriginal" to text("2013:07:04 18:12:11"),
            "GPSLatitude" to real(47.99), "GPSLatitudeRef" to text("N"),
            "GPSLongitude" to real(12.26), "GPSLongitudeRef" to text("E"),
            "PixelXDimension" to int(4000),
            "PixelYDimension" to int(3000),
            "Orientation" to int(6),
        ).toPhotoRow(id = Uuid.random(), filename = "IMG_0042.jpg", bytes = 3_145_728, imageId = imageId)

        assertEquals("IMG_0042.jpg", row.filename)
        assertEquals(1_372_961_531L, assertNotNull(row.takenAt).epochSeconds)
        assertEquals(47.99, row.latitude)
        assertEquals(3000, row.width, "transposed by orientation 6")
        assertEquals(4000, row.height)
        assertEquals(3_145_728L, row.bytes)
        assertEquals(imageId, row.imageId)
    }

    @Test
    fun noTagsAtAllStillProducesAUsableRow() {
        val row = ExifTags().toPhotoRow(id = Uuid.random(), filename = "scan.png", bytes = 4096)

        assertEquals("scan.png", row.filename)
        assertNull(row.takenAt)
        assertNull(row.latitude)
        assertNull(row.width)
        assertEquals(4096L, row.bytes)
        assertEquals(MediaType.PHOTO, row.mediaType)
    }

    /** The column means "this blob is not the file that was ingested", so an equal name says nothing. */
    @Test
    fun aSourceNameEqualToTheZoneNameIsNotStored() {
        val row = ExifTags().toPhotoRow(
            id = Uuid.random(), filename = "IMG_1.jpg", sourceFilename = "IMG_1.jpg", bytes = 1,
        )
        assertNull(row.sourceFilename)
        assertEquals("IMG_1.jpg", row.diskFilename)
    }
}
