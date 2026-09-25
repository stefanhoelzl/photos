package net.stho.photos.faces

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * §12's matching, on embeddings made to measure: a face is a unit vector, and "the same person"
 * is a small turn away from their other faces.
 */
class MatchingTest {

    private val album = Uuid.random()
    private val anna = Uuid.random()
    private val ben = Uuid.random()
    private val box = FaceBox(0.4f, 0.3f, 0.2f, 0.25f)

    /** A unit vector near axis [axis], turned towards axis 7 by [lean]. */
    private fun embedding(axis: Int, lean: Float = 0f): FloatArray {
        val v = FloatArray(8)
        v[axis] = 1f
        v[7] = lean
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(8) { v[it] / norm }
    }

    private fun face(vector: FloatArray, score: Float = 0.9f): IndexedFace =
        IndexedFace(Uuid.random(), album, Uuid.random(), DetectedFace(box, FloatArray(10), score, vector))

    private fun verdict(face: IndexedFace, kind: VerdictKind, person: Uuid?, at: Long = 1) =
        Verdict(Uuid.random(), face.photoId, face.face.box, kind, person, Instant.fromEpochSeconds(at))

    @Test
    fun aFaceLikeAConfirmedOneIsSuggestedAsThatPerson() {
        val confirmed = face(embedding(0))
        val alike = face(embedding(0, lean = 0.3f))
        val stranger = face(embedding(3))
        val faces = listOf(confirmed, alike, stranger)

        val resolved = Matching.resolve(faces, listOf(verdict(confirmed, VerdictKind.CONFIRMED, anna)))
        val suggestions = Matching.suggest(faces, resolved)

        assertEquals(anna, suggestions[alike.id]?.person)
        assertNull(suggestions[stranger.id])
        assertNull(suggestions[confirmed.id], "a confirmed face is not suggested")
    }

    @Test
    fun everyFaceListsThePeopleItIsMostLikeBestFirstLessRejections() {
        val a = face(embedding(0))
        val b = face(embedding(1))
        val mostlyA = face(embedding(0, lean = 0.4f).also { it[1] = 0.3f })
        val faces = listOf(a, b, mostlyA)
        val resolved = Matching.resolve(
            faces,
            listOf(verdict(a, VerdictKind.CONFIRMED, anna), verdict(b, VerdictKind.CONFIRMED, ben)),
        )
        val weighed = Matching.weigh(faces, resolved)
        assertEquals(listOf(anna, ben), weighed.candidates.getValue(mostlyA.id).map { it.person })
        // A confirmed face is ranked too: its confirmation could be the mistake.
        assertEquals(anna, weighed.candidates.getValue(a.id).first().person)

        val rejected = Matching.resolve(faces, resolved.let { listOf(verdict(a, VerdictKind.CONFIRMED, anna), verdict(b, VerdictKind.CONFIRMED, ben), verdict(mostlyA, VerdictKind.REJECTED, anna)) })
        assertEquals(listOf(ben), Matching.weigh(faces, rejected).candidates.getValue(mostlyA.id).map { it.person })
    }

    @Test
    fun aFaceTheDetectorDoubtsIsNotSuggestedHoweverAlikeItLooks() {
        val confirmed = face(embedding(0))
        val doubtful = face(embedding(0, lean = 0.1f), score = 0.65f)
        val faces = listOf(confirmed, doubtful)
        val resolved = Matching.resolve(faces, listOf(verdict(confirmed, VerdictKind.CONFIRMED, anna)))
        assertNull(Matching.suggest(faces, resolved)[doubtful.id])
        // Still ranked for naming it by hand from its box.
        assertEquals(anna, Matching.weigh(faces, resolved).candidates.getValue(doubtful.id).first().person)
    }

    /** Confirmed, a doubted face counts as the person's like any other — and helps find them. */
    @Test
    fun aConfirmedFaceTheDetectorDoubtedIsStillAReference() {
        val doubtfulButConfirmed = face(embedding(0), score = 0.6f)
        val alike = face(embedding(0, lean = 0.3f))
        val faces = listOf(doubtfulButConfirmed, alike)
        val resolved = Matching.resolve(faces, listOf(verdict(doubtfulButConfirmed, VerdictKind.CONFIRMED, anna)))
        assertEquals(anna, resolved.confirmed[doubtfulButConfirmed.id])
        assertEquals(anna, Matching.suggest(faces, resolved)[alike.id]?.person)
    }

