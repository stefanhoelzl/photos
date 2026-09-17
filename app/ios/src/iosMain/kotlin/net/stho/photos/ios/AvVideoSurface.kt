@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import net.stho.photos.ui.screens.LocalViewerSound
import net.stho.photos.ui.screens.VideoSurface
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

/**
 * §5's HEVC transcode, played by the platform.
 *
 * `AVPlayerViewController` rather than a bare `AVPlayerLayer`, because §6's interop rule is to
 * reach UIKit only where the platform is the thing being used — and here it is: scrubbing, the
 * system volume, AirPlay and Picture in Picture are what a person expects of a video on a phone,
 * and every one of them would otherwise be a control this app draws and gets subtly wrong.
 *
 * It starts playing when it appears, as the desktop's libvlc surface does, and pauses when it
 * leaves composition — a swipe to the next photo must not leave audio running behind it.
 *
 * **It starts muted**, unless this viewer session was already unmuted ([ViewerSound]): a swipe
 * landing on a video must not make a sound nobody asked for. Unmuting is the player's own mute
 * control, so this draws none; it only notices the change and moves [IosAudio] with it.
 */
internal class AvVideoSurface(
    /** `muted` or `unmuted` as the open video's player stands, for a Debug build's `/state`. */
    private val report: ((String) -> Unit)? = null,
) : VideoSurface {

    @Composable
    override fun Render(path: String, modifier: Modifier) {
        // Keyed on the path so a swipe from one video to the next builds a new player rather
        // than reusing one whose item belongs to the photo left behind.
        key(path) {
            val sound = LocalViewerSound.current
            val player = remember { AVPlayer(uRL = NSURL.fileURLWithPath(path)).apply { muted = !sound.unmuted } }
            // AVPlayerViewController's mute button changes `muted` and tells no one; a quarter of a
            // second is soon enough to follow it, and far cheaper than key-value observing from Kotlin.
            LaunchedEffect(player) {
                var muted: Boolean? = null
                while (true) {
                    if (player.muted != muted) {
                        muted = player.muted
                        if (!player.muted) sound.unmuted = true
                        IosAudio.claim(player, if (player.muted) IosAudio.Mode.Muted else IosAudio.Mode.Audible)
                        report?.invoke(if (player.muted) "muted" else "unmuted")
                    }
                    delay(250)
                }
            }
            UIKitViewController(
                factory = {
                    AVPlayerViewController().apply {
                        this.player = player
                        player.play()
                    }
                },
                modifier = modifier,
                onRelease = { controller ->
                    controller.player?.pause()
                    controller.player = null
                    IosAudio.release(player)
                },
            )
        }
    }
}
