package net.stho.photos.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import net.stho.photos.exif.ExifTags
import net.stho.photos.exif.ExifValue
import net.stho.photos.ports.ImageBackend
import net.stho.photos.ports.MediaProbe

/**
 * What the sniffer decided, without a sniffer.
 *
 * The classifier's job is pairing, ordering and reporting; deciding what a file *is* belongs to
 * [MediaProbe], and the real one is exercised in `:adapter:linux`. Keying the stub on the
 * filename keeps these tests about the only thing this type actually judges.
 */
private class StubProbe(
    private val formats: Map<String, MediaFormat>,
    private val identifiers: Map<String, String> = emptyMap(),
) : MediaProbe {

    override fun sniff(path: String): MediaFormat =
        formats[path.substringAfterLast('/')] ?: MediaFormat.UNKNOWN

    override fun videoInfo(path: String): VideoInfo? {
        val name = path.substringAfterLast('/')
        if (formats[name] != MediaFormat.VIDEO) return null
        return VideoInfo(
            width = 64,
            height = 48,
            duration = 1.0,
            rotation = 0,
            hasAudio = false,
            isInterlaced = false,
            contentIdentifier = identifiers[name],
        )
    }
}

/**
 * Returns the identifier the classifier should find for a given HEIC, so pairing can be
 * exercised without building an Apple maker note by hand.
 */
private class StubBackend(private val identifiers: Map<String, String> = emptyMap()) : ImageBackend {
    override fun rawTags(path: String): ExifTags {
        val identifier = identifiers[path.substringAfterLast('/')] ?: return ExifTags()
        return ExifTags(mapOf("AppleContentIdentifier" to ExifValue.Text(identifier)))
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun <R> withFiles(vararg names: String, body: (Map<String, String>) -> R): R {
    val directory = Path(SystemTemporaryDirectory, "photos-classify-${Uuid.random()}")
    SystemFileSystem.createDirectories(directory)
    try {
        return body(
            names.associateWith { name ->
                val path = Path(directory, name)
                SystemFileSystem.sink(path).buffered().use { it.write("some bytes".encodeToByteArray()) }
                path.toString()
            },
        )
    } finally {
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it, mustExist = false) }
        SystemFileSystem.delete(directory, mustExist = false)
    }
}

/** Classification and Live Photo pairing. */
class MediaClassifierTest {

    @Test
    fun anUnrecognisedFileIsReportedNotDecodedAndNotFatal() =
        withFiles("mystery.xyz") { paths ->
            // Nothing the sniffer recognises. The denylist degrades in this direction on
            // purpose: content decides, and content that decides nothing is reported.
            val result = MediaClassifier(StubProbe(emptyMap()), StubBackend()).classify(paths.values.toList())
            assertTrue(result.items.isEmpty())
            assertEquals(SkippedFile.Reason.UnrecognisedFormat, result.skipped.single().reason)
        }

    @Test
    fun aFileThatCannotBeReadIsReportedRatherThanAbandoningTheRun() {
        val result = MediaClassifier(StubProbe(emptyMap()), StubBackend())
            .classify(listOf(Path(SystemTemporaryDirectory, "photos-not-here").toString()))
        assertTrue(result.items.isEmpty())
        assertIs<SkippedFile.Reason.Unreadable>(result.skipped.single().reason)
    }

    @Test
    fun aCr2IsClassifiedAsRawNeverAsADecodableStill() = withFiles("IMG_7353.CR2") { paths ->
        val probe = StubProbe(mapOf("IMG_7353.CR2" to MediaFormat.CR2))
        val result = MediaClassifier(probe, StubBackend()).classify(paths.values.toList())
        assertEquals(MediaItem.Kind.Raw, result.items.single().kind)
    }

    @Test
    fun aHeicAndAMovSharingAContentIdentifierBecomeOneLivePhoto() =
        withFiles("IMG_0679.HEIC", "IMG_0679.mov") { paths ->
            // Decision 14: pair on content.identifier, not on filenames.
            val identifier = "B34B6B99-C28F-4E16-A788-79AA0E30BB18"
            val probe = StubProbe(
                formats = mapOf("IMG_0679.HEIC" to MediaFormat.HEIF, "IMG_0679.mov" to MediaFormat.VIDEO),
                identifiers = mapOf("IMG_0679.mov" to identifier),
            )
            val backend = StubBackend(mapOf("IMG_0679.HEIC" to identifier))

            val result = MediaClassifier(probe, backend).classify(paths.values.toList())

            assertEquals(1, result.items.size)
            val kind = assertIs<MediaItem.Kind.LivePhoto>(result.items.single().kind)
            assertEquals(paths.getValue("IMG_0679.mov"), kind.video)
            // The MOV is not a separate item; it is uploaded as part of the Live Photo.
            assertEquals(SkippedFile.Reason.ConsumedAsLivePhotoVideo, result.skipped.single().reason)
        }

    @Test
    fun aMatchingFilenameDoesNotPairWithoutAMatchingIdentifier() =
        withFiles("IMG_1234.HEIC", "IMG_1234.mov") { paths ->
            // The case that makes decision 14 worth its cost: an unrelated video that happens to
            // share a basename must stay a video, not be swallowed into a Live Photo. Both
            // signals agree on every one of this library's 312 MOVs, but a filename match is a
            // coincidence that holds until someone drops an unrelated IMG_1234.MOV beside an
            // IMG_1234.HEIC.
            val probe = StubProbe(
                mapOf("IMG_1234.HEIC" to MediaFormat.HEIF, "IMG_1234.mov" to MediaFormat.VIDEO),
            )
            val result = MediaClassifier(probe, StubBackend()).classify(paths.values.toList())
            assertEquals(2, result.items.size)
            assertTrue(result.items.none { it.kind is MediaItem.Kind.LivePhoto })
        }

    @Test
    fun classificationIsStableAcrossRuns() = withFiles("c.jpg", "a.jpg", "b.jpg") { paths ->
        val probe = StubProbe(paths.keys.associateWith { MediaFormat.JPEG })
        val classifier = MediaClassifier(probe, StubBackend())

        val first = classifier.classify(paths.values.toList())
        val second = classifier.classify(paths.values.reversed())

        assertEquals(first.items.map(MediaItem::filename), second.items.map(MediaItem::filename))
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), first.items.map(MediaItem::filename))
    }
}

/** The name a derivative claims in the zone (§3). */
class WithExtensionTest {

    @Test
    fun replacesTheExtensionItFinds() {
        assertEquals("IMG_1234.jpg", "IMG_1234.CR2".withExtension("jpg"))
        assertEquals("VID_0001.mp4", "VID_0001.MOV".withExtension("mp4"))
    }

    @Test
    fun anExtensionlessNameSimplyGainsOne() {
        assertEquals("IMG_1234.jpg", "IMG_1234".withExtension("jpg"))
        // A leading dot is a hidden file, not an extension.
        assertEquals(".hidden.jpg", ".hidden".withExtension("jpg"))
    }
}
