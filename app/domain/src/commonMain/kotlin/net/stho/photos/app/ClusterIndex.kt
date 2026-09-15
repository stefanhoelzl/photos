package net.stho.photos.app

import kotlin.math.floor

/**
 * Pins that would overlap, drawn as one: the indices of the points it holds, and where it sits.
 *
 * [members] are ascending, so for an album's photos the first is the earliest (§3's order).
 */
public data class Cluster(val members: List<Int>, val center: WorldPoint) {
    /** One point on its own: drawn as a pin rather than as a count. */
    public val isPin: Boolean get() = members.size == 1
}

/**
 * §6's clustering, worked out once for every whole zoom level.
 *
 * Supercluster's greedy radius merge, the rule MapLibre GL uses. The deepest level starts from
 * the points themselves; each shallower level merges the level below it, so a cluster at one
 * zoom is always a union of clusters at the next — zooming in only ever splits clusters, it
 * never regroups them. A cluster sits at its members' centroid, weighted by how many each holds.
 *
 * Built when the set of points changes, never while panning: the renderer only picks the level
 * for `floor(zoom)` (§6). That keeps the part that can be wrong away from the frame clock, and
 * is what makes it testable with nothing but numbers.
 */
public class ClusterIndex(
    points: List<WorldPoint>,
    /** Pins closer than this, in dp at the level's own zoom, merge. */
    radius: Double = MapLimits.CLUSTER_RADIUS,
    public val maxZoom: Int = MapLimits.MAX_ZOOM,
) {
    private val levels: List<List<Cluster>>

    /** `owners[z][point]` is the index, in `levels[z]`, of the cluster holding that point. */
    private val owners: List<IntArray>

    init {
        val built = arrayOfNulls<List<Cluster>>(maxZoom + 1)
        var current = points.mapIndexed { index, point -> Cluster(listOf(index), point) }
        for (zoom in maxZoom downTo 0) {
            current = merge(current, radius / Mercator.worldSize(zoom.toDouble()))
            built[zoom] = current
        }
        levels = built.map { requireNotNull(it) }
        owners = levels.map { level ->
            IntArray(points.size).also { owner ->
                level.forEachIndexed { index, cluster -> cluster.members.forEach { owner[it] = index } }
            }
        }
    }

    /** The level a camera at [zoom] draws: its whole part, clamped to what was built. */
    public fun level(zoom: Double): Int = floor(zoom).toInt().coerceIn(0, maxZoom)

    /** What a camera at [zoom] draws. */
    public fun at(zoom: Double): List<Cluster> = levels[level(zoom)]

    /**
     * The first level deeper than [level] at which [cluster] splits, or null when it never does.
     *
     * Null is §6's "cannot separate": its members are still one cluster at the deepest zoom, so
     * zooming would show the same circle closer up. The map offers a list instead.
     */
    public fun expansion(cluster: Cluster, level: Int): Int? {
        if (cluster.isPin) return null
        val first = cluster.members.first()
        for (zoom in (level + 1)..maxZoom) {
            if (levels[zoom][owners[zoom][first]].members.size < cluster.members.size) return zoom
        }
        return null
    }

    private companion object {
        /**
         * One pass of the greedy merge at a radius of [radius] world units.
         *
         * Neighbours are found through a grid of radius-sized cells, so each seed looks at nine
         * cells rather than at every other cluster. Seeds are taken in input order, which makes
         * the result deterministic for a given point order.
         */
        fun merge(input: List<Cluster>, radius: Double): List<Cluster> {
            if (input.size < 2) return input
            fun cell(value: Double): Int = floor(value / radius).toInt()
            fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

            val grid = HashMap<Long, MutableList<Int>>()
            input.forEachIndexed { index, cluster ->
                grid.getOrPut(key(cell(cluster.center.x), cell(cluster.center.y))) { mutableListOf() } += index
            }
            val taken = BooleanArray(input.size)
            val out = ArrayList<Cluster>()
            for (index in input.indices) {
                if (taken[index]) continue
                taken[index] = true
                val seed = input[index]
                val members = ArrayList(seed.members)
                var weight = seed.members.size.toDouble()
                var sumX = seed.center.x * weight
                var sumY = seed.center.y * weight
                val cx = cell(seed.center.x)
                val cy = cell(seed.center.y)
                for (gx in cx - 1..cx + 1) {
                    for (gy in cy - 1..cy + 1) {
                        for (other in grid[key(gx, gy)] ?: continue) {
                            if (taken[other]) continue
                            val candidate = input[other]
                            val dx = candidate.center.x - seed.center.x
                            val dy = candidate.center.y - seed.center.y
                            if (dx * dx + dy * dy > radius * radius) continue
                            taken[other] = true
                            members += candidate.members
                            val w = candidate.members.size.toDouble()
                            sumX += candidate.center.x * w
                            sumY += candidate.center.y * w
                            weight += w
                        }
                    }
                }
                out += if (members.size == seed.members.size) seed
                else Cluster(members.sorted(), WorldPoint(sumX / weight, sumY / weight))
            }
            return out
        }
    }
}
