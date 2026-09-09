package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import net.stho.photos.exif.Dimensions
import net.stho.photos.exif.asText
import net.stho.photos.fixtures.syntheticJpeg
import net.stho.photos.fixtures.withScratchDirectory
import net.stho.photos.fixtures.wrappedInCr2
import net.stho.photos.fixtures.write
import net.stho.photos.pipeline.MediaItem

/** Carving the embedded JPEG out of a CR2 — decision 2. */
class Cr2Test {

    @Test
    fun theEmbeddedJpegComesBackByteForByte() {
        // The whole premise of decision 2: this is a copy, not a re-encode. If the carved bytes
        // ever differed from the source stream, we would be paying for a decode we decided not
        // to do.
        val jpeg = syntheticJpeg(800, 533)
        val extraction = jpeg.wrappedInCr2(800, 533).carveEmbeddedJpeg()

        // The graft prepends an APP1, so the tail after the SOI must match exactly.
        assertTrue(extraction.jpeg.size >= jpeg.size)
        assertContentEquals(
            jpeg.copyOfRange(2, jpeg.size),
            extraction.jpeg.copyOfRange(extraction.jpeg.size - (jpeg.size - 2), extraction.jpeg.size),
        )
    }

    @Test
    fun declaredDimensionsAreRecoveredFromIfd0() {
        val jpeg = syntheticJpeg(640, 480)
        val extraction = jpeg.wrappedInCr2(5184, 3456).carveEmbeddedJpeg()
        // The tags say 5184x3456 even though this fixture's stream is smaller; the carver must
        // report what IFD0 declared rather than re-deriving it.
        assertEquals(5184, extraction.width)
        assertEquals(3456, extraction.height)
    }

    @Test
    fun theCarvedJpegIsDecodableAtFullResolution() {
        val jpeg = syntheticJpeg(1024, 768)
        val extraction = jpeg.wrappedInCr2(1024, 768).carveEmbeddedJpeg()
        PixelImage.decodeJpeg(extraction.jpeg, maxLongEdge = 0).use {
            assertEquals(1024, it.width)
            assertEquals(768, it.height)
        }
    }

    @Test
    fun aFileThatIsNotATiffIsRejectedRatherThanGuessedAt() {
        assertFailsWith<Cr2Exception> { ByteArray(64).carveEmbeddedJpeg() }
        assertFailsWith<Cr2Exception> { syntheticJpeg(32, 32).carveEmbeddedJpeg() }
    }

    @Test
    fun aTiffWithNoOldStyleJpegIfdIsRejected() {
        // A bare, valid little-endian TIFF header with an empty IFD.
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00) +
            byteArrayOf(0x00, 0x00) + // zero entries
            byteArrayOf(0x00, 0x00, 0x00, 0x00) // no next IFD
        assertFailsWith<Cr2Exception> { tiff.carveEmbeddedJpeg() }
    }

    @Test
    fun aTruncatedFileIsRefusedNotReadPastItsEnd() {
        val full = syntheticJpeg(256, 256).wrappedInCr2(256, 256)
        // Chop the stream in half: the IFD still claims the original byte count, so the carver
        // is being asked to read past the end of the buffer.
        val truncated = full.copyOfRange(0, full.size / 2)
        assertEquals(
            Cr2Exception.Reason.TRUNCATED,
            assertFailsWith<Cr2Exception> { truncated.carveEmbeddedJpeg() }.reason,
        )
    }

    @Test
    fun aCarvedOriginalIsWhatThePipelineUploadsForARaw() = withScratchDirectory("cr2") { directory ->
        val path = Path(directory, "IMG_7353.CR2")
            .write(syntheticJpeg(900, 600).wrappedInCr2(900, 600))

        val derived = CImagingPipeline(workDirectory = directory.toString())
            .derive(MediaItem(path.toString(), MediaItem.Kind.Raw, byteCount = 0))

        // §3: the row describes what is in the zone, which for a CR2 is a HEIC derived from the
        // JPEG carved out of it — never the RAW, which stays on the laptop.
        assertTrue(derived.image.isNotEmpty(), "a raw should still produce a viewing image")
        assertEquals(Dimensions(256, 256), derived.thumbnail.imageDimensions())
        assertEquals("IMG_7353.heic", derived.row.filename)
        assertEquals("IMG_7353.CR2", derived.row.sourceFilename)
    }
}

/** The EXIF graft — decision 9's "the extracted JPEG should look like every other original". */
class Cr2GraftTest {

    @Test
    fun aCarvedJpegCarriesTheCr2sOwnExif() = withScratchDirectory("graft") { directory ->
        // Without a tag in IFD0 there is nothing to graft, so the graft path is silently
        // untested — which is exactly what happened until this fixture grew a Model.
        val jpeg = syntheticJpeg(320, 240)
        val extraction = jpeg.wrappedInCr2(320, 240, model = "Canon EOS 100D").carveEmbeddedJpeg()

        // The graft prepends an APP1, so the carved file is larger than the raw stream.
        assertTrue(extraction.jpeg.size > jpeg.size)
        assertEquals(0xFF.toByte(), extraction.jpeg[2])
        assertEquals(0xE1.toByte(), extraction.jpeg[3], "APP1 should follow SOI")

        val path = Path(directory, "carved.jpg").write(extraction.jpeg)
        assertEquals("Canon EOS 100D", CImagingBackend().rawTags(path.toString())["Model"]?.asText)

        // And it must still be a decodable JPEG afterwards.
        assertEquals(Dimensions(320, 240), imageDimensions(path.toString()))
    }

    @Test
    fun aCr2WithNoTaggableMetadataStillYieldsAUsableJpeg() {
        // The graft is best-effort: the catalog carries date and coordinates regardless, so a
        // CR2 with nothing to graft must produce a working original rather than an error.
        val extraction = syntheticJpeg(200, 150).wrappedInCr2(200, 150).carveEmbeddedJpeg()
        assertEquals(Dimensions(200, 150), extraction.jpeg.imageDimensions())
    }
}
