package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.stho.photos.app.AppUi
import net.stho.photos.app.Cluster
import net.stho.photos.app.MapCamera
import net.stho.photos.app.Thumbnails
import net.stho.photos.catalog.Album

/**
 * A level drawn as its map: the album list's, a container's, or an album's (§6).
 *
 * [MapCanvas] draws the basemap and the pins; what is the phone's own is the sheet that lists the
 * albums in a spot no zoom separates.
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
    Box(Modifier.fillMaxSize()) {
        MapCanvas(ui.stack.map, ui.map, ui.thumbnails, thumbnails, arrivals, onViewport, onCameraMoved, onTap)
        ui.map?.sheet?.let { albums ->
            SpotSheet(albums, thumbnails, arrivals, contents, onOpenFromSheet, onDismissSheet)
        }
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
