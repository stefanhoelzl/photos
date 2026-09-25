package net.stho.photos.faces

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
 * [SUGGEST_AT] and [GROUP_AT] are measured on the library's own verdicts, [QUALITY_FLOOR] too.
 */
public object Matching {
    /**
     * On the [TOP_K] score. Measured on the library's own verdicts — 20,029 confirmed faces, each
     * against its person's other faces, and 6,808 rejected ones against the person they were
     * rejected as: at 0.52, 89% of real matches are suggested and 9% of rejected ones would be. The
     * closest-face score needed 0.57 for the same 89% and let 13% through; the first guess, 0.40 on
     * it, let 56% through. A size floor on top moved nothing by more than a point or two.
     */
    public const val SUGGEST_AT: Float = 0.52f

    /** How far ahead of the runner-up the best person has to be. */
    public const val MARGIN: Float = 0.05f

    /** Two faces at least this alike are within reach of each other for [Grouper]. Measured there. */
    public const val GROUP_AT: Float = 0.74f

    /** A group smaller than this is shown as "Other" rather than on its own. */
    public const val MIN_GROUP: Int = 3

    /**
     * The detector's confidence below which a face nobody has decided about is set aside: never
     * suggested, never grouped, left to its box on the photo. Many detections under it are not
     * faces at all — a blurred shape, a pattern — and their embeddings match people by accident.
     * Measured on the library's own verdicts: 5% of confirmed faces score under it, against 78% of
     * ignored ones. A verdict always stands, whatever the score.
     */
    public const val QUALITY_FLOOR: Float = 0.8f

    /** Whether a face without a verdict is worth suggesting or grouping, by confidence alone. */
    public fun trusted(face: DetectedFace): Boolean = Quality().trusted(face)

    /**
     * What a face has to be to take part: the detector confident of it, and its crop at least
     * [sharpnessFloor] sharp. A face measured before sharpness was (NaN) is judged on confidence.
     */
    public class Quality(public val sharpnessFloor: Float? = null) {
        public fun trusted(face: DetectedFace): Boolean =
            face.score >= QUALITY_FLOOR &&
                (sharpnessFloor == null || face.sharpness.isNaN() || face.sharpness >= sharpnessFloor)

        public companion object {
            /**
             * The floor, from the library's own confirmed faces: the sharpness [SHARP_PERCENTILE] of
             * them fall under. A blur threshold is a property of the camera, the compression and the
             * crop size together, so it is measured on what the person has already called a face
             * rather than guessed. Null — no sharpness gate — until there are enough to measure.
             */
            public fun from(confirmed: List<DetectedFace>): Quality {
                val measured = confirmed.map { it.sharpness }.filterNot(Float::isNaN).sorted()
                if (measured.size < MIN_TO_MEASURE) return Quality()
                return Quality(measured[(measured.size * SHARP_PERCENTILE).toInt()])
            }

            /** Confirmed faces under the floor: this share of what a person calls a face is set aside. */
            public const val SHARP_PERCENTILE: Float = 0.05f
            public const val MIN_TO_MEASURE: Int = 50
        }
    }

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
    public fun suggest(faces: List<IndexedFace>, resolved: Resolved): Map<Uuid, Suggestion> =
        weigh(faces, resolved).suggestions

    /** What [weigh] concludes: the suggestions, and every face's likeliest people. */
    public class Weighed(
        public val suggestions: Map<Uuid, Suggestion>,
        /**
         * Face → up to [CANDIDATES] people, most alike first — what a naming menu lists first
         * (§12). Every face but an ignored one, confirmed ones included, since a confirmation can
         * be wrong; a person the face was rejected as is left out.
         */
        public val candidates: Map<Uuid, List<Suggestion>>,
    )

    /** [Weigher.weigh] for every face, on one thread — the tests' and a small library's way. */
    public fun weigh(faces: List<IndexedFace>, resolved: Resolved): Weighed {
        val weigher = Weigher(faces, resolved)
        val suggestions = mutableMapOf<Uuid, Suggestion>()
        val candidates = mutableMapOf<Uuid, List<Suggestion>>()
        for (face in faces) {
            val weight = weigher.weigh(face) ?: continue
            candidates[face.id] = weight.candidates
            weight.suggestion?.let { suggestions[face.id] = it }
        }
        return Weighed(suggestions, candidates)
    }

