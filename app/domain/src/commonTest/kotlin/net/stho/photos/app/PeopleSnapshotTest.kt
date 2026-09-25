package net.stho.photos.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.stho.photos.faces.FaceBox
import net.stho.photos.faces.IndexEntry
import net.stho.photos.faces.LabelSet
import net.stho.photos.faces.Person
import net.stho.photos.faces.Suggestion
import net.stho.photos.faces.Verdict
import net.stho.photos.faces.VerdictKind

/**
 * The labels file as it is now, laid over the index the last sync built (§12): a decision shows
 * at once, and nothing is suggested that the sync did not suggest.
 */
class PeopleSnapshotTest {

    private val anna = Person(Uuid.random(), "Anna")
    private val album = Uuid.random()
    private val box = FaceBox(0.2f, 0.2f, 0.2f, 0.2f)

    private fun entry(
        suggested: Uuid? = null,
        group: Int? = null,
        verdict: VerdictKind? = null,
        confirmed: Uuid? = null,
    ) = IndexEntry(
        faceId = Uuid.random(), albumId = album, photoId = Uuid.random(), box = box, score = 0.9f,
        verdict = verdict, personId = confirmed ?: suggested, suggested = suggested != null,
        similarity = suggested?.let { 0.7f }, group = group,
    )

    private fun on(entry: IndexEntry, kind: VerdictKind, person: Uuid?, at: Long = 10) =
        Verdict(Uuid.random(), entry.photoId, entry.box, kind, person, Instant.fromEpochSeconds(at))

    @Test
    fun aConfirmationMadeSinceTheSyncTakesTheFaceOutOfItsGroup() {
        val grouped = List(4) { entry(group = 1) }
        val snapshot = PeopleSnapshot.of(grouped, LabelSet(listOf(anna), listOf(on(grouped[0], VerdictKind.CONFIRMED, anna.id))))

        assertEquals(FaceState.CONFIRMED, snapshot.faces.single { it.id == grouped[0].faceId }.state)
        assertEquals(1, snapshot.person(anna.id)?.confirmed)
        assertEquals(3, snapshot.groups.single { it.id == 1 }.size)
    }

    @Test
    fun aGroupNamedDownBelowThreeJoinsOther() {
        val grouped = List(3) { entry(group = 1) }
        val snapshot = PeopleSnapshot.of(grouped, LabelSet(listOf(anna), listOf(on(grouped[0], VerdictKind.CONFIRMED, anna.id))))
        assertEquals(listOf(Face.OTHER), snapshot.groups.map { it.id })
        assertEquals(2, snapshot.facesIn(Face.OTHER).size)
    }

    @Test
    fun aRejectionMadeSinceTheSyncWithdrawsItsSuggestion() {
        val suggestion = entry(suggested = anna.id)
        val snapshot = PeopleSnapshot.of(listOf(suggestion), LabelSet(listOf(anna), listOf(on(suggestion, VerdictKind.REJECTED, anna.id))))
        val face = snapshot.faces.single()
        assertEquals(FaceState.UNKNOWN, face.state)
        assertNull(face.person)
    }

    @Test
    fun anIgnoredFaceIsGoneAndAWithdrawnConfirmationIsUnknownAgain() {
        val ignored = entry(group = 1)
        val withdrawn = entry(verdict = VerdictKind.CONFIRMED, confirmed = anna.id)
        // The sync saw `withdrawn` confirmed; the labels file no longer says so.
        val snapshot = PeopleSnapshot.of(listOf(ignored, withdrawn), LabelSet(listOf(anna), listOf(on(ignored, VerdictKind.IGNORED, null))))

        // Out of every group and every photo's boxes, and kept for review under "Ignored".
        assertEquals(listOf(ignored.faceId), snapshot.ignored.map { it.id })
        assertTrue(snapshot.facesOn(ignored.photoId).isEmpty())
        assertTrue(snapshot.groups.none { group -> snapshot.facesIn(group.id).any { it.id == ignored.faceId } })
        val face = snapshot.faces.single { it.id == withdrawn.faceId }
        assertEquals(FaceState.UNKNOWN, face.state)
        assertEquals(Face.OTHER, face.group)
    }

