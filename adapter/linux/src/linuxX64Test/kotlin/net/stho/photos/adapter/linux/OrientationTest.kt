@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.set

/**
 * §3 promises that stored dimensions are already rotated and that no consumer applies
 * orientation. libjpeg does not rotate on its own, so that promise is only true because the
 * pipeline makes it true — and nothing proved it until these existed.
 */
class OrientationTest {

    @Test
    fun aTransposingOrientationSwapsTheDecodedDimensions() {
        for (orientation in 5..8) {
            PixelImage.decodeJpeg(syntheticOrientedJpeg(400, 300, orientation), maxLongEdge = 0).use {
                assertEquals(300, it.width, "orientation $orientation should transpose")
                assertEquals(400, it.height)
                assertEquals(300, it.sourceWidth)
                assertEquals(400, it.sourceHeight)
            }
        }
    }

    @Test
    fun aNonTransposingOrientationLeavesDimensionsAlone() {
        for (orientation in 1..4) {
            PixelImage.decodeJpeg(syntheticOrientedJpeg(400, 300, orientation), maxLongEdge = 0).use {
                assertEquals(400, it.width, "orientation $orientation should not transpose")
                assertEquals(300, it.height)
            }
        }
    }

    @Test
    fun orientationIsAppliedToThePixelsNotMerelyToTheGeometry() {
        // Orientation 6 is "rotate 90 clockwise for display", so the source's top-left corner
        // must end up in the top-right. Checking geometry alone would pass even if the buffer
        // were rotated the wrong way — which is exactly how the video path's sign bug hid.
        val marked = syntheticImage(64, 32).use { image ->
            val pixels = image.pixels!!
            for (y in 0 until 4) {
                for (x in 0 until 4) {
                    val i = (y * 64 + x) * 3
                    pixels[i] = 255u
                    pixels[i + 1] = 0u
                    pixels[i + 2] = 0u
                }
            }
            image.encodedJpeg(quality = 95)
        }

        PixelImage.decodeJpeg(marked.withApp1(exifApp1(orientation = 6)), maxLongEdge = 0).use { decoded ->
            assertEquals(32, decoded.width)
            assertEquals(64, decoded.height)
            val (r, g, b) = decoded.rgbAt(30, 1)
            assertTrue(
                r > 150 && g < 110 && b < 110,
                "orientation 6 should carry the top-left corner to the top-right",
            )
        }
    }

    @Test
    fun shrinkOnLoadStillReportsThePhotographsDimensions() {
        // The bug this caught: row.width was reporting the decoder's buffer (2250) rather than
        // the photo (3000), because libjpeg had scaled by 6/8 on the way in.
        PixelImage.decodeJpeg(syntheticJpeg(3000, 2000), maxLongEdge = 2048).use {
            assertTrue(it.width < 3000, "shrink-on-load should have engaged")
            assertEquals(3000, it.sourceWidth)
            assertEquals(2000, it.sourceHeight)
        }
    }

    @Test
    fun sourceDimensionsSurviveResizingAndCropping() {
        PixelImage.decodeJpeg(syntheticJpeg(3000, 2000), maxLongEdge = 2048).use { decoded ->
            decoded.resizedFitting(2048).use { preview ->
                assertEquals(3000, preview.sourceWidth)
                assertEquals(2000, preview.sourceHeight)
            }
            decoded.squareCropped(256).use { thumbnail ->
                assertEquals(3000, thumbnail.sourceWidth)
                assertEquals(2000, thumbnail.sourceHeight)
            }
        }
    }
}