    /** One face's weighing: its likeliest people, and the suggestion, when the rule makes one. */
    public class Weight(public val candidates: List<Suggestion>, public val suggestion: Suggestion?)

    /**
     * Each face against every person's confirmed faces: the best similarity per person is what
     * both [suggest]'s rule and the naming menus' order are made from, so it is computed a single
     * time for both.
     *
     * Built once, then [weigh] is independent per face and touches nothing shared — so the faces of
     * a large library can be weighed on as many threads as there are, which is what `sync` does.
     * Each person's references are packed into one array, since weighing is almost all dot
     * products against them.
     */
    public class Weigher(
        faces: List<IndexedFace>,
        private val resolved: Resolved,
        private val quality: Quality = Quality(),
    ) {
        private class Packed(val person: Uuid, val vectors: FloatArray, val count: Int, val dimensions: Int) {
            fun best(embedding: FloatArray): Float = closest(embedding, 1)

            /** The mean similarity of the [k] references most like [embedding]. */
            fun closest(embedding: FloatArray, k: Int): Float {
                val top = FloatArray(minOf(k, count)) { -2f }
                for (r in 0 until count) {
                    var dot = 0f
                    val base = r * dimensions
                    for (i in 0 until dimensions) dot += vectors[base + i] * embedding[i]
                    // A handful of slots, kept smallest-first: replace the smallest and re-sort.
                    if (dot > top[0]) {
                        top[0] = dot
                        var j = 0
                        while (j + 1 < top.size && top[j] > top[j + 1]) {
                            val t = top[j]; top[j] = top[j + 1]; top[j + 1] = t; j++
                        }
                    }
                }
                return top.sum() / top.size
            }
        }

        private fun pack(groups: Map<Uuid, List<FloatArray>>): Map<Uuid, Packed> = groups.mapValues { (person, list) ->
            val dimensions = list.first().size
            val vectors = FloatArray(list.size * dimensions)
            list.forEachIndexed { r, v -> v.copyInto(vectors, r * dimensions) }
            Packed(person, vectors, list.size, dimensions)
        }

        private val references: List<Packed>
        private val negatives: Map<Uuid, Packed>

        init {
            val byId = faces.associateBy(IndexedFace::id)
            val confirmed = mutableMapOf<Uuid, MutableList<DetectedFace>>()
            for ((face, person) in resolved.confirmed) {
                val detected = byId[face]?.face ?: continue
                confirmed.getOrPut(person) { mutableListOf() } += detected
            }
            // A blurred confirmation is a poor reference for anyone — unless it is all there is.
            references = pack(
                confirmed.mapValues { (_, list) ->
                    val sharp = list.filter(quality::trusted).ifEmpty { list }
                    distinct(sharp.sortedByDescending { it.score }.map { it.embedding })
                },
            ).values.toList()
            val rejected = mutableMapOf<Uuid, MutableList<FloatArray>>()
            for ((face, people) in resolved.rejected) {
                val embedding = byId[face]?.face?.embedding ?: continue
                for (person in people) rejected.getOrPut(person) { mutableListOf() } += embedding
            }
            negatives = pack(rejected)
        }

        /** Null for an ignored face, or when nobody has been confirmed yet. */
        public fun weigh(face: IndexedFace): Weight? {
            if (references.isEmpty() || face.id in resolved.ignored) return null
            val turnedDown = resolved.rejected[face.id].orEmpty()
            val embedding = face.face.embedding
            val scored = references.map { Suggestion(it.person, it.closest(embedding, TOP_K)) }.sortedByDescending { it.similarity }
            val candidates = scored.filter { it.person !in turnedDown }.take(CANDIDATES)

            if (face.id in resolved.confirmed || !quality.trusted(face.face)) return Weight(candidates, null)
            val best = scored.first()
            val second = scored.getOrNull(1)?.similarity ?: -1f
            if (best.similarity < SUGGEST_AT || best.similarity - second < MARGIN || best.person in turnedDown) {
                return Weight(candidates, null)
            }
            val against = negatives[best.person]?.best(embedding)
            if (against != null && against >= best.similarity) return Weight(candidates, null)
            return Weight(candidates, best)
        }
    }

