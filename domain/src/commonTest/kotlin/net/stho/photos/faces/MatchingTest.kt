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
    fun theLastGroupingIsKeptAndOnlyNewFacesArePlaced() {
        val big = List(5) { face(embedding(0, lean = it * 0.05f)) }
        val small = List(3) { face(embedding(2, lean = it * 0.05f)) }
        val first = Matching.group(big + small)

        // Two of the big group named since, and a new photograph of its person.
        val newcomer = face(embedding(0, lean = 0.12f))
        val again = Matching.group(big.drop(2) + small + newcomer, previous = first)

        assertTrue(big.drop(2).all { again[it.id] == again[newcomer.id] }, "the newcomer joins the kept group")
        assertTrue(small.all { again[it.id] == again[small[0].id] })
        assertTrue(again[newcomer.id] != again[small[0].id])
    }

    @Test
    fun unknownFacesFormGroupsLargestFirstAndSmallOnesAreLeftOut() {
        val big = List(5) { face(embedding(0, lean = it * 0.05f)) }
        val small = List(3) { face(embedding(2, lean = it * 0.05f)) }
        val pair = List(2) { face(embedding(4, lean = it * 0.05f)) }
        val groups = Matching.group(big + small + pair)

        assertTrue(big.all { groups[it.id] == 1 })
        assertTrue(small.all { groups[it.id] == 2 })
        assertTrue(pair.none { it.id in groups }, "a pair is below MIN_GROUP")
    }
}
