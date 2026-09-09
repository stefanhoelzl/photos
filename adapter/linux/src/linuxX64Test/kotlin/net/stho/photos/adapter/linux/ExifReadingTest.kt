package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import net.stho.photos.exif.asLong
import net.stho.photos.exif.asText
import net.stho.photos.fixtures.syntheticHeic
import net.stho.photos.fixtures.syntheticOrientedJpeg
import net.stho.photos.fixtures.withScratchDirectory
import net.stho.photos.fixtures.write
import net.stho.photos.fixtures.writeSyntheticHeic

/**
 * Reading EXIF back out of the containers the library actually holds.
 *
 * These exist because of a bug the rest of the suite could not have caught: `pi_encode_heic`
 * writes pixels and a colour profile but no metadata, so every synthetic HEIC was
 * metadata-free — and a HEIF reader that returned *zero tags for every file* passed everything.
 * In the real library that silently cost 1,531 photos their date and GPS, and all 187 Live
 * Photos their pairing.
 */
class ExifReadingTest {

    @Test
    fun tagsAreReadBackOutOfAHeic() = withScratchDirectory("exif") { directory ->
        val path = Path(directory, "live.heic")
        writeSyntheticHeic(path, width = 64, height = 48, model = "iPhone XS")

        val tags = CImagingBackend().rawTags(path.toString())
        assertTrue(tags.values.isNotEmpty(), "a HEIC with an EXIF block must yield tags")
        assertEquals("iPhone XS", tags["Model"]?.asText)
    }

    @Test
    fun tagsAreReadBackOutOfAJpeg() = withScratchDirectory("exif") { directory ->
        val path = Path(directory, "photo.jpg").write(syntheticOrientedJpeg(64, 48, orientation = 6))
        assertEquals(6L, CImagingBackend().rawTags(path.toString())["Orientation"]?.asLong)
    }

    @Test
    fun aHeicWithNoExifYieldsNoTagsRatherThanFailing() = withScratchDirectory("exif") { directory ->
        val path = Path(directory, "bare.heic").write(syntheticHeic(32, 32))
        assertTrue(CImagingBackend().rawTags(path.toString()).values.isEmpty())
    }
}
