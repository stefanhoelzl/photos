package net.stho.photos.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density

/**
 * The desktop's half of the control server: the one endpoint that differs by root.
 *
 * The server itself is `:app:control`'s, shared with iOS. What only this root can do is render
 * the *same* composition offscreen, so what `/screenshot` returns is the app's real state — and
 * it needs no display at all, which is what lets an agent on a headless machine look at the UI.
 */
internal fun offscreen(content: @Composable () -> Unit): (width: Int, height: Int) -> ByteArray =
    { width, height ->
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) { content() }
        try {
            requireNotNull(scene.render().encodeToData()) { "skia declined to encode the frame" }.bytes
        } finally {
            scene.close()
        }
    }
