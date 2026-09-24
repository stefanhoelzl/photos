package net.stho.photos.faces

import app.cash.sqldelight.db.SqlDriver
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.instantAdapter
import net.stho.photos.catalog.openDriver
import net.stho.photos.catalog.uuidAdapter
import net.stho.photos.faces.people.Person as PersonRow
import net.stho.photos.faces.people.PeopleDatabase
import net.stho.photos.faces.people.PeopleQueries
import net.stho.photos.faces.people.Verdict as VerdictRow
import net.stho.photos.ports.SqlDrivers

public data class Person(public val id: Uuid, public val name: String)

/** One decision about one face, named by photo and box so it outlives any detection (§12). */
public data class Verdict(
    public val id: Uuid,
    public val photoId: Uuid,
    public val box: FaceBox,
    public val kind: VerdictKind,
    /** Null exactly when [kind] is [VerdictKind.IGNORED]. */
    public val personId: Uuid?,
    public val decidedAt: Instant,
)

/** Everything the labels file holds, read at one moment. */
public class LabelSet(public val people: List<Person>, public val verdicts: List<Verdict>)

/**
 * The labels file, `$LIBRARY_ROOT/.photos/people.db` (§12): the people, and every decision about a
 * face.
 *
 * The desktop viewer is its one writer and `sync` its one reader, so the rules for what a decision
 * does to the decisions already made live here, where both can see them — a face has at most one
 * confirmed-or-ignored verdict, any number of rejections, and confirming a face as a person
 * withdraws a rejection of that same person.
 *
 * Every operation opens, acts and closes. A decision is a few rows, made at the speed a person
 * clicks, and a connection held open between them would be one more thing to keep consistent with
 * a `sync` reading the same file from another process.
 */
