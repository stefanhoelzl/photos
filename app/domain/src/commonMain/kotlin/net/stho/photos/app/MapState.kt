package net.stho.photos.app

import net.stho.photos.catalog.Album
import net.stho.photos.model.PhotoRow

/**
 * One level's map as the back stack remembers it (§6).
 *
 * Held in memory only: relaunching, or leaving the level, frames the map afresh.
 */
public data class MapView(
    /** The map is the representation on screen; false once toggled back to the list or grid. */
    val showing: Boolean = false,
    /** Null until first framed — a frame needs the viewport, and only the screen knows it. */
    val camera: MapCamera? = null,
    /**
     * Bumped each time the model moves the camera — a frame, a cluster tap — which the renderer
     * then animates to. Gestures report where they left the camera without bumping it, since the
     * renderer is already there.
     */
    val moves: Int = 0,
    /**
     * What the camera was framed on, for as long as it is still that automatic frame.
     *
     * A frame fits a viewport, and the first one is made before the screen has said how big it
     * is — a map narrower than the phone assumed opened with its pins past both edges. Until a
     * gesture or a tap takes the camera over, a different viewport refits the same points.
     */
    val framing: Framing? = null,
)

/** The points a frame fits, and the viewport it fitted them to. */
public data class Framing(val points: List<WorldPoint>, val width: Double, val height: Double) {
    /** The same frame, fitted to a [width]×[height] dp viewport. */
    public fun cameraFor(width: Double, height: Double): MapCamera = frame(points, width, height)
}

/** One thing the map places: an album on the album list's map, a photo on an album's. */
public sealed interface MapPin {
    public val point: WorldPoint

    public data class OfAlbum(val album: Album, override val point: WorldPoint) : MapPin

    /** [index] is the photo's position in its album's order, which is what the viewer opens at. */
    public data class OfPhoto(val photo: PhotoRow, val index: Int, override val point: WorldPoint) : MapPin
}

/** What the open map draws, built off the draw path when its points change. */
public data class MapUi(
    /** Indexed exactly as [clusters]' members are. */
    val pins: List<MapPin>,
    val clusters: ClusterIndex,
    /**
     * What the pins are counted against in the subtitle: every album that owns photos (or every
     * one matching the search), or the album's photos. The difference is what has no location.
     */
    val total: Int,
    /** Albums in one spot that no zoom separates, listed instead (§6). Null when closed. */
    val sheet: List<Album>? = null,
)
