@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import net.stho.photos.ui.screens.VideoSurface
import platform.AVFoundation.AVPlayer
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
 */
internal class AvVideoSurface : VideoSurface {

    @Composable
    override fun Render(path: String, modifier: Modifier) {
        // Keyed on the path so a swipe from one video to the next builds a new player rather
        // than reusing one whose item belongs to the photo left behind.
        key(path) {
            UIKitViewController(
                factory = {
                    AVPlayerViewController().apply {
                        player = AVPlayer(uRL = NSURL.fileURLWithPath(Playable.video(path)))
                        player?.play()
                    }
                },
                modifier = modifier,
                onRelease = { controller ->
                    controller.player?.pause()
                    controller.player = null
                },
            )
        }
    }
}