    /**
     * A person's references with near-copies dropped: a burst of one moment is one reference, not
     * five, so it cannot fill [TOP_K] alone. Best-detected first, so the kept copy is the clearest.
     */
    private fun distinct(embeddings: List<FloatArray>): List<FloatArray> {
        val kept = mutableListOf<FloatArray>()
        for (e in embeddings) if (kept.none { it.cosine(e) >= DUPLICATE }) kept += e
        return kept
    }

    /**
     * How a face is scored against a person: the mean similarity of the [TOP_K] closest of their
     * references rather than the single closest. One near-identical confirmed photo — the same
     * burst, the same angle and light — is then not enough on its own. Measured on the library's
     * verdicts: separating confirmed from rejected faces, AUC 0.955 against 0.940 for the closest
     * alone; beyond 5 nothing more.
     */
    public const val TOP_K: Int = 5

    /** References at least this alike are copies of one moment (§12). */
    public const val DUPLICATE: Float = 0.9f

    /** How many people a face's naming menu is ordered by; the rest follow by name. */
    public const val CANDIDATES: Int = 5

    /**
     * Groups the faces nobody is suggested for — §12's cold start: face → group number, 1 for the
     * largest; a face in no group is absent ("Other").
     *
     * Density rather than distance alone, as DBSCAN has it: a face with at least [CORE] faces
     * within [GROUP_AT] (itself included) is a core, cores within reach of each other are one group,
     * and a face near a core joins its group without extending it. A lone look-alike cannot chain two
     * people together, the way a greedy pass around centres did at the old, looser threshold.
     *
     * Measured on the library's confirmed faces, whose people are known: at 0.74 the groups are 96%
     * one person and hold 40% of the faces; at 0.45 — the first guess — every face chained into a
     * single group. The rest wait in "Other", where suggestions still reach them.
     *
     * [neighbours] is the costly half — every face against every other — and is independent per
     * face, so the caller can spread it over threads; [cluster] then walks it on one.
     */
    public class Grouper(private val faces: List<IndexedFace>) {
        private val order = faces.sortedWith(compareByDescending<IndexedFace> { it.face.score }.thenBy { it.id.toString() })
        private val dimensions = order.firstOrNull()?.face?.embedding?.size ?: 0
        private val packed = FloatArray(order.size * dimensions).also { all ->
            order.forEachIndexed { i, face -> face.face.embedding.copyInto(all, i * dimensions) }
        }

        public val size: Int get() = order.size

        /** For each face in [range], the faces within [GROUP_AT] of it, itself included. */
        public fun neighbours(range: IntRange): List<IntArray> = range.map { i ->
            val near = ArrayList<Int>()
            val a = i * dimensions
            for (j in order.indices) {
                var dot = 0f
                val b = j * dimensions
                for (k in 0 until dimensions) dot += packed[a + k] * packed[b + k]
                if (dot >= GROUP_AT) near += j
            }
            near.toIntArray()
        }

        /** The groups, from every face's [neighbours] in order. */
        public fun cluster(neighbours: List<IntArray>): Map<Uuid, Int> {
            val core = BooleanArray(order.size) { neighbours[it].size >= CORE }
            val label = IntArray(order.size) { -1 }
            var groups = 0
            for (seed in order.indices) {
                if (label[seed] != -1 || !core[seed]) continue
                val stack = ArrayDeque<Int>().apply { addLast(seed) }
                label[seed] = groups
                while (stack.isNotEmpty()) {
                    val j = stack.removeLast()
                    if (!core[j]) continue
                    for (k in neighbours[j]) if (label[k] == -1) {
                        label[k] = groups
                        stack.addLast(k)
                    }
                }
                groups++
            }
            val members = order.indices.filter { label[it] >= 0 }.groupBy { label[it] }.values
                .filter { it.size >= MIN_GROUP }
                .sortedWith(compareByDescending<List<Int>> { it.size }.thenBy { order[it.first()].id.toString() })
            val result = mutableMapOf<Uuid, Int>()
            for ((number, group) in members.withIndex()) for (i in group) result[order[i].id] = number + 1
            return result
        }
    }

    /** [Grouper] on one thread — the tests' and a small library's way. */
    public fun group(faces: List<IndexedFace>): Map<Uuid, Int> {
        val grouper = Grouper(faces)
        if (grouper.size == 0) return emptyMap()
        return grouper.cluster(grouper.neighbours(0 until grouper.size))
    }

    /** A face with this many faces within reach, itself included, is a group's core. */
    public const val CORE: Int = 5

}