    @Test
    fun peopleAreListedByTheirLatestPhotoNewestFirst() {
        val ben = Person(Uuid.random(), "Ben")
        val cleo = Person(Uuid.random(), "Cleo")
        val old = entry(verdict = VerdictKind.CONFIRMED, confirmed = anna.id)
        val recent = entry(verdict = VerdictKind.CONFIRMED, confirmed = ben.id)
        val undated = entry(verdict = VerdictKind.CONFIRMED, confirmed = cleo.id)
        val dates = mapOf(old.photoId to Instant.parse("2010-06-01T00:00:00Z"), recent.photoId to Instant.parse("2024-06-01T00:00:00Z"))
        val snapshot = PeopleSnapshot.of(
            listOf(old, recent, undated),
            LabelSet(
                listOf(anna, ben, cleo),
                listOf(on(old, VerdictKind.CONFIRMED, anna.id), on(recent, VerdictKind.CONFIRMED, ben.id), on(undated, VerdictKind.CONFIRMED, cleo.id)),
            ),
            takenAt = dates::get,
        )
        assertEquals(listOf("Ben", "Anna", "Cleo"), snapshot.people.map { it.person.name })
    }

    @Test
    fun theNamingMenuListsTheMostAlikeFirstAndTheRestByName() {
        val ben = Person(Uuid.random(), "Ben")
        val cleo = Person(Uuid.random(), "Cleo")
        val face = entry(group = 1).copy(candidates = listOf(Suggestion(cleo.id, 0.6f), Suggestion(ben.id, 0.3f)))
        val snapshot = PeopleSnapshot.of(listOf(face), LabelSet(listOf(anna, ben, cleo), emptyList()))
        assertEquals(listOf("Cleo", "Ben", "Anna"), snapshot.ranked(snapshot.faces).map { it.name })
    }

    @Test
    fun aFaceTheDetectorDoubtsIsListedNowhereButBoxedOnItsPhoto() {
        val grouped = List(3) { entry(group = 1) }
        // The CLI decides what is set aside, where the sharpness floor is measured; the viewer reads it.
        val doubtful = entry(group = 1).copy(score = 0.6f, setAside = true)
        val snapshot = PeopleSnapshot.of(grouped + doubtful, LabelSet(listOf(anna), emptyList()))
        assertTrue(snapshot.groups.none { group -> snapshot.facesIn(group.id).any { it.id == doubtful.faceId } })
        assertEquals(listOf(doubtful.faceId), snapshot.facesOn(doubtful.photoId).map { it.id })
    }

    /** A verdict stands whatever the detector thought: a confirmed face stays with its person. */
    @Test
    fun aConfirmedFaceTheDetectorDoubtedStaysWithItsPerson() {
        val doubtful = entry(verdict = VerdictKind.CONFIRMED, confirmed = anna.id).copy(score = 0.55f)
        val snapshot = PeopleSnapshot.of(listOf(doubtful), LabelSet(listOf(anna), listOf(on(doubtful, VerdictKind.CONFIRMED, anna.id))))
        assertEquals(listOf(doubtful.faceId), snapshot.facesOf(anna.id).map { it.id })
        assertEquals(1, snapshot.person(anna.id)?.confirmed)
    }

    /** A box drawn round a missed face shows under its person at once, before any sync saw it. */
    @Test
    fun aDrawnFaceIsItsPersonsBeforeTheSyncHasLookedInsideIt() {
        val detected = entry(verdict = VerdictKind.CONFIRMED, confirmed = anna.id)
        val photo = Uuid.random()
        val drawn = Verdict(Uuid.random(), photo, FaceBox(0.6f, 0.2f, 0.1f, 0.15f), VerdictKind.CONFIRMED, anna.id, Instant.fromEpochSeconds(20))
        val snapshot = PeopleSnapshot.of(
            listOf(detected),
            LabelSet(listOf(anna), listOf(on(detected, VerdictKind.CONFIRMED, anna.id), drawn)),
            albumOf = { if (it == photo) album else null },
        )
        val faces = snapshot.facesOf(anna.id)
        assertEquals(2, faces.size)
        assertTrue(faces.single { it.photoId == photo }.drawn)
        assertEquals(2, snapshot.person(anna.id)?.confirmed)
        assertEquals(listOf(drawn.id), snapshot.facesOn(photo).map { it.id }, "and boxed on its photo")
    }

    @Test
    fun aPersonsSuggestionsComeFirstMostAlikeFirst() {
        val confirmed = entry(verdict = VerdictKind.CONFIRMED, confirmed = anna.id)
        val weak = entry(suggested = anna.id).copy(similarity = 0.5f)
        val strong = entry(suggested = anna.id).copy(similarity = 0.8f)
        val snapshot = PeopleSnapshot.of(
            listOf(confirmed, weak, strong),
            LabelSet(listOf(anna), listOf(on(confirmed, VerdictKind.CONFIRMED, anna.id))),
        )
        assertEquals(listOf(strong.faceId, weak.faceId, confirmed.faceId), snapshot.facesOf(anna.id).map { it.id })
    }
}
