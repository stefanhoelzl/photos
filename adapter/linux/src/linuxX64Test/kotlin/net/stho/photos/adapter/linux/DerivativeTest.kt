@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.io.files.Path
import net.stho.photos.derivative.DerivativeSpec
import net.stho.photos.exif.Dimensions
import net.stho.photos.fixtures.readBytes
import net.stho.photos.fixtures.syntheticJpeg
import net.stho.photos.fixtures.withScratchDirectory
import net.stho.photos.fixtures.writeSyntheticVideo
import net.stho.photos.fixtures.write
import net.stho.photos.model.MediaType
import net.stho.photos.pipeline.MediaItem

/** Every pixel this image holds, for tests that compare two buffers. */
internal fun PixelImage.pixelBytes(): ByteArray =
    pixels?.readBytes(width * height * channels) ?: ByteArray(0)

/** One pixel's RGB, as unsigned values. */
internal fun PixelImage.rgbAt(x: Int, y: Int): Triple<Int, Int, Int> {
    val base = (y * width + x) * channels
    val buffer = pixels!!
    return Triple(buffer[base].toInt(), buffer[base + 1].toInt(), buffer[base + 2].toInt())
}

/**
 * What the pipeline produces, checked against the decisions that fixed it.
 *
 * Every input is generated (decision 16). The real library is personal data, and a committed
 * corpus is a set of files that quietly stops representing anything.
 */
class ThumbnailTest {

    @Test
    fun thumbnailsAreASquareCropAtTheSpecsEdge() {
        // Landscape, portrait and square sources all have to arrive at the same tile: every
        // consumer in the app is a square cover-crop box.
        for ((width, height) in listOf(1600 to 1067, 1067 to 1600, 800 to 800, 4000 to 900)) {
            syntheticImage(width, height).use { image ->
                image.squareCropped(DerivativeSpec.THUMBNAIL_EDGE).use { thumbnail ->
                    assertEquals(DerivativeSpec.THUMBNAIL_EDGE, thumbnail.width)
                    assertEquals(DerivativeSpec.THUMBNAIL_EDGE, thumbnail.height)
                }
            }
        }
    }

    @Test
    fun aSquareCropTakesTheCentreNotACorner() {
        // A wide image whose centre column is a distinct colour: after cropping the centre must
        // survive and the edges must not. Cropping from the origin would keep the left edge
        // instead, which no geometry assertion on its own would notice.
        syntheticImage(900, 300).use { image ->
            val pixels = image.pixels!!
            for (y in 0 until 300) {
                for (x in 420 until 480) {
                    val i = (y * 900 + x) * 3
                    pixels[i] = 255u
                    pixels[i + 1] = 0u
                    pixels[i + 2] = 0u
                }
            }
            image.squareCropped(64).use { thumbnail ->
                val (r, g, b) = thumbnail.rgbAt(32, 32)
                assertTrue(r > 180 && g < 90 && b < 90, "the centre stripe should survive a centre crop")
            }
        }
    }
}

class PreviewTest {

    @Test
    fun viewingImagesKeepAspectAndAreNeverUpscaled() {
        // Below the cap: must come back untouched. 17.3% of the library is already at or below
        // 3200px, and inventing pixels for those would only inflate the tier.
        syntheticImage(800, 600).use { small ->
            small.resizedFitting(DerivativeSpec.IMAGE_LONG_EDGE, allowUpscale = false).use {
                assertEquals(800, it.width)
                assertEquals(600, it.height)
            }
        }

        // Above the tier: scaled to the long edge, aspect preserved.
        syntheticImage(4096, 2048).use { large ->
            large.resizedFitting(DerivativeSpec.IMAGE_LONG_EDGE, allowUpscale = false).use {
                assertEquals(DerivativeSpec.IMAGE_LONG_EDGE, it.width)
                assertEquals(DerivativeSpec.IMAGE_LONG_EDGE / 2, it.height)
            }
        }
    }

    @Test
    fun aPortraitSourceScalesOnItsLongEdgeNotItsWidth() {
        syntheticImage(2000, 4000).use { portrait ->
            portrait.resizedFitting(1000, allowUpscale = false).use {
                assertEquals(1000, it.height)
                assertEquals(500, it.width)
            }
        }
    }
}

class DecodeMemoryTest {

    @Test
    fun jpegDecodeIsBoundedByTheRequestedTierNotTheSourceSize() {
        // This is the property that keeps the library's 164 MP panorama from needing 494 MB per
        // worker. libjpeg decodes from the DCT coefficients at N/8, so asking for a 256px tier
        // from a large source must come back far smaller than the source — and still at least as
        // large as the tier, or the resize afterwards would be an upscale.
        val big = syntheticJpeg(4000, 3000)
        PixelImage.decodeJpeg(big, maxLongEdge = 512).use {
            assertTrue(it.width < 4000, "shrink-on-load should not decode at full size")
            assertTrue(it.width >= 512, "must still cover the requested tier")
            assertTrue(it.width * 8 >= 4000, "scale denominators are eighths")
        }

        // Asking for the full size must genuinely give the full size.
        PixelImage.decodeJpeg(big, maxLongEdge = 0).use {
            assertEquals(4000, it.width)
            assertEquals(3000, it.height)
        }
    }
}

class ColorTest {

