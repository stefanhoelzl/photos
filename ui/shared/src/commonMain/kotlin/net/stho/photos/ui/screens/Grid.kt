package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import net.stho.photos.app.Scroll
import net.stho.photos.model.PhotoRow

/**
 * An album's photos (§6's central screen).
 *
 * Thumbnails come from the album's pack, already on disk — so the grid is a **pure local read**
 * at every density: no network, no per-tile loading state, and it works offline. That is also
 * why §5's open question about 2-column sharpness resolved to upscaling these same 256px
 * thumbs rather than fetching previews.
 *
 * While the pack is still downloading the tiles are placeholders, one per photo. The count is
 * what distinguishes this from §10's zero-photo album, which shows no tiles at all.
 *
 * Opened again — back from the viewer, or from the album's map — it stands where [scroll] says it
 * was left, with the photo the viewer was last on in view.
 */
@Composable
public fun PhotoGrid(
    photos: List<PhotoRow>,
    thumbnails: Map<Uuid, ByteArray>,
    columns: Int,
    scroll: Scroll?,
    onScrolled: (key: String?, index: Int, offset: Int) -> Unit,
    onDensity: (closer: Boolean) -> Unit,
    onOpen: (index: Int) -> Unit,
    /** The tile the arrow keys are on, outlined in the active colour (§11); null when none is. */
    focused: Int? = null,
    /** The desktop's scroll bar, drawn over the grid's right edge; the phone passes none. */
    scrollbar: (@Composable BoxScope.(LazyGridState) -> Unit)? = null,
) {
    if (photos.isEmpty()) {
        EmptyState("No photos in this album")
        return
    }
    val grid = rememberLazyGridState()
    val current by rememberUpdatedState(photos)
    val keys = remember { derivedStateOf { current.map { it.id.toString() } } }
    val report by rememberUpdatedState(onScrolled)
    // Once on arrival, and again whenever the model moves the grid; scrolls of its own are reported.
    LaunchedEffect(scroll?.moves) { grid.follow(scroll, keys) { key, index, offset -> report(key, index, offset) } }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp).densityGesture(onDensity),
        ) {
            itemsIndexed(photos, key = { _, photo -> photo.id.toString() }) { index, photo ->
                Tile(photo, thumbnails[photo.id], focused = index == focused) { onOpen(index) }
            }
        }
        scrollbar?.invoke(this, grid)
    }
}

/**
 * Ctrl+scroll, the desktop's pinch.
 *
 * A scroll *away* from the reader means "closer", which is the direction every desktop map and
 * image viewer uses, so the gesture matches the pinch it stands in for.
 */
private fun Modifier.densityGesture(onDensity: (Boolean) -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                if (!event.keyboardModifiers.isCtrlPressed) continue
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: continue
                if (delta != 0f) onDensity(delta < 0f)
            }
        }
    },
)

/**
 * One square tile.
 *
 * §5's thumbnails are a square centre crop precisely so every consumer can fill its box: the
 * grid, the 54pt album cover, the map pin, the filmstrip. Nothing here fits or letterboxes.
 */
@Composable
private fun Tile(photo: PhotoRow, jpeg: ByteArray?, focused: Boolean, onOpen: () -> Unit) {
    Box(
        Modifier.padding(1.dp)
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onOpen),
    ) {
        if (jpeg != null) Thumbnail(jpeg, Modifier.fillMaxSize())
        TileMark(photo.mediaType, Modifier.align(Alignment.BottomStart).padding(4.dp))
        // Over the photograph, not around it: the grid has no gutter to draw a ring in.
        if (focused) Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colorScheme.primary))
    }
}
