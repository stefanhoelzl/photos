package net.stho.photos.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import net.stho.photos.adapter.linux.NativeSqlDrivers
import net.stho.photos.faces.FaceBox
import net.stho.photos.faces.FaceRef
import net.stho.photos.faces.Labels
import net.stho.photos.faces.PeopleIndex
import net.stho.photos.faces.VerdictKind
import net.stho.photos.ingest.ExitCode
import net.stho.photos.ingest.FacePhase

/**
 * §12 end to end, through the shipped binary: faces found at sync, a confirmation made the way
 * the viewer makes one, and the next sync's suggestion — on real NASA portraits, since a synthetic
 * image has no face for a detector to find.
 *
 * Buzz Aldrin in 1963 and 1969, and Michael Collins in 1964: one person twice, and a man of the
 * same age in the same studio as the one who must not be suggested.
 */
class FacesTest {

    private val drivers = NativeSqlDrivers()

    @Test
    fun aConfirmedFaceIsSuggestedInTheNextPhotographOfThatPerson() = scenario("faces") {
        library {
            photosignore()
            album("Crew") {
                portrait("aldrin-1963.jpg", "s63-20056")
                portrait("aldrin-1969.jpg", "S69-31743")
                portrait("collins-1964.jpg", "s64-29926")
            }
        }
        zone { empty() }

        run("sync")
        expect { exit(ExitCode.CLEAN) }

        val index = PeopleIndex(Path(cacheRoot, PeopleIndex.FILENAME), drivers)
        val crew = s3.readZone(scratch).albums.single()
        val album = crew.info.id
        assertNotNull(s3.head(FacePhase.facesKey(album)), "faces/ holds the album's faces")
        assertNull(s3.head(FacePhase.PEOPLE_KEY), "no labels yet, so nothing to upload")

        val photos = crew.photos.associate { (it.sourceFilename ?: it.filename) to it.id }
        val first = index.read()
        // At least the three sitters; nobody is anybody yet.
        assertTrue(first.size >= 3, "faces: $first")
        assertTrue(first.none { it.personId != null })

        // What the viewer does: a person, and a confirmation of the 1963 face as them.
        fun sitter(file: String) = index.read().filter { it.photoId == photos.getValue(file) }.maxBy { it.score }
        val labels = Labels.at(libraryRoot, drivers)
        val buzz = labels.createPerson("Buzz")
        labels.confirm(listOf(sitter("aldrin-1963.jpg").let { FaceRef(it.photoId, it.box) }), buzz.id)

        run("sync")
        expect { exit(ExitCode.CLEAN) }

        assertNotNull(s3.head(FacePhase.PEOPLE_KEY), "the labels reach the zone")
        assertEquals(VerdictKind.CONFIRMED, sitter("aldrin-1963.jpg").verdict)
        val later = sitter("aldrin-1969.jpg")
        assertEquals(buzz.id, later.personId, "1969 is suggested as Buzz")
        assertTrue(later.suggested)
        assertNull(sitter("collins-1964.jpg").personId, "Collins is nobody")

        // No new photographs and no new labels: the index stands, and nothing is sent again.
        run("sync")
        expect { exit(ExitCode.CLEAN) }
        assertTrue("faces:" !in output, output)

        // A box drawn round Collins in the viewer, twice the size the detector boxed him — too loose
        // to be taken for that face — and named: the next sync looks inside it, finds him, and
        // indexes the face under the drawn box, confirmed.
        val collins = sitter("collins-1964.jpg")
        val loose = FaceBox(
            (collins.box.x - collins.box.width / 2).coerceAtLeast(0f),
            (collins.box.y - collins.box.height / 2).coerceAtLeast(0f),
            collins.box.width * 2,
            collins.box.height * 2,
        )
        val mike = labels.createPerson("Mike")
        labels.confirm(listOf(FaceRef(collins.photoId, loose)), mike.id)
        run("sync")
        expect { exit(ExitCode.CLEAN) }
        val drawn = index.read().single { it.photoId == collins.photoId && it.box == loose }
        assertEquals(VerdictKind.CONFIRMED, drawn.verdict)
        assertEquals(mike.id, drawn.personId)

        // The album goes, and its faces with it.
        library { removeTree("Crew") }
        run("sync")
        expect { exit(ExitCode.CLEAN) }
        assertNull(s3.head(FacePhase.facesKey(album)), "faces/ follows its album")
        assertTrue(index.read().isEmpty())
    }
}
