package net.stho.photos.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Playing a video, which is the one thing on this screen that no shared code can draw.
 *
 * A **UI-interop port**: unlike a value port, whatever satisfies it *is* a composable, so its
 * implementation lives in the platform app module — `:app:desktop` over libvlc today,
 * `:app:ios` over AVPlayer in a `UIKitView` later — and never in `:adapter:linux`, which stays
 * free of Compose. It is still ports and adapters; only the binding differs, and it is
 * injected rather than `expect`/`actual` so that `:tests:app` can substitute a recording fake
 * and a headless run never opens a real player.
 */
public fun interface VideoSurface {
    @Composable
    public fun Render(path: String, modifier: Modifier)
}

/**
 * Provided by the composition root.
 *
 * The default plays nothing, deliberately: a screen that finds no surface installed should
 * show a still rather than crash, which is exactly what a test or a headless render wants.
 */
public val LocalVideoSurface: androidx.compose.runtime.ProvidableCompositionLocal<VideoSurface> =
    staticCompositionLocalOf { VideoSurface { _, _ -> } }