    @Test
    fun alphaIsFlattenedOntoWhiteNotBlack() {
        // A fully transparent pixel must become the background. White rather than black because
        // the grid is #1a1a1a and a transparent image flattened onto black disappears into its
        // own tile.
        syntheticImage(8, 8, alpha = true).use { image ->
            val pixels = image.pixels!!
            for (i in 0 until 8 * 8) pixels[i * 4 + 3] = 0u
            image.flattenAlpha(DerivativeSpec.ALPHA_BACKGROUND)
            assertEquals(3, image.channels)
            assertEquals(Triple(255, 255, 255), image.rgbAt(0, 0))
        }
    }

    @Test
    fun anOpaquePixelSurvivesFlatteningUnchanged() {
        syntheticImage(4, 4, alpha = true).use { image ->
            val pixels = image.pixels!!
            pixels[0] = 10u
            pixels[1] = 20u
            pixels[2] = 30u
            pixels[3] = 255u
            image.flattenAlpha(DerivativeSpec.ALPHA_BACKGROUND)
            // Rounded compositing, so a fully opaque pixel is exactly itself rather than one
            // unit off from integer truncation.
            assertEquals(Triple(10, 20, 30), image.rgbAt(0, 0))
        }
    }

    @Test
    fun convertingToSrgbWithNoProfileIsANoOp() {
        // 56% of the library's JPEGs carry no profile, and untagged means sRGB everywhere.
        syntheticImage(16, 16).use { image ->
            val before = image.pixelBytes()
            image.convertToSrgb()
            assertContentEquals(before, image.pixelBytes())
            assertFalse(image.hasProfile)
        }
    }
}

class EncodingTest {

    @Test
    fun optimizedHuffmanTablesProduceASmallerJpeg() {
        // The 4% that makes the recorded 8.7 KB/thumbnail reproduce.
        syntheticImage(256, 256).use { image ->
            val plain = image.encodedJpeg(quality = 75, optimize = false)
            val optimized = image.encodedJpeg(quality = 75, optimize = true)
            assertTrue(optimized.size < plain.size)
        }
    }

    @Test
    fun encodedOutputRoundTripsAtTheGeometryItWasGiven() {
        syntheticImage(200, 120).use { image ->
            assertEquals(Dimensions(200, 120), image.encodedJpeg(quality = 75).imageDimensions())
            assertEquals(Dimensions(200, 120), image.encodedHeic(quality = 60).imageDimensions())
        }
    }

    @Test
    fun higherHeicQualityYieldsALargerFile() {
        syntheticImage(512, 384).use { image ->
            assertTrue(image.encodedHeic(quality = 85).size > image.encodedHeic(quality = 40).size)
        }
    }
}

class PipelineTest {

    @Test
    fun aStillYieldsAThumbnailAndAViewingImage() = withScratchDirectory("still") { directory ->
        // Above the cap, so the ceiling is actually exercised rather than passed through.
        val path = Path(directory, "photo.jpg").write(syntheticJpeg(4000, 2000))

        val derived = CImagingPipeline(workDirectory = directory.toString())
            .derive(MediaItem(path.toString(), MediaItem.Kind.Still, byteCount = 123))

        assertEquals(Dimensions(256, 256), derived.thumbnail.imageDimensions())
        val image = derived.image.imageDimensions()
        assertEquals(DerivativeSpec.IMAGE_LONG_EDGE, maxOf(image.width, image.height))

        // Dimensions come from the decoded pixels, not from EXIF, because EXIF can be absent or
        // can describe an embedded thumbnail instead of the photograph -- and they describe the
        // photograph, not the capped image, so they survive the cap changing.
        assertEquals(4000, derived.row.width)
        assertEquals(2000, derived.row.height)
        // `bytes` is the derived image; `sourceBytes` is the file the walk measured (§7).
        assertEquals(derived.image.size.toLong(), derived.row.bytes)
        assertEquals(123L, derived.row.sourceBytes)
        assertEquals(MediaType.PHOTO, derived.row.mediaType)
        assertNull(derived.video)
        assertNull(derived.liveStill)
    }

    @Test
    fun aVideoKeepsItsSoundtrack() = withScratchDirectory("sound") { directory ->
        // Every transcode once came out silent: the audio graph needed a filter the ffmpeg build
        // left out, and the transcoder fell back to video only without saying so.
        val source = Path(directory, "clip.mov")
        writeSyntheticVideo(source, width = 64, height = 48, frames = 25, audio = true)
        assertTrue(probeVideo(source.toString()).hasAudio, "the fixture carries a soundtrack")

        val derived = CImagingPipeline(workDirectory = directory.toString())
            .derive(MediaItem(source.toString(), MediaItem.Kind.Video, byteCount = 0))

        assertTrue(probeVideo(requireNotNull(derived.video)).hasAudio, "and so does its transcode")
    }

    @Test
    fun anUndecodableFileThrowsRatherThanPoisoningTheRun(): Unit = withScratchDirectory("bad") { directory ->
        // A valid JPEG header followed by nothing usable: sniffing succeeds, decoding must not.
        // Decision 15 makes this per-file and recoverable.
        val path = Path(directory, "truncated.jpg")
            .write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) + ByteArray(64))

        assertFailsWith<ImagingException> {
            CImagingPipeline(workDirectory = directory.toString())
                .derive(MediaItem(path.toString(), MediaItem.Kind.Still, byteCount = 0))
        }
    }
}
