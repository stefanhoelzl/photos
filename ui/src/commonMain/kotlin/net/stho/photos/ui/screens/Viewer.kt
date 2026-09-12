package net.stho.photos.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.uuid.Uuid
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.app.Preview

/**
 * §6's fullscreen viewer: one screen, and in E.1 one state.
 *
 * Chrome is back and gear only. **Set-as-cover moved to G** — it rewrites the album's shard
 * with `If-Match`, and E is meant to be read-only — and **share moved to E.3**, being
 * `UIActivityViewController` interop only a device can exercise. The LIVE badge is drawn, but
 * playback is E.3's: `PHLivePhotoView` is a system view with nothing to reimplement.
 *
 * The ground is the app surface, not black: iOS Photos does it that way, and a black viewer
 * inside an otherwise light app reads as a bug.
 */
@Composable
public fun Viewer(
    photos: List<PhotoRow>,
    index: Int,
    preview: Preview?,
    videoPath: String?,
    thumbnails: Map<Uuid, ByteArray>,
    /** A worker is fetching this photo's blob right now, so the placeholder pulses. */
    moving: Boolean,
    onSelect: (Int) -> Unit,
) {
    val photo = photos.getOrNull(index) ?: return
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // `contain`, not `cover`: this is the one surface in the whole app that fits rather
        // than fills, because it is the only one showing the whole photograph (§5).
        // The poster first, then the player over it once the transcode has arrived: §5's
        // preview *is* a video's poster frame, so there is never a blank rectangle.
        if (preview != null) {
            Image(preview.image, null, Modifier.fillMaxSize().padding(bottom = 96.dp), contentScale = ContentScale.Fit)
        } else {
            // The same photograph-shaped mark the album list and the grid use, on the surface
            // the wait is longest on. It pulses only while bytes are actually moving, so a
            // still glyph means the fetch is queued rather than stalled.
            Box(
                Modifier.fillMaxSize().padding(bottom = 96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(72.dp).alpha(pulseAlpha(moving)),
                )
            }
        }
        if (photo.mediaType == MediaType.VIDEO && videoPath != null) {
            LocalVideoSurface.current.Render(videoPath, Modifier.fillMaxSize().padding(bottom = 96.dp))
        }

        if (photo.mediaType == MediaType.LIVE_PHOTO) {
            Badge("LIVE", Modifier.align(Alignment.TopStart).padding(16.dp))
        }

        Column(Modifier.align(Alignment.BottomStart)) {
            Filmstrip(photos, index, thumbnails, onSelect)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    photo.takenAt?.toString()?.substringBefore('T') ?: "undated",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // §3: `bytes` is the size of the blob a tap actually fetches, so this is true
                // for a transcoded video and a carved RAW as well as for an untouched JPEG.
                photo.bytes?.let { Badge("Original ${it.megabytes()}") }
            }
        }
    }
}

/** The strip of the album, current photo outlined. Thumbnails, so it costs no network. */
@Composable
private fun Filmstrip(
    photos: List<PhotoRow>,
    index: Int,
    thumbnails: Map<Uuid, ByteArray>,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(photos, key = { it.id.toString() }) { photo ->
            val position = photos.indexOf(photo)
            Box(
                Modifier.size(30.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onSelect(position) },
            ) {
                thumbnails[photo.id]?.let { Thumbnail(it, Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

private fun Long.megabytes(): String {
    val mb = this / 1_000_000.0
    return if (mb >= 10) "${mb.toInt()} MB" else "${(mb * 10).toInt() / 10.0} MB"
}
