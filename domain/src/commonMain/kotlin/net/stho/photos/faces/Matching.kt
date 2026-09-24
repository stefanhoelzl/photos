package net.stho.photos.faces

import kotlin.math.sqrt
import kotlin.uuid.Uuid

/** A face the matcher works on: where it is, and its embedding. */
public class IndexedFace(
    public val id: Uuid,
    public val albumId: Uuid,
    public val photoId: Uuid,
    public val face: DetectedFace,
)

/** What the verdicts say about each face, once each verdict has found its face. */
public class Resolved(
    /** Face → the person it is confirmed as. */
    public val confirmed: Map<Uuid, Uuid>,
    public val ignored: Set<Uuid>,
    /** Face → the people it has been rejected as. */
    public val rejected: Map<Uuid, Set<Uuid>>,
)

public class Suggestion(public val person: Uuid, public val similarity: Float)

/**
 * §12's pre-labelling: which person each face probably is, and which unknown faces belong
 * together.
 *
 * Nothing here is learned. Every confirmation is one more reference photograph of that person, and
 * a face is compared with each person's references directly — nearest neighbour, so a child is
 * matched against the photographs of the same child at the nearest age rather than against an
 * average of every age at once.
 *
 * The thresholds are starting points, not findings. SFace's own is 0.363 for "same person"; the
 * suggestion threshold sits above it because a wrong suggestion costs a click, and the grouping
 * threshold above that because a mixed group costs several. §12 defers tuning them to the
 * evaluation against confirmed faces.
 */
public object Matching {
    public const val SUGGEST_AT: Float = 0.40f

    /** How far ahead of the runner-up the best person has to be. */
    public const val MARGIN: Float = 0.05f

    public const val GROUP_AT: Float = 0.45f

    /** A group smaller than this is shown as "Other" rather than on its own. */
    public const val MIN_GROUP: Int = 3

    /**
     * Gives each verdict its face: on the same photo, the face whose box overlaps it most, and at
     * least [Labels.SAME_FACE_OVERLAP]. A verdict whose face is gone — the photo deleted, or a new
     * model that no longer finds it — attaches to nothing and waits.
     */
    public fun resolve(faces: List<IndexedFace>, verdicts: List<Verdict>): Resolved {
        val byPhoto = faces.groupBy(IndexedFace::photoId)
        val confirmed = mutableMapOf<Uuid, Uuid>()
        val ignored = mutableSetOf<Uuid>()
        val rejected = mutableMapOf<Uuid, MutableSet<Uuid>>()
        // Oldest first, so a later decision about the same face is the one that stands.
        for (verdict in verdicts.sortedBy { it.decidedAt }) {
            val face = byPhoto[verdict.photoId]
                ?.map { it to it.face.box.overlap(verdict.box) }
                ?.filter { (_, overlap) -> overlap >= Labels.SAME_FACE_OVERLAP }
                ?.maxByOrNull { (_, overlap) -> overlap }
                ?.first ?: continue
            when (verdict.kind) {
                VerdictKind.CONFIRMED -> {
                    confirmed[face.id] = verdict.personId ?: continue
                    ignored -= face.id
                }
                VerdictKind.IGNORED -> {
                    ignored += face.id
                    confirmed -= face.id
                }
                VerdictKind.REJECTED -> rejected.getOrPut(face.id) { mutableSetOf() } += verdict.personId ?: continue
            }
        }
        return Resolved(confirmed, ignored, rejected)
    }

    /**
     * A suggestion for every face with neither a confirmation nor an ignore.
     *
     * For each person, the best similarity to any face confirmed as them. The top person is
     * suggested when that is at least [SUGGEST_AT], at least [MARGIN] ahead of the next person,
     * and the face has not been rejected as them. Rejections count against their neighbours too:
     * if the face is at least as close to a face rejected as that person as it is to any confirmed
     * one, the resemblance is the kind already turned down once.
     */
    public fun suggest(faces: List<IndexedFace>, resolved: Resolved): Map<Uuid, Suggestion> {
        val byId = faces.associateBy(IndexedFace::id)
        val references = mutableMapOf<Uuid, MutableList<FloatArray>>()
        for ((face, person) in resolved.confirmed) {
            val embedding = byId[face]?.face?.embedding ?: continue
            references.getOrPut(person) { mutableListOf() } += embedding
        }
        if (references.isEmpty()) return emptyMap()
        val negatives = mutableMapOf<Uuid, MutableList<FloatArray>>()
        for ((face, people) in resolved.rejected) {
            val embedding = byId[face]?.face?.embedding ?: continue
            for (person in people) negatives.getOrPut(person) { mutableListOf() } += embedding
        }

        val suggestions = mutableMapOf<Uuid, Suggestion>()
        for (face in faces) {
            if (face.id in resolved.confirmed || face.id in resolved.ignored) continue
            val turnedDown = resolved.rejected[face.id].orEmpty()
            var best: Uuid? = null
            var bestSimilarity = -1f
            var second = -1f
            for ((person, embeddings) in references) {
                val similarity = embeddings.maxOf { face.face.embedding.cosine(it) }
                if (similarity > bestSimilarity) {
                    second = bestSimilarity
                    bestSimilarity = similarity
                    best = person
                } else if (similarity > second) {
                    second = similarity
                }
            }
            val person = best ?: continue
            if (bestSimilarity < SUGGEST_AT || bestSimilarity - second < MARGIN || person in turnedDown) continue
            val against = negatives[person]?.maxOf { face.face.embedding.cosine(it) }
            if (against != null && against >= bestSimilarity) continue
            suggestions[face.id] = Suggestion(person, bestSimilarity)
        }
        return suggestions
    }

