package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
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
 */
@Composable
public fun PhotoGrid(
    photos: List<PhotoRow>,
    thumbnails: Map<Uuid, ByteArray>,
    columns: Int,
    onDensity: (closer: Boolean) -> Unit,
    onOpen: (index: Int) -> Unit,
) {
    if (photos.isEmpty()) {
        EmptyState("No photos in this album")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp).densityGesture(onDensity),
    ) {
        itemsIndexed(photos, key = { _, photo -> photo.id.toString() }) { index, photo ->
            Tile(thumbnails[photo.id]) { onOpen(index) }
        }
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
private fun Tile(jpeg: ByteArray?, onOpen: () -> Unit) {
    Box(
        Modifier.padding(1.dp)
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onOpen),
    ) {
        if (jpeg != null) Thumbnail(jpeg, Modifier.fillMaxSize())
    }
}
