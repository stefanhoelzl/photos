package net.stho.photos.adapter.linux

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Panama binding, against the real shared object.
 *
 * What is actually under test is the boundary rather than libjpeg: the `pi_image` struct layout,
 * the pixel copy out of C-owned memory, and the failure path. Get the layout wrong by four
 * bytes and this reads a plausible-looking width from the wrong field, which is exactly the
 * class of bug a binding fails at silently.
 */
class FfmImagingTest {

    private val library = System.getProperty("photos.decode.library")
        ?: error("photos.decode.library not set: the build must point at libphotosdecode.so")

    @Test
    fun decodesAJpegToTheGeometryItWasWritten() {
        FfmImaging(library).use { imaging ->
            val decoded = assertNotNull(imaging.decode(jpeg(64, 48, Color(20, 140, 90))))

            assertEquals(64, decoded.width)
            assertEquals(48, decoded.height)
            assertTrue(decoded.channels == 3 || decoded.channels == 4, "got ${decoded.channels}")
            assertEquals(64 * 48 * decoded.channels, decoded.pixels.size)
        }
    }

    /** The pixels are the photograph's, not whatever happened to be at that address. */
    @Test
    fun theBytesAreTheColourThatWasEncoded() {
        FfmImaging(library).use { imaging ->
            val decoded = assertNotNull(imaging.decode(jpeg(32, 32, Color(200, 40, 60))))
            val red = decoded.pixels[0].toInt() and 0xFF
            val green = decoded.pixels[1].toInt() and 0xFF
            val blue = decoded.pixels[2].toInt() and 0xFF

            // JPEG is lossy, so this is "the right colour" rather than "the same bytes".
            assertTrue(red > 150, "red was $red")
            assertTrue(green < 90, "green was $green")
            assertTrue(blue < 110, "blue was $blue")
        }
    }

    /** A preview that will not decode is a missing picture, never a failed run. */
    @Test
    fun rubbishDecodesToNothingRatherThanThrowing() {
        FfmImaging(library).use { imaging ->
            assertNull(imaging.decode(ByteArray(64) { it.toByte() }))
        }
    }

    private fun jpeg(width: Int, height: Int, colour: Color): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { paint = colour; fillRect(0, 0, width, height); dispose() }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "jpg", out)
            out.toByteArray()
        }
    }
}
