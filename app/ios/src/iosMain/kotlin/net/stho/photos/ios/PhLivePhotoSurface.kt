@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import net.stho.photos.ui.screens.LivePhotoSurface
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.Photos.PHImageContentModeAspectFit
import platform.Photos.PHLivePhoto
import platform.PhotosUI.PHLivePhotoView
import platform.PhotosUI.PHLivePhotoViewPlaybackStyleHint
import platform.UIKit.UIColor
import platform.UIKit.UIViewContentMode

/**
 * A Live Photo, played by the view Photos itself uses (§6: "there is nothing to reimplement").
 *
 * Built from the two files on disk rather than from the photo library, which is why §5 keeps the
 * still byte-for-byte: `PHLivePhoto` pairs a still with its MOV by Apple's content identifier,
 * carried inside each file, and a re-encoded still would have lost it. Nothing here asks for
 * photo-library permission — these are this app's own files.
 *
 * The press-and-hold that plays it is `PHLivePhotoView`'s own gesture, so it behaves exactly as
 * it does in Photos. On arrival it plays a brief hint once, which is what tells a person a
 * photograph has motion in it before the LIVE badge has to.
 */
internal class PhLivePhotoSurface : LivePhotoSurface {

    @Composable
    override fun Render(still: String, video: String, modifier: Modifier) {
        key(still, video) {
            UIKitView(
                factory = {
                    PHLivePhotoView().apply {
                        contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                        // The viewer's own ground shows through, so the still underneath and the
                        // live view over it read as one picture while the pair loads.
                        backgroundColor = UIColor.clearColor
                        val view = this
                        PHLivePhoto.requestLivePhotoWithResourceFileURLs(
                            fileURLs = listOf(
                                NSURL.fileURLWithPath(Playable.liveStill(still)),
                                NSURL.fileURLWithPath(Playable.liveVideo(video)),
                            ),
                            placeholderImage = null,
                            // Zero asks for the full-size asset; the view scales it to fit.
                            targetSize = CGSizeMake(0.0, 0.0),
                            contentMode = PHImageContentModeAspectFit,
                        ) { photo, _ ->
                            // Called more than once: a degraded result first, then the full one.
                            // Only the first arrival plays the hint.
                            val first = view.livePhoto == null
                            view.livePhoto = photo
                            if (first && photo != null) {
                                view.startPlaybackWithStyle(PHLivePhotoViewPlaybackStyleHint)
                            }
                        }
                    }
                },
                modifier = modifier,
                onRelease = { view -> view.stopPlayback() },
            )
        }
    }
}
