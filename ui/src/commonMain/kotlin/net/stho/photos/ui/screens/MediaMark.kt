package net.stho.photos.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import net.stho.photos.model.MediaType

/**
 * What a Live Photo or a video is marked with, wherever it is marked (§6): a glyph, not a word.
 * A still has no mark.
 */
private fun markOf(type: MediaType): Pair<ImageVector, String>? = when (type) {
    MediaType.PHOTO -> null
    MediaType.VIDEO -> Icons.video to "Video"
    MediaType.LIVE_PHOTO -> Icons.live to "Live Photo"
}

/**
 * The mark on a grid or picker tile: white over the photograph, bottom-left, as iOS Photos does.
 *
 * No backdrop; a soft shadow keeps it readable on snow and sky. [Icon] draws none, so a blurred
 * dark copy sits beneath it.
 */
@Composable
internal fun TileMark(type: MediaType, modifier: Modifier = Modifier) {
    val (icon, label) = markOf(type) ?: return
    Box(modifier.size(16.dp)) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp).offset(y = 0.5.dp).blur(1.5.dp, BlurredEdgeTreatment.Unbounded),
        )
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/**
 * The viewer's mark, top-left of the screen. It sits on the app surface beside a fitted photo
 * rather than on the photograph, so it takes the surface's text colour and needs no shadow.
 */
@Composable
internal fun ViewerMark(type: MediaType) {
    val (icon, label) = markOf(type) ?: return
    Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
}