    @Test
    fun theSharpnessFloorIsWhereTheBlurriestConfirmedFacesSit() {
        // 100 confirmed faces, sharpness 1..100: the floor is where the lowest 5% end.
        val confirmed = List(100) { DetectedFace(box, FloatArray(10), 0.9f, embedding(0), sharpness = (it + 1).toFloat()) }
        val quality = Matching.Quality.from(confirmed)
        assertEquals(6f, quality.sharpnessFloor)
        assertFalse(quality.trusted(DetectedFace(box, FloatArray(10), 0.95f, embedding(0), sharpness = 3f)), "blurred")
        assertTrue(quality.trusted(DetectedFace(box, FloatArray(10), 0.95f, embedding(0), sharpness = 40f)))
        assertTrue(quality.trusted(DetectedFace(box, FloatArray(10), 0.95f, embedding(0))), "never measured: confidence decides")
        // Too few confirmed faces to measure a floor from: confidence alone.
        assertNull(Matching.Quality.from(confirmed.take(10)).sharpnessFloor)
    }

    @Test
    fun aRejectionIsNeverSuggestedAgainAndCountsAgainstItsNeighbours() {
        val confirmed = face(embedding(0))
        val rejected = face(embedding(0, lean = 0.5f))
        val closeToTheRejected = face(embedding(0, lean = 0.55f))
        val faces = listOf(confirmed, rejected, closeToTheRejected)

        val resolved = Matching.resolve(
            faces,
            listOf(
                verdict(confirmed, VerdictKind.CONFIRMED, anna),
                verdict(rejected, VerdictKind.REJECTED, anna),
            ),
        )
        val suggestions = Matching.suggest(faces, resolved)

        assertNull(suggestions[rejected.id])
        assertNull(suggestions[closeToTheRejected.id], "nearer a rejection than any confirmation")
    }

    @Test
    fun twoPeopleEquallyLikeTheFaceSuggestNeither() {
        val a = face(embedding(0))
        val b = face(embedding(1))
        // Halfway between the two.
        val between = face(FloatArray(8).also { it[0] = 0.7071f; it[1] = 0.7071f })
        val faces = listOf(a, b, between)
        val resolved = Matching.resolve(
            faces,
            listOf(verdict(a, VerdictKind.CONFIRMED, anna), verdict(b, VerdictKind.CONFIRMED, ben)),
        )
        assertNull(Matching.suggest(faces, resolved)[between.id], "no margin between Anna and Ben")
    }

    @Test
    fun theLaterDecisionAboutAFaceStands() {
        val one = face(embedding(0))
        val resolved = Matching.resolve(
            listOf(one),
            listOf(
                verdict(one, VerdictKind.CONFIRMED, anna, at = 1),
                verdict(one, VerdictKind.IGNORED, null, at = 2),
            ),
        )
        assertTrue(one.id in resolved.ignored)
        assertFalse(one.id in resolved.confirmed)
    }

    @Test
    fun aVerdictFindsARedetectedFaceByOverlapAndNotAFaceBesideIt() {
        val photo = Uuid.random()
        val here = FaceBox(0.10f, 0.10f, 0.20f, 0.20f)
        val redetected = IndexedFace(Uuid.random(), album, photo, DetectedFace(FaceBox(0.11f, 0.10f, 0.20f, 0.21f), FloatArray(10), 0.9f, embedding(0)))
        val beside = IndexedFace(Uuid.random(), album, photo, DetectedFace(FaceBox(0.50f, 0.10f, 0.20f, 0.20f), FloatArray(10), 0.9f, embedding(1)))
        val resolved = Matching.resolve(
            listOf(redetected, beside),
            listOf(Verdict(Uuid.random(), photo, here, VerdictKind.CONFIRMED, anna, Instant.fromEpochSeconds(1))),
        )
        assertEquals(mapOf(redetected.id to anna), resolved.confirmed)
    }

    @Test
    fun unknownFacesFormGroupsLargestFirstAndThinOnesAreLeftOut() {
        val big = List(7) { face(embedding(0, lean = it * 0.03f)) }
        val small = List(5) { face(embedding(2, lean = it * 0.03f)) }
        // Too few to be dense: no face here has CORE faces within reach.
        val pair = List(2) { face(embedding(4, lean = it * 0.03f)) }
        val groups = Matching.group(big + small + pair)

        assertTrue(big.all { groups[it.id] == 1 })
        assertTrue(small.all { groups[it.id] == 2 })
        assertTrue(pair.none { it.id in groups })
    }

    /**
     * A face halfway between two people is within reach of both, but it is no core — so it
     * cannot join them into one group, the way a greedy pass around centres did.
     */
    @Test
    fun aLookAlikeBetweenTwoPeopleDoesNotChainThemTogether() {
        val anna = List(6) { face(embedding(0, lean = it * 0.02f)) }
        val ben = List(6) { face(embedding(1, lean = it * 0.02f)) }
        val between = face(FloatArray(8).also { it[0] = 0.8f; it[1] = 0.6f })
        val groups = Matching.group(anna + ben + between)
        assertTrue(groups[anna[0].id] != groups[ben[0].id])
        assertTrue(anna.all { groups[it.id] == groups[anna[0].id] })
        assertTrue(ben.all { groups[it.id] == groups[ben[0].id] })
    }
}
