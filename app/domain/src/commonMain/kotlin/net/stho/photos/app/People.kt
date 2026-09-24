package net.stho.photos.app

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import net.stho.photos.faces.FaceBox
import net.stho.photos.faces.FaceRef
import net.stho.photos.faces.IndexEntry
import net.stho.photos.faces.LabelSet
import net.stho.photos.faces.Labels
import net.stho.photos.faces.Matching
import net.stho.photos.faces.PeopleIndex
import net.stho.photos.faces.Person
import net.stho.photos.faces.VerdictKind
import net.stho.photos.ports.SqlDrivers

/** Where a face stands for the viewer, once its own verdicts are laid over the last sync's. */
public enum class FaceState { CONFIRMED, SUGGESTED, UNKNOWN, IGNORED }

/** One face, as the viewer lists it and draws its box (§12). */
public data class Face(
    val id: Uuid,
    val albumId: Uuid,
    val photoId: Uuid,
    val box: FaceBox,
    val score: Float,
    val state: FaceState,
    /** Confirmed as, or suggested as; null for an unknown face. */
    val person: Uuid?,
    val similarity: Float?,
    /** Its unknown group, [OTHER] for one too small to show; null unless [state] is unknown. */
    val group: Int?,
) {
    val ref: FaceRef get() = FaceRef(photoId, box)

    public companion object {
        /** The group number of "Other": unknown faces in no group big enough to show. */
        public const val OTHER: Int = 0
    }
}

public data class PersonSummary(
    val person: Person,
    val confirmed: Int,
    val suggested: Int,
    /** The most confident confirmed face, which stands for them (§12: no cover choice in v1). */
    val avatar: Face?,
    /** When their newest confirmed photo was taken: the sidebar lists the most recent first. */
    val latest: Instant? = null,
)

public data class GroupSummary(val id: Int, val size: Int, val sample: Face)

/**
 * Every face in the library and every person, as the viewer shows them: the index `sync` built,
 * with the labels file as it is *now* laid over it.
 *
 * The overlay is what makes a confirmation visible the moment it is made, although suggestions
 * only move at the next sync (§12): a face confirmed here leaves its unknown group and joins its
 * person at once, a face rejected here stops being suggested at once, and nothing is guessed that
 * `sync` did not.
 */
public class PeopleSnapshot(
    public val people: List<PersonSummary>,
    /** Largest first, then [Face.OTHER] last when anything is in it. */
    public val groups: List<GroupSummary>,
    public val faces: List<Face>,
) {
    private val byPhoto = faces.groupBy(Face::photoId)

    public fun person(id: Uuid): PersonSummary? = people.firstOrNull { it.person.id == id }

    /** A person's faces: suggestions, most similar first, then the confirmed ones. */
    public fun facesOf(person: Uuid): List<Face> {
        val mine = faces.filter { it.person == person }
        return mine.filter { it.state == FaceState.SUGGESTED }.sortedByDescending { it.similarity ?: 0f } +
            mine.filter { it.state == FaceState.CONFIRMED }.sortedByDescending { it.score }
    }

    public fun facesIn(group: Int): List<Face> =
        faces.filter { it.state == FaceState.UNKNOWN && it.group == group }.sortedByDescending { it.score }

    /** Every ignored face, kept for review in the sidebar's "Ignored" (§12). */
    public val ignored: List<Face> get() = faces.filter { it.state == FaceState.IGNORED }.sortedByDescending { it.score }

    /** The faces on one photo, for its boxes in the viewer. Ignored faces are not among them. */
    public fun facesOn(photo: Uuid): List<Face> = byPhoto[photo].orEmpty().filter { it.state != FaceState.IGNORED }

    public companion object {
        public val EMPTY: PeopleSnapshot = PeopleSnapshot(emptyList(), emptyList(), emptyList())

        /**
         * [entries] as the index has them, with [labels] as the labels file has them now. People
         * come newest first — by [takenAt] of their latest confirmed photo — and those with no
         * dated photo after them, by name.
         */
        public fun of(
            entries: List<IndexEntry>,
            labels: LabelSet,
            takenAt: (Uuid) -> Instant? = { null },
        ): PeopleSnapshot {
            val live = labels.verdicts.groupBy { it.photoId }
            val faces = entries.mapNotNull { entry ->
                val verdicts = live[entry.photoId].orEmpty()
                    .filter { it.box.overlap(entry.box) >= Labels.SAME_FACE_OVERLAP }
                    .sortedBy { it.decidedAt }
                val standing = verdicts.lastOrNull { it.kind != VerdictKind.REJECTED }
                val rejected = verdicts.filter { it.kind == VerdictKind.REJECTED }.mapNotNullTo(mutableSetOf()) { it.personId }
                when {
                    standing?.kind == VerdictKind.IGNORED -> entry.face(FaceState.IGNORED, null, null)
                    standing?.kind == VerdictKind.CONFIRMED -> entry.face(FaceState.CONFIRMED, standing.personId, null)
                    entry.suggested && entry.personId != null && entry.personId !in rejected ->
                        entry.face(FaceState.SUGGESTED, entry.personId, null)
                    // Unknown: in the group the last sync put it in, or — confirmed or ignored then,
                    // and withdrawn here since — in "Other" until the next sync groups it.
                    else -> entry.face(FaceState.UNKNOWN, null, entry.group ?: Face.OTHER)
                }
            }

            val known = labels.people.associateBy(Person::id)
            val people = labels.people.map { person ->
                val mine = faces.filter { it.person == person.id }
                val confirmed = mine.filter { it.state == FaceState.CONFIRMED }
                PersonSummary(
                    person = person,
                    confirmed = confirmed.size,
                    suggested = mine.count { it.state == FaceState.SUGGESTED },
                    avatar = confirmed.maxByOrNull { it.score },
                    latest = confirmed.mapNotNull { takenAt(it.photoId) }.maxOrNull(),
                )
            }.sortedWith(
                compareBy<PersonSummary> { it.latest == null }
                    .thenByDescending { it.latest }
                    .thenBy { it.person.name.lowercase() },
            )
            // A confirmation of someone the labels file no longer names — merged away since — is
            // nobody's, and back among the unknown.
            val settled = faces.map { if (it.person != null && it.person !in known) it.copy(state = FaceState.UNKNOWN, person = null, group = Face.OTHER) else it }

            val unknown = settled.filter { it.state == FaceState.UNKNOWN }.groupBy { it.group ?: Face.OTHER }
            val groups = unknown.filterKeys { it != Face.OTHER }
                .map { (id, members) -> GroupSummary(id, members.size, members.maxBy { it.score }) }
                .filter { it.size >= Matching.MIN_GROUP }
            // A group whose faces have mostly been named since is too small to show on its own.
            val shown = groups.mapTo(mutableSetOf(), GroupSummary::id)
            val final = settled.map { if (it.state == FaceState.UNKNOWN && it.group !in shown) it.copy(group = Face.OTHER) else it }
            val others = final.filter { it.state == FaceState.UNKNOWN && it.group == Face.OTHER }
            val listed = groups.sortedWith(compareByDescending<GroupSummary> { it.size }.thenBy { it.id }) +
                listOfNotNull(others.maxByOrNull { it.score }?.let { GroupSummary(Face.OTHER, others.size, it) })
            return PeopleSnapshot(people, listed, final)
        }

        private fun IndexEntry.face(state: FaceState, person: Uuid?, group: Int?): Face = Face(
            id = faceId,
            albumId = albumId,
            photoId = photoId,
            box = box,
            score = score,
            state = state,
            person = person,
            similarity = if (state == FaceState.SUGGESTED) similarity else null,
            group = group,
        )
    }
}

