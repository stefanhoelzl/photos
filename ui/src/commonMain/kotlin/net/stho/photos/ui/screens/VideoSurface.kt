package net.stho.photos.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/**
 * A Live Photo's still and MOV, played as one — the other thing on the viewer no shared code
 * can draw.
 *
 * A UI-interop port exactly like [VideoSurface]. On iOS it is `PHLivePhotoView`, the system view
 * §6 says there is nothing to reimplement; the desktop installs none, so the still §5 already
 * put on screen stays and the Live mark says what the phone would do with it.
 */
public fun interface LivePhotoSurface {
    @Composable
    public fun Render(still: String, video: String, modifier: Modifier)
}

/** Provided by the composition root. The default draws nothing, for the reason [LocalVideoSurface]'s does. */
public val LocalLivePhotoSurface: androidx.compose.runtime.ProvidableCompositionLocal<LivePhotoSurface> =
    staticCompositionLocalOf { LivePhotoSurface { _, _, _ -> } }

/**
 * Whether sound was asked for in this viewer session.
 *
 * A video opens muted; unmuting one is a person saying they want to hear it, so the videos and
 * Live Photos after it in the same viewer play with sound too — even with the phone on silent,
 * since unmuting is that override. The [Viewer] owns it, so leaving for the grid mutes again.
 * Only the platform surfaces read and write it; shared code draws no sound control.
 */
@Stable
public class ViewerSound {
    public var unmuted: Boolean by mutableStateOf(false)
}

/** Provided by the [Viewer]. The default is a session nobody unmuted. */
public val LocalViewerSound: androidx.compose.runtime.ProvidableCompositionLocal<ViewerSound> =
    staticCompositionLocalOf { ViewerSound() }
