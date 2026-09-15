package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import net.stho.photos.app.MapCamera

/**
 * The tiles under the map's pins — the one part of the map no shared code draws (§6).
 *
 * A UI-interop port exactly like [VideoSurface]: MapLibre satisfies it in `:app:map`, and each
 * root installs that only where it has a window to present into. Everything else renders the
 * stand-in, which is what keeps `/screenshot` and `:tests:app` free of a native renderer.
 *
 * The camera goes both ways. [camera] and [moves] are the model's: when [moves] changes the
 * model moved the camera, and the renderer follows. [onCamera] is the renderer's: where the
 * camera is now, with `settled` once it has stopped — every frame of a gesture positions the
 * pins, and only the settled position is worth remembering.
 */
public fun interface BaseMap {
    @Composable
    public fun Render(
        camera: MapCamera,
        moves: Int,
        onCamera: (camera: MapCamera, settled: Boolean) -> Unit,
        modifier: Modifier,
    )
}

/** Provided by a root with a window. The default is the plain stand-in, for the reason [LocalVideoSurface]'s is. */
public val LocalBaseMap: ProvidableCompositionLocal<BaseMap> = staticCompositionLocalOf { PlainBaseMap }

/**
 * A basemap with no map on it: a plain ground that still pans and zooms.
 *
 * Drag pans; the scroll wheel zooms by half a level. So the pins, the clusters and every tap on
 * them can be reviewed and driven without tiles, and a headless frame shows exactly what the
 * shared tier decided.
 */
public object PlainBaseMap : BaseMap {
    @Composable
    override fun Render(
        camera: MapCamera,
        moves: Int,
        onCamera: (camera: MapCamera, settled: Boolean) -> Unit,
        modifier: Modifier,
    ) {
        var live by remember { mutableStateOf(camera) }
        var followed by remember { mutableIntStateOf(moves) }
        val report by rememberUpdatedState(onCamera)
        LaunchedEffect(moves) {
            if (moves != followed) {
                followed = moves
                live = camera
                report(camera, true)
            }
        }
        Box(
            modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { report(live, true) }) { change, drag ->
                        change.consume()
                        live = live.panned((drag.x / density).toDouble(), (drag.y / density).toDouble())
                        report(live, false)
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: continue
                            if (delta == 0f) continue
                            // Away from the reader is closer, as on every desktop map (and the grid).
                            live = live.zoomedBy(if (delta < 0f) 0.5 else -0.5)
                            report(live, true)
                        }
                    }
                },
        )
    }
}
