package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.uuid.Uuid
import net.stho.photos.app.AppUi
import net.stho.photos.app.Cluster
import net.stho.photos.app.MapCamera
import net.stho.photos.app.MapPin
import net.stho.photos.app.MapUi
import net.stho.photos.app.ScreenPoint
import net.stho.photos.app.Thumbnails
import net.stho.photos.catalog.Album

/**
 * A level drawn as its map: the album list's, a container's, or an album's (§6).
 *
 * The basemap is whatever the root installed ([LocalBaseMap]); the pins and clusters above it
 * are drawn here, placed by the shared tier's projection from the camera the basemap reports.
 * Which clusters exist was decided off the draw path, when the points changed — this only picks
 * the level for the camera's zoom and places what falls on screen.
 */
@Composable
internal fun MapScreen(
    ui: AppUi,
    thumbnails: Thumbnails,
    arrivals: Int,
    contents: (Album) -> String,
    onViewport: (width: Double, height: Double) -> Unit,
    onCameraMoved: (MapCamera) -> Unit,
    onTap: (Cluster) -> Unit,
    onOpenFromSheet: (Album) -> Unit,
    onDismissSheet: () -> Unit,
) {
    val view = ui.stack.map
    val map = ui.map
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val width = maxWidth.value.toDouble()
        val height = maxHeight.value.toDouble()
        LaunchedEffect(width, height) { onViewport(width, height) }

        val camera = view?.camera
        if (camera == null) {
            // Not framed yet: the first frame needs the pins, which are still being clustered.
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer))
        } else {
            // Framed before this screen reported its size, the camera fits some other viewport.
            // Until the model refits it, draw the same frame fitted to this one — so even a single
            // offscreen render, which never sees the report land, shows the pins where they belong.
            val shown = view.framing
                ?.takeIf { it.width != width || it.height != height }
                ?.cameraFor(width, height)
                ?: camera
            // Where the basemap is right now, which during a gesture is ahead of anything the
            // model has been told. The pins follow this, frame by frame.
            var live by remember { mutableStateOf(shown) }
            LocalBaseMap.current.Render(
                camera = shown,
                moves = view.moves,
                onCamera = { now, settled ->
                    live = now
                    if (settled) onCameraMoved(now)
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (map != null) PinLayer(map, live, width, height, ui.thumbnails, thumbnails, arrivals, onTap)
        }

        map?.sheet?.let { albums ->
            SpotSheet(albums, thumbnails, arrivals, contents, onOpenFromSheet, onDismissSheet)
        }
    }
}

@Composable
private fun PinLayer(
    map: MapUi,
    camera: MapCamera,
    width: Double,
    height: Double,
    /** The open album's thumbnails, for its photos' pins. */
    photos: Map<Uuid, ByteArray>,
    thumbnails: Thumbnails,
    arrivals: Int,
    onTap: (Cluster) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        for (cluster in map.clusters.at(camera.zoom)) {
            val at = camera.toScreen(cluster.center, width, height)
            // A pin's label reaches past its point, so a little beyond the edge is still drawn.
            if (at.x < -OFFSCREEN || at.x > width + OFFSCREEN || at.y < -OFFSCREEN || at.y > height + OFFSCREEN) continue
            key(cluster.members.first(), cluster.members.size) {
                if (cluster.isPin) {
                    when (val pin = map.pins[cluster.members.single()]) {
                        is MapPin.OfAlbum -> AlbumPin(pin.album, thumbnails, arrivals, Modifier.anchoredAt(at, PIN / 2)) { onTap(cluster) }
                        is MapPin.OfPhoto -> PhotoPin(photos[pin.photo.id], Modifier.anchoredAt(at, PIN / 2)) { onTap(cluster) }
                    }
                } else {
                    ClusterMark(cluster.members.size, Modifier.anchoredAt(at, null)) { onTap(cluster) }
                }
            }
        }
    }
}

/**
 * Places a mark so the point [anchorY] down from its top sits on [at] — the thumbnail's centre
 * for a pin, whose label hangs below the place rather than covering it; the middle otherwise.
 */
private fun Modifier.anchoredAt(at: ScreenPoint, anchorY: Dp?): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val y = anchorY?.roundToPx() ?: (placeable.height / 2)
    layout(placeable.width, placeable.height) {
        placeable.place(at.x.dp.roundToPx() - placeable.width / 2, at.y.dp.roundToPx() - y)
    }
}

/** An album on the album list's map: its cover, framed, and its name beneath. */
@Composable
private fun AlbumPin(album: Album, thumbnails: Thumbnails, arrivals: Int, modifier: Modifier, onTap: () -> Unit) {
    // Read per pin on screen, as the list reads per row: resolving a cover opens that album's pack.
    val cover = remember(album.id, arrivals) { thumbnails.cover(album) }
    Column(modifier.clickable(onClick = onTap), horizontalAlignment = Alignment.CenterHorizontally) {
        PinFrame(cover)
        Text(
            album.name,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp)
                .widthIn(max = 78.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** A photo on its album's map: the same frame, no label — the thumbnail is what it is. */
@Composable
private fun PhotoPin(jpeg: ByteArray?, modifier: Modifier, onTap: () -> Unit) {
    Box(modifier.clickable(onClick = onTap)) { PinFrame(jpeg) }
}

/**
 * §5's square thumbnail at 38pt, in a white frame so it reads against any basemap.
 *
 * One frame for both maps: an album's cover and a photo are the same kind of thing to look at.
 * Until its pack lands it is the same photograph-shaped mark the album list uses.
 */
@Composable
private fun PinFrame(jpeg: ByteArray?) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier.size(PIN)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(2.5.dp, Color.White, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (jpeg != null) {
            Thumbnail(jpeg, Modifier.fillMaxSize().padding(2.5.dp).clip(RoundedCornerShape(7.dp)))
        } else {
            Icon(
                Icons.image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Pins that would overlap, as a count. Smaller under ten, as the mockup draws them. */
@Composable
private fun ClusterMark(count: Int, modifier: Modifier, onTap: () -> Unit) {
    Box(
        modifier.size(if (count < 10) 32.dp else 44.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.active.copy(alpha = 0.92f))
            .border(2.dp, Color.White, CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (count < 10) 11.sp else 12.5.sp,
        )
    }
}

/**
 * Albums in one spot that no zoom separates — several trips to one town — listed instead (§6).
 *
 * Rows as the album list draws them, a cover and what it holds, without the cache strip: the
 * list is still where an album is downloaded. A tap outside the sheet closes it.
 */
@Composable
private fun SpotSheet(
    albums: List<Album>,
    thumbnails: Thumbnails,
    arrivals: Int,
    contents: (Album) -> String,
    onOpen: (Album) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 380.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(MaterialTheme.colorScheme.surface)
                // The sheet itself swallows taps, so only the scrim around it dismisses.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Text(
                "${albums.size} albums in this spot",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            LazyColumn {
                items(albums, key = { it.id.toString() }) { album ->
                    val cover = remember(album.id, arrivals) { thumbnails.cover(album) }
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(album) }.padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(54.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            if (cover != null) Thumbnail(cover, Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                album.name,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(contents(album), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                }
            }
        }
    }
}

/** §5's pin: a 38pt square thumbnail. */
private val PIN = 38.dp

/** How far past the viewport's edge, in dp, a pin is still drawn. */
private const val OFFSCREEN = 60.0
