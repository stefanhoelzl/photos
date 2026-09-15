package net.stho.photos.app

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A place on Web Mercator's unit square: [x] runs west to east and [y] north to south, both 0..1.
 *
 * The map's shared tier works here rather than in degrees, because clustering, framing and placing
 * a pin are all distances, and Mercator is what the basemap draws in — so a distance measured
 * here is the distance a person sees (§6).
 */
public data class WorldPoint(val x: Double, val y: Double)

/** A point in the map's viewport, in dp from its top-left corner. */
public data class ScreenPoint(val x: Double, val y: Double)

/**
 * Where the map is looking. Bearing and tilt are fixed at zero: the map is an index of where
 * albums were taken, and a rotated one only makes that harder to read.
 */
public data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Double) {
    public val center: WorldPoint get() = Mercator.project(latitude, longitude)

    /** Where [point] lands in a [width]×[height] dp viewport. */
    public fun toScreen(point: WorldPoint, width: Double, height: Double): ScreenPoint {
        val world = Mercator.worldSize(zoom)
        val here = center
        // The nearer copy of the world, so a pin just across the antimeridian is drawn beside
        // the centre rather than a whole world away.
        var dx = point.x - here.x
        if (dx > 0.5) dx -= 1.0 else if (dx < -0.5) dx += 1.0
        return ScreenPoint(width / 2 + dx * world, height / 2 + (point.y - here.y) * world)
    }

    /** The camera after the content was dragged by ([dx], [dy]) dp — the stand-in basemap's pan. */
    public fun panned(dx: Double, dy: Double): MapCamera {
        val world = Mercator.worldSize(zoom)
        val here = center
        var x = (here.x - dx / world) % 1.0
        if (x < 0) x += 1.0
        return Mercator.camera(WorldPoint(x, (here.y - dy / world).coerceIn(0.0, 1.0)), zoom)
    }

    /** The same centre, [delta] zoom levels closer, kept inside [MapLimits]. */
    public fun zoomedBy(delta: Double): MapCamera =
        copy(zoom = (zoom + delta).coerceIn(MapLimits.MIN_ZOOM.toDouble(), MapLimits.MAX_ZOOM.toDouble()))
}

public object Mercator {
    /**
     * The world's width at zoom 0, in dp. 512 is MapLibre's tile size, so a zoom here is exactly
     * the zoom the basemap renders at, and a pin placed with it lands where the basemap draws
     * the place.
     */
    public const val TILE: Double = 512.0

    /** Where Web Mercator stops: the latitude at which the unit square is square. */
    public const val MAX_LATITUDE: Double = 85.05112878

    public fun worldSize(zoom: Double): Double = TILE * 2.0.pow(zoom)

    public fun project(latitude: Double, longitude: Double): WorldPoint {
        val phi = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / 180
        return WorldPoint(
            x = (longitude + 180) / 360,
            y = (1 - ln(tan(PI / 4 + phi / 2)) / PI) / 2,
        )
    }

    public fun latitudeOf(point: WorldPoint): Double = atan(sinh(PI * (1 - 2 * point.y))) * 180 / PI

    public fun longitudeOf(point: WorldPoint): Double = point.x * 360 - 180

    public fun camera(center: WorldPoint, zoom: Double): MapCamera =
        MapCamera(latitudeOf(center), longitudeOf(center), zoom)
}

public object MapLimits {
    /** The whole world. */
    public const val MIN_ZOOM: Int = 0

    /**
     * Street level. VersaTiles' data stops at 14 and MapLibre overzooms its last level beyond
     * that, which is plenty for telling two albums in one town apart.
     */
    public const val MAX_ZOOM: Int = 18

    /** How close a frame goes on a single place: the town, not the doorstep. */
    public const val FIT_MAX_ZOOM: Double = 14.0

    /** Kept clear around what a frame fits, so a pin at its edge is not cut in half. */
    public const val FIT_PADDING: Double = 48.0

    /** Pins closer than this merge — the 38pt pin plus a little air, as the mockup draws them. */
    public const val CLUSTER_RADIUS: Double = 44.0

    /** A camera that shows everything, for a map with nothing on it. */
    public val WORLD: MapCamera = MapCamera(0.0, 0.0, 0.0)
}

/**
 * The camera that shows every one of [points] in a [width]×[height] dp viewport.
 *
 * Centred on their bounding box, as close as the box allows but never closer than
 * [MapLimits.FIT_MAX_ZOOM]: a single album framed at street level shows nothing around it.
 * Wrong across the antimeridian, as §3's centroid is — no album in this library spans it.
 */
public fun frame(points: List<WorldPoint>, width: Double, height: Double): MapCamera {
    if (points.isEmpty()) return MapLimits.WORLD
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val usableWidth = max(width - 2 * MapLimits.FIT_PADDING, 1.0)
    val usableHeight = max(height - 2 * MapLimits.FIT_PADDING, 1.0)
    val byWidth = if (maxX > minX) log2(usableWidth / ((maxX - minX) * Mercator.TILE)) else Double.POSITIVE_INFINITY
    val byHeight = if (maxY > minY) log2(usableHeight / ((maxY - minY) * Mercator.TILE)) else Double.POSITIVE_INFINITY
    val zoom = min(min(byWidth, byHeight), MapLimits.FIT_MAX_ZOOM)
        .coerceAtLeast(MapLimits.MIN_ZOOM.toDouble())
    return Mercator.camera(WorldPoint((minX + maxX) / 2, (minY + maxY) / 2), zoom)
}
