@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import net.stho.photos.derivative.DerivativeSpec
import net.stho.photos.faces.DetectedFace
import net.stho.photos.faces.FaceModelFiles
import net.stho.photos.faces.cosine
import net.stho.photos.fixtures.withScratchDirectory
import net.stho.photos.pipeline.MediaItem
import platform.posix.getenv

/**
 * The real detector and embedder (§12), on real faces: public-domain NASA portraits, two each of
 * three astronauts — Mae Jemison in 1987 and 1992, Buzz Aldrin in 1963 and 1969, Michael Collins in
 * 1964 and 1969. Aldrin and Collins are the hard pair: two men of an age, in the same studio.
 *
 * What it proves is the integration rather than the models' quality — the pixels reach dnn the
 * right way round (RGB versus BGR, box coordinates mapped back from the detection scale), and two
 * photographs of one person land closer together than photographs of two. How good the models are
 * on a real library is §12's evaluation, not a unit test.
 */
class FacesTest {

    private val fixtures: List<String> = requireNotNull(getenv("PHOTOS_FACE_FIXTURES")?.toKString()) {
        "PHOTOS_FACE_FIXTURES is not set; run through Gradle"
    }.split(':')

    private fun fixture(name: String): String = fixtures.single { it.substringAfterLast('/').startsWith(name) }

    private val models = FaceModelFiles(
        detector = Path(fixture("face_detection_yunet_2023mar")),
        embedder = Path(fixture("face_recognition_sface_2021dec")),
    )

    /** The portrait's sitter: its most confident face. A helmet visor may reflect another. */
    private fun sitter(faces: CImagingFaces, name: String): DetectedFace {
        val found = PixelImage.decode(fixture(name), DerivativeSpec.IMAGE_LONG_EDGE).use(faces::find)
        assertTrue(found.isNotEmpty(), "no face in $name")
        return found.maxBy { it.score }
    }

    @Test
    fun twoPhotographsOfOnePersonAreCloserThanTwoPeople() {
        CImagingFaces(models).use { faces ->
            val people = listOf(
                listOf("s87-45893", "S92-40463"),
                listOf("s63-20056", "S69-31743"),
                listOf("s64-29926", "S69-31742"),
            ).map { photos -> photos.map { sitter(faces, it) } }

            val same = people.map { (a, b) -> a.embedding.cosine(b.embedding) }
            val different = people.indices.flatMap { i ->
                (i + 1 until people.size).flatMap { j ->
                    people[i].flatMap { a -> people[j].map { b -> a.embedding.cosine(b.embedding) } }
                }
            }

            // SFace's own threshold for "same person" is 0.363.
            for (similarity in same) assertTrue(similarity > 0.363f, "same person at $similarity")
            assertTrue(same.min() > different.max(), "same $same, different $different")
        }
    }

    @Test
    fun boxesAreFractionsOfTheDisplayedPhoto() {
        CImagingFaces(models).use { faces ->
            val face = sitter(faces, "s63-20056")
            val box = face.box
            for (value in listOf(box.x, box.y, box.x + box.width, box.y + box.height)) {
                assertTrue(value in 0f..1f, "box $box")
            }
            // A studio portrait: the face is in the upper half, and not a sliver of the frame.
            assertTrue(box.y + box.height / 2 < 0.6f, "box $box")
            assertTrue(box.width > 0.1f, "box $box")
            // Unit embedding, so similarity is a dot product.
            assertTrue(kotlin.math.abs(face.embedding.cosine(face.embedding) - 1f) < 1e-3f)
        }
    }

    @Test
    fun thePipelineFindsFacesInTheDecodeItDerivesFrom() {
        withScratchDirectory("faces") { work ->
            CImagingFaces(models).use { faces ->
                val pipeline = CImagingPipeline(work.toString(), faces = faces)
                val path = fixture("s64-29926")
                val item = MediaItem(path, MediaItem.Kind.Still, 0)
                val derived = pipeline.derive(item)
                val found = derived.faces!!
                assertTrue(found.isNotEmpty())
                // The backfill decodes again, and has to find the same faces the derive did.
                val backfilled = pipeline.findFaces(item)!!
                assertEquals(found.size, backfilled.size)
                assertTrue(found.maxBy { it.score }.embedding.cosine(backfilled.maxBy { it.score }.embedding) > 0.99f)
            }
        }
    }
}
