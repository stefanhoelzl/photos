package net.stho.photos.faces

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.testDrivers
import net.stho.photos.scratchPath

/** The rules for what a decision does to the decisions already made about a face (§12). */
class LabelsTest {

    private val root = scratchPath("labels")
    private val labels = Labels(Path(root, "people.db"), testDrivers)
    private val face = FaceRef(Uuid.random(), FaceBox(0.1f, 0.1f, 0.2f, 0.2f))
    /** The same face as a later model boxes it: nearly the same place. */
    private val sameFace = FaceRef(face.photoId, FaceBox(0.11f, 0.1f, 0.2f, 0.21f))

    @AfterTest
    fun clean() {
        runCatching { SystemFileSystem.delete(Path(root, "people.db")) }
        runCatching { SystemFileSystem.delete(root) }
    }

    private fun kinds(): List<Pair<VerdictKind, Uuid?>> =
        labels.read().verdicts.map { it.kind to it.personId }.sortedBy { it.first.wire }

    @Test
    fun confirmingReplacesAnEarlierConfirmationAndKeepsOtherRejections() {
        val anna = labels.createPerson("Anna")
        val ben = labels.createPerson("Ben")
        val cleo = labels.createPerson("Cleo")
        labels.reject(listOf(face), cleo.id)
        labels.confirm(listOf(face), anna.id)
        labels.confirm(listOf(sameFace), ben.id)

        assertEquals(listOf(VerdictKind.CONFIRMED to ben.id, VerdictKind.REJECTED to cleo.id), kinds())
    }

    @Test
    fun rejectingThePersonAFaceIsConfirmedAsWithdrawsTheConfirmation() {
        val anna = labels.createPerson("Anna")
        labels.confirm(listOf(face), anna.id)
        labels.reject(listOf(sameFace), anna.id)
        assertEquals(listOf(VerdictKind.REJECTED to anna.id), kinds())
    }

    @Test
    fun clearingLeavesOnlyTheRejections() {
        val anna = labels.createPerson("Anna")
        val ben = labels.createPerson("Ben")
        labels.reject(listOf(face), ben.id)
        labels.confirm(listOf(face), anna.id)
        labels.clear(listOf(face))
        assertEquals(listOf(VerdictKind.REJECTED to ben.id), kinds())
    }

    @Test
    fun revertingADecisionRestoresWhatItReplaced() {
        val anna = labels.createPerson("Anna")
        val ben = labels.createPerson("Ben")
        labels.confirm(listOf(face), anna.id)
        val before = labels.read().verdicts

        val change = labels.confirm(listOf(sameFace), ben.id)
        assertEquals(listOf(VerdictKind.CONFIRMED to ben.id), kinds())

        labels.revert(change)
        assertEquals(before, labels.read().verdicts, "Anna's confirmation, as it was, id and all")
    }

    @Test
    fun mergingMovesEveryVerdictAndRemovesThePerson() {
        val anna = labels.createPerson("Anna")
        val annaAgain = labels.createPerson("Anna B.")
        val other = FaceRef(Uuid.random(), FaceBox(0.5f, 0.5f, 0.1f, 0.1f))
        labels.confirm(listOf(face), anna.id)
        labels.confirm(listOf(other), annaAgain.id)
        labels.merge(from = annaAgain.id, into = anna.id)

        val read = labels.read()
        assertEquals(listOf("Anna"), read.people.map { it.name })
        assertTrue(read.verdicts.all { it.personId == anna.id && it.kind == VerdictKind.CONFIRMED })
        assertEquals(2, read.verdicts.size)
    }
}
