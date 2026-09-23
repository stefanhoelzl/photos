package net.stho.photos.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import kotlin.time.Duration.Companion.milliseconds
import net.stho.photos.app.MapCamera
import net.stho.photos.app.MapLimits
import net.stho.photos.ui.screens.BaseMap
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * The basemap on a phone and in the desktop window: VersaTiles' vector tiles, drawn by MapLibre.
 *
 * It draws tiles and nothing else (§6). Pins and clusters are `:ui:shared`'s overlay, placed by the
 * shared tier's own projection, so what can be wrong about them is unit-tested and a headless
 * render still shows them. Rotation and tilt are off: the map is an index of places, and the
 * overlay's projection assumes a flat, north-up camera.
 *
 * Tiles are cached by MapLibre Native's own ambient cache at its default size, so an area looked
 * at before still draws offline; nothing here configures it (§6).
 */
public object MaplibreBaseMap : BaseMap {
    @Composable
    override fun Render(
        camera: MapCamera,
        moves: Int,
        onCamera: (camera: MapCamera, settled: Boolean) -> Unit,
        modifier: Modifier,
    ) {
        // The style follows the app's scheme, which follows the system's. Keyed, so a theme change
        // rebuilds the map at the camera the model last remembered rather than restyling in place.
        val style = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DARK_STYLE else LIGHT_STYLE
        key(style) {
            val state = rememberMapState(baseStyle = BaseStyle.Uri(style), initialCameraPosition = camera.position())
            val report by rememberUpdatedState(onCamera)

            // Only moves the model made are followed. The first frame is where the map starts, not
            // somewhere to fly to from the middle of the Atlantic.
            var followed by remember { mutableIntStateOf(moves) }
            LaunchedEffect(moves) {
                if (moves != followed) {
                    followed = moves
                    state.animateCameraPosition(camera.position(), MOVE_DURATION)
                }
            }
            LaunchedEffect(state) {
                snapshotFlow { state.cameraPosition to state.isCameraMoving }
                    .collect { (position, moving) -> report(position.camera(), !moving) }
            }

            MaplibreMap(
                modifier = modifier,
                state = state,
                cameraConstraints = CameraConstraints(
                    minZoom = MapLimits.MIN_ZOOM.toDouble(),
                    maxZoom = MapLimits.MAX_ZOOM.toDouble(),
                    maxPitch = 0.0,
                ),
                interactions = MapInteractions {
                    camera {
                        rotate { enabled = false }
                        tilt { enabled = false }
                    }
                },
                // No compass or scale bar: nothing rotates and nothing is measured. The attribution
                // stays, because OpenStreetMap's licence asks for it.
                overlay = { include(MapOverlay.AttributionOnly) },
            )
        }
    }

    /** VersaTiles' own hosted styles, under the names its public server still serves. */
    private const val LIGHT_STYLE = "https://tiles.versatiles.org/assets/styles/colorful/style.json"
    private const val DARK_STYLE = "https://tiles.versatiles.org/assets/styles/eclipse/style.json"

    private val MOVE_DURATION = 350.milliseconds
}

private fun MapCamera.position(): CameraPosition =
    CameraPosition(target = Position(longitude = longitude, latitude = latitude), zoom = zoom)

/** MapLibre reports longitudes past ±180 once a pan crosses the antimeridian; the tier's are not. */
private fun CameraPosition.camera(): MapCamera =
    MapCamera(target.latitude, ((target.longitude + 180) % 360 + 360) % 360 - 180, zoom)