    /**
     * Groups the faces nobody is suggested for — §12's cold start. Face → group number, 1 for the
     * largest group; a face in a group smaller than [MIN_GROUP] is absent ("Other").
     *
     * [previous] is the last sync's grouping. Its groups are kept — less the faces named since —
     * and only faces it did not place are placed now, into the group whose centre they are most
     * like or into a new one. So a sync after a labelling session regroups nothing, and the groups
     * a person is working through do not reshuffle under them between syncs. Without a previous
     * grouping — the first sync, or a new model — every face is placed, and groups whose centres
     * are alike are then merged.
     *
     * Greedy centroid grouping rather than §12's first choice, average-linkage agglomeration, which
     * holds every pair in memory: billions, for a first sync's tens of thousands of unknown faces.
     * Deterministic: faces are placed best-detected first, ties by id.
     */
    public fun group(
        faces: List<IndexedFace>,
        previous: Map<Uuid, Int> = emptyMap(),
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<Uuid, Int> {
        val dimensions = faces.firstOrNull()?.face?.embedding?.size ?: return emptyMap()
        val kept = mutableMapOf<Int, Group>()
        val unplaced = mutableListOf<IndexedFace>()
        for (face in faces) {
            val was = previous[face.id]
            if (was == null) unplaced += face
            else kept.getOrPut(was) { Group(dimensions) }.add(face.id, face.face.embedding)
        }
        val groups = kept.entries.sortedBy { it.key }.mapTo(mutableListOf()) { it.value }

        val order = unplaced.sortedWith(compareByDescending<IndexedFace> { it.face.score }.thenBy { it.id.toString() })
        for ((done, face) in order.withIndex()) {
            if (done % PROGRESS_EVERY == 0) progress(done, order.size)
            val embedding = face.face.embedding
            var best: Group? = null
            var bestSimilarity = GROUP_AT
            for (group in groups) {
                val similarity = group.similarity(embedding)
                if (similarity >= bestSimilarity) {
                    bestSimilarity = similarity
                    best = group
                }
            }
            (best ?: Group(dimensions).also { groups += it }).add(face.id, embedding)
        }

        if (previous.isEmpty()) merge(groups)

        val numbered = groups.filter { it.members.size >= MIN_GROUP }
            .sortedWith(compareByDescending<Group> { it.members.size }.thenBy { it.members.first().toString() })
        val result = mutableMapOf<Uuid, Int>()
        for ((index, group) in numbered.withIndex()) {
            for (member in group.members) result[member] = index + 1
        }
        return result
    }

    /**
     * Sweeps until one merges nothing. A group absorbs every later group alike to it in the same
     * sweep, so a sweep is quadratic in groups rather than each merge starting over. A pair's
     * similarity is one dot product of cached unit centres.
     */
    private fun merge(groups: MutableList<Group>) {
        var merged = true
        while (merged) {
            merged = false
            var i = 0
            while (i < groups.size) {
                var j = i + 1
                while (j < groups.size) {
                    if (groups[i].centre().cosine(groups[j].centre()) >= GROUP_AT) {
                        groups[i].absorb(groups.removeAt(j))
                        merged = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }
    }

    private const val PROGRESS_EVERY = 500

    /**
     * A group's members and the sum of their embeddings. The sum's length is cached, so comparing a
     * face with the group is one dot product; its unit centre is cached until the group changes.
     */
    private class Group(dimensions: Int) {
        val members = mutableListOf<Uuid>()
        private val sum = FloatArray(dimensions)
        private var length = 0f
        private var unit: FloatArray? = null

        fun add(id: Uuid, embedding: FloatArray) {
            members += id
            for (i in sum.indices) sum[i] += embedding[i]
            changed()
        }

        fun absorb(other: Group) {
            members += other.members
            for (i in sum.indices) sum[i] += other.sum[i]
            changed()
        }

        private fun changed() {
            var squares = 0f
            for (v in sum) squares += v * v
            length = sqrt(squares)
            unit = null
        }

        fun centre(): FloatArray = unit ?: FloatArray(sum.size) { if (length > 0f) sum[it] / length else 0f }.also { unit = it }

        fun similarity(embedding: FloatArray): Float {
            if (length <= 0f) return 0f
            var dot = 0f
            for (i in sum.indices) dot += sum[i] * embedding[i]
            return dot / length
        }
    }
}