/**
 * The people behind the viewer: what `sync` concluded, the labels file, and every decision made
 * about a face (§12).
 *
 * A port because the viewer's tests have no CLI cache to read and no library to write into; the
 * real one is [LocalPeople].
 */
public interface People {
    public fun read(): PeopleSnapshot

    public fun createPerson(name: String): Person
    public fun rename(person: Uuid, name: String)
    public fun merge(from: Uuid, into: Uuid)
    public fun confirm(faces: List<Face>, person: Uuid): Labels.Change
    public fun reject(faces: List<Face>, person: Uuid): Labels.Change
    public fun ignore(faces: List<Face>): Labels.Change
    public fun clear(faces: List<Face>): Labels.Change

    /** Ctrl+Z: a decision's change, taken back. */
    public fun revert(change: Labels.Change)
}

/** The CLI's `people_index.db` and the library's `.photos/people.db`. */
public class LocalPeople(
    cliCache: Path,
    libraryRoot: Path,
    drivers: SqlDrivers,
    /** When a photo was taken — the viewer's catalog knows, the index does not. */
    private val takenAt: (Uuid) -> Instant? = { null },
) : People {
    private val index = PeopleIndex(Path(cliCache, PeopleIndex.FILENAME), drivers)
    private val labels = Labels.at(libraryRoot, drivers)

    override fun read(): PeopleSnapshot = PeopleSnapshot.of(index.read(), labels.read(), takenAt)

    override fun createPerson(name: String): Person = labels.createPerson(name)
    override fun rename(person: Uuid, name: String): Unit = labels.rename(person, name)
    override fun merge(from: Uuid, into: Uuid): Unit = labels.merge(from, into)
    override fun confirm(faces: List<Face>, person: Uuid): Labels.Change = labels.confirm(faces.map(Face::ref), person)
    override fun reject(faces: List<Face>, person: Uuid): Labels.Change = labels.reject(faces.map(Face::ref), person)
    override fun ignore(faces: List<Face>): Labels.Change = labels.ignore(faces.map(Face::ref))
    override fun clear(faces: List<Face>): Labels.Change = labels.clear(faces.map(Face::ref))
    override fun revert(change: Labels.Change): Unit = labels.revert(change)
}

/**
 * A face's crop, as JPEG — cut from the original by the viewer and kept on disk (§12). Null when
 * the photo is not on this machine.
 */
public fun interface FaceCrops {
    public suspend fun crop(face: Face): ByteArray?
}
