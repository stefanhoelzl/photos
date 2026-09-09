package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import net.stho.photos.exif.ExifTags
import net.stho.photos.exif.ExifValue
import net.stho.photos.fixtures.syntheticHeic
import net.stho.photos.fixtures.syntheticJpeg
import net.stho.photos.fixtures.withScratchDirectory
import net.stho.photos.fixtures.wrappedInCr2
import net.stho.photos.fixtures.write
import net.stho.photos.fixtures.writeSyntheticVideo
import net.stho.photos.pipeline.MediaClassifier
import net.stho.photos.pipeline.MediaItem
import net.stho.photos.pipeline.SkippedFile
import net.stho.photos.ports.ImageBackend

/**
 * Classification over files the native stack actually sniffed.
 *
 * The classifier's own logic is covered in `:domain` against a stub probe; what needs the
 * native stack is the other half of the bargain — that a real CR2 sniffs as a CR2, that junk
 * sniffs as nothing, and that a real MOV's `content.identifier` is the string decision 14 pairs
 * on. The identifier of the *still* still comes from a stub, because it lives in an Apple maker
 * note no fixture writer can produce.
 */
class ClassifierNativeTest {

    /** Returns the identifier the classifier should find for a given HEIC. */
    private class StubBackend(private val identifiers: Map<String, String>) : ImageBackend {
        override fun rawTags(path: String): ExifTags {
            val identifier = identifiers[path.substringAfterLast('/')] ?: return ExifTags()
            return ExifTags(mapOf("AppleContentIdentifier" to ExifValue.Text(identifier)))
        }
    }

    private fun classifier(identifiers: Map<String, String> = emptyMap()) =
        MediaClassifier(CImagingProbe(), StubBackend(identifiers))

    @Test
    fun junkReachingTheClassifierIsSkippedAsUnrecognised() = withScratchDirectory("classify") { directory ->
        // The classifier no longer knows what junk is — `.photosignore` and the walker handle
        // that. This is the property that made moving exclusions out safe: the denylist only
        // ever quieted the report, because every one of these sniffs as nothing.
        val junk = listOf(
            "digikam4.db", "thumbnails-digikam.db", "THUMB~JT.DBE",
            "hochzeit50.doc", "preisliste.pdf", "akaroa_III.psd", ".hidden",
        ).map { Path(directory, it).write("not a photo".encodeToByteArray()).toString() }
        val photo = Path(directory, "real.jpg").write(syntheticJpeg(64, 64)).toString()

        val result = classifier().classify(junk + photo)

        assertEquals(1, result.items.size)
        assertEquals(photo, result.items.first().path)
        assertEquals(junk.size, result.skipped.size)
        assertTrue(result.skipped.all { it.reason == SkippedFile.Reason.UnrecognisedFormat })
    }

    @Test
    fun aNewMediaFormatIsPickedUpAutomatically() = withScratchDirectory("classify") { directory ->
        // The payoff of a denylist over an allowlist: content decides, so a file whose extension
        // nobody enumerated is still ingested if it sniffs as media.
        val path = Path(directory, "photo.unknownext").write(syntheticJpeg(32, 32)).toString()

        val result = classifier().classify(listOf(path))
        assertEquals(1, result.items.size)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun aCr2IsClassifiedAsRawNeverAsADecodableStill() = withScratchDirectory("classify") { directory ->
        val path = Path(directory, "IMG_7353.CR2")
            .write(syntheticJpeg(128, 96).wrappedInCr2(128, 96)).toString()

        assertEquals(MediaItem.Kind.Raw, classifier().classify(listOf(path)).items.first().kind)
    }

    @Test
    fun aHeicAndAMovSharingAContentIdentifierBecomeOneLivePhoto() =
        withScratchDirectory("classify") { directory ->
            val identifier = "B34B6B99-C28F-4E16-A788-79AA0E30BB18"
            val still = Path(directory, "IMG_0679.HEIC").write(syntheticHeic(64, 48)).toString()
            val movie = Path(directory, "IMG_0679.mov")
            writeSyntheticVideo(movie, width = 64, height = 48, frames = 4, contentIdentifier = identifier)

            val result = classifier(mapOf("IMG_0679.HEIC" to identifier))
                .classify(listOf(still, movie.toString()))

            assertEquals(1, result.items.size)
            val kind = assertIs<MediaItem.Kind.LivePhoto>(result.items.first().kind)
            assertEquals(movie.toString(), kind.video)
            // The MOV is not a separate item; it is uploaded as part of the Live Photo.
            assertTrue(result.skipped.any { it.reason == SkippedFile.Reason.ConsumedAsLivePhotoVideo })
        }

    @Test
    fun aMatchingFilenameDoesNotPairWithoutAMatchingIdentifier() =
        withScratchDirectory("classify") { directory ->
            // The case that makes decision 14 worth its cost: an unrelated video that happens to
            // share a basename must stay a video, not be swallowed into a Live Photo.
            val still = Path(directory, "IMG_1234.HEIC").write(syntheticHeic(64, 48)).toString()
            val movie = Path(directory, "IMG_1234.mov")
            writeSyntheticVideo(movie, width = 64, height = 48, frames = 4) // no identifier

            val result = classifier().classify(listOf(still, movie.toString()))
            assertEquals(2, result.items.size)
            assertTrue(result.items.none { it.kind is MediaItem.Kind.LivePhoto })
        }
}