public class Labels(
    public val path: Path,
    private val drivers: SqlDrivers,
    private val clock: Clock = Clock.System,
    private val ids: () -> Uuid = { Uuid.random() },
) {
    public val exists: Boolean get() = SystemFileSystem.exists(path)

    /** Everything, or an empty set when there is no file yet. */
    public fun read(): LabelSet {
        if (!exists) return LabelSet(emptyList(), emptyList())
        return reading { queries ->
            LabelSet(
                people = queries.selectPeople().executeAsList().map { Person(it.id, it.name) },
                verdicts = queries.selectVerdicts().executeAsList().map(VerdictRow::toVerdict),
            )
        }
    }

    public fun createPerson(name: String): Person = writing { queries ->
        val person = Person(ids(), name.trim())
        queries.insertPerson(person.id, person.name, now())
        person
    }

    public fun rename(person: Uuid, name: String): Unit = writing { it.renamePerson(name.trim(), person) }

    /**
     * Moves every verdict about [from] to [into] and removes [from] — how a duplicate person, or
     * one created by mistake, is undone (§12 has no delete). A face confirmed as one and rejected
     * as the other keeps the confirmation.
     */
    public fun merge(from: Uuid, into: Uuid): Unit = writing { queries ->
        if (from == into) return@writing
        val verdicts = queries.selectVerdicts().executeAsList().map(VerdictRow::toVerdict)
        val confirmedInto = verdicts.filter { it.personId == into && it.kind == VerdictKind.CONFIRMED }
        for (rejection in verdicts.filter { it.personId == from && it.kind == VerdictKind.REJECTED }) {
            if (confirmedInto.any { it.sameFace(rejection) }) queries.deleteVerdict(rejection.id)
        }
        queries.moveVerdicts(to = into, from = from)
        queries.deletePerson(from)
    }

    /** These faces are [person]. Replaces any confirmation or ignore they had. */
    public fun confirm(faces: List<FaceRef>, person: Uuid): Change = decide(faces) { face, existing ->
        existing.filter { it.kind != VerdictKind.REJECTED || it.personId == person }.forEach(::remove)
        add(face, VerdictKind.CONFIRMED, person)
    }

    /** These faces are not [person]. Withdraws a confirmation of that same person. */
    public fun reject(faces: List<FaceRef>, person: Uuid): Change = decide(faces) { face, existing ->
        if (existing.any { it.kind == VerdictKind.REJECTED && it.personId == person }) return@decide
        existing.filter { it.kind == VerdictKind.CONFIRMED && it.personId == person }.forEach(::remove)
        add(face, VerdictKind.REJECTED, person)
    }

    /** Nobody to name. Replaces a confirmation; rejections stay, as history for suggestions. */
    public fun ignore(faces: List<FaceRef>): Change = decide(faces) { face, existing ->
        existing.filter { it.kind != VerdictKind.REJECTED }.forEach(::remove)
        add(face, VerdictKind.IGNORED, null)
    }

    /**
     * Withdraws the confirmation or ignore these faces carry — Enter on a confirmed face (§12). The
     * face falls back to whatever the next `sync` suggests.
     */
    public fun clear(faces: List<FaceRef>): Change = decide(faces) { _, existing ->
        existing.filter { it.kind != VerdictKind.REJECTED }.forEach(::remove)
    }

    /**
     * Ctrl+Z (§12): what [change] added is taken out and what it replaced is put back, under the
     * ids they had — so an undone decision is as if it had never been made.
     */
    public fun revert(change: Change): Unit = writing { queries ->
        for (verdict in change.added) queries.deleteVerdict(verdict.id)
        for (verdict in change.removed) queries.insert(verdict)
    }

    /** What one decision did to the labels file, verdict by verdict — what [revert] undoes. */
    public class Change(public val removed: List<Verdict>, public val added: List<Verdict>) {
        public val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()
    }

    /** One decision's writes, recorded as they are made. */
    private inner class Step(private val queries: PeopleQueries) {
        val removed = mutableListOf<Verdict>()
        val added = mutableListOf<Verdict>()

        fun remove(verdict: Verdict) {
            queries.deleteVerdict(verdict.id)
            removed += verdict
        }

        fun add(face: FaceRef, kind: VerdictKind, person: Uuid?) {
            val verdict = Verdict(ids(), face.photoId, face.box, kind, person, now())
            queries.insert(verdict)
            added += verdict
        }
    }

    private fun PeopleQueries.insert(verdict: Verdict) {
        insertVerdict(
            id = verdict.id,
            photo_id = verdict.photoId,
            x = verdict.box.x.toDouble(),
            y = verdict.box.y.toDouble(),
            w = verdict.box.width.toDouble(),
            h = verdict.box.height.toDouble(),
            kind = verdict.kind,
            person_id = verdict.personId,
            decided_at = verdict.decidedAt,
        )
    }

    private fun decide(
        faces: List<FaceRef>,
        action: Step.(FaceRef, List<Verdict>) -> Unit,
    ): Change = writing { queries ->
        val step = Step(queries)
        for (face in faces) {
            val existing = queries.selectVerdictsForPhoto(face.photoId).executeAsList()
                .map(VerdictRow::toVerdict)
                .filter { it.box.overlap(face.box) >= SAME_FACE_OVERLAP }
            step.action(face, existing)
        }
        Change(step.removed.toList(), step.added.toList())
    }

    private fun now(): Instant = Instant.fromEpochSeconds(clock.now().epochSeconds)

    private inline fun <T> reading(block: (PeopleQueries) -> T): T {
        val driver = path.openDriver(drivers, PeopleDatabase.Schema, creating = false)
        try {
            return block(database(driver).peopleQueries)
        } finally {
            driver.close()
        }
    }

    private fun <T> writing(block: (PeopleQueries) -> T): T {
        val driver = path.openDriver(drivers, PeopleDatabase.Schema, creating = true)
        try {
            val queries = database(driver).peopleQueries
            return queries.transactionWithResult {
                queries.insertInfo(SCHEMA_VERSION.toLong())
                block(queries)
            }
        } finally {
            driver.close()
        }
    }

    public companion object {
        public const val SCHEMA_VERSION: Int = 1

        /** `.photos/` in the library root: the walker's one reserved name (§12). */
        public const val DIRECTORY: String = ".photos"
        public const val FILENAME: String = "people.db"

        /**
         * How much two boxes on one photo must overlap to be the same face. Two detections of one
         * face by one model overlap almost entirely; neighbouring faces in a crowd, barely.
         */
        public const val SAME_FACE_OVERLAP: Float = 0.5f

        public fun at(libraryRoot: Path, drivers: SqlDrivers, clock: Clock = Clock.System): Labels =
            Labels(Path(libraryRoot, DIRECTORY, FILENAME), drivers, clock)

        internal fun database(driver: SqlDriver): PeopleDatabase = PeopleDatabase(
            driver,
            personAdapter = PersonRow.Adapter(idAdapter = uuidAdapter, created_atAdapter = instantAdapter),
            verdictAdapter = VerdictRow.Adapter(
                idAdapter = uuidAdapter,
                photo_idAdapter = uuidAdapter,
                kindAdapter = verdictKindAdapter,
                person_idAdapter = uuidAdapter,
                decided_atAdapter = instantAdapter,
            ),
        )
    }
}

/** A face as the viewer points at it: the photo it is in, and where. */
public data class FaceRef(public val photoId: Uuid, public val box: FaceBox)

internal fun VerdictRow.toVerdict(): Verdict = Verdict(
    id = id,
    photoId = photo_id,
    box = FaceBox(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()),
    kind = kind,
    personId = person_id,
    decidedAt = decided_at,
)

private fun Verdict.sameFace(other: Verdict): Boolean =
    photoId == other.photoId && box.overlap(other.box) >= Labels.SAME_FACE_OVERLAP
