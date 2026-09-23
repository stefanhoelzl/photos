package net.stho.photos.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * A 256px JPEG from the pack, drawn.
 *
 * Decoded by Compose itself rather than through a port: JPEG is the one format every target
 * decodes without help, and §5 chose it for the thumbnail tier partly for that reason. The
 * 2048px **HEIC** preview is the one that needs a platform decoder, and that arrives with the
 * viewer.
 *
 * `remember` keys on the byte array, so scrolling a grid re-uses decoded bitmaps rather than
 * decoding the same tile on every frame.
 */
@Composable
public fun Thumbnail(jpeg: ByteArray, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val bitmap: ImageBitmap? = remember(jpeg) { runCatching { jpeg.decodeToImageBitmap() }.getOrNull() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = scale,
            modifier = modifier,
        )
    }
}
