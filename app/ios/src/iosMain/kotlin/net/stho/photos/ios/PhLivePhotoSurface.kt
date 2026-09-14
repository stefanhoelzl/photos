@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import net.stho.photos.ui.screens.LivePhotoSurface
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Photos.PHImageContentModeAspectFit
import platform.Photos.PHLivePhoto
import platform.Photos.PHLivePhotoInfoIsDegradedKey
import platform.PhotosUI.PHLivePhotoView
import platform.PhotosUI.PHLivePhotoViewPlaybackStyleHint
import platform.UIKit.UIColor
import platform.UIKit.UIViewContentMode

/**
 * A Live Photo, played by the view Photos itself uses (§6: "there is nothing to reimplement").
 *
 * Built from the two files on disk rather than from the photo library, which is why §5 keeps the
 * still byte-for-byte: `PHLivePhoto` pairs a still with its MOV by Apple's content identifier,
 * carried inside each file. Nothing here asks for photo-library permission — these are this app's
 * own files.
 *
 * **The view is mounted only once there is a Live Photo to put in it.** `PHLivePhoto` answers
 * more than once — a degraded photo from the still first, then the full one — and when the pair
 * cannot be assembled the second answer is *no photo at all*. Assigning each answer as it came
 * replaced the degraded photo with nothing and left an empty native view covering the viewer's
 * own still: a blank screen, for any pair iOS will not accept. So the request runs first, an
 * empty later answer never replaces a photo already shown, and until a photo exists the view is
 * not there and Compose's still is what a person sees.
 *
 * The press-and-hold that plays it is `PHLivePhotoView`'s own gesture. On arrival it plays a
 * brief hint once, which is what tells a person a photograph has motion in it.
 */
internal class PhLivePhotoSurface(
    /** What iOS last answered, for a Debug build's `/state`. Null in release. */
    private val report: ((String) -> Unit)? = null,
) : LivePhotoSurface {

    @Composable
    override fun Render(still: String, video: String, modifier: Modifier) {
        key(still, video) {
            var photo by remember { mutableStateOf<PHLivePhoto?>(null) }

            DisposableEffect(Unit) {
                var live = true
                report?.invoke("requested")
                val request = PHLivePhoto.requestLivePhotoWithResourceFileURLs(
                    fileURLs = listOf(
                        NSURL.fileURLWithPath(Playable.liveStill(still)),
                        NSURL.fileURLWithPath(Playable.liveVideo(video)),
                    ),
                    placeholderImage = null,
                    // Zero asks for the full-size asset; the view scales it to fit.
                    targetSize = CGSizeMake(0.0, 0.0),
                    contentMode = PHImageContentModeAspectFit,
                ) { result, info ->
                    // Measured on a simulator: a degraded photo, then 167 ms later `nil`, not
                    // cancelled, for a pair iOS will not assemble. Only a photo is ever kept.
                    if (!live) return@requestLivePhotoWithResourceFileURLs
                    val degraded = (info?.get(PHLivePhotoInfoIsDegradedKey) as? NSNumber)?.boolValue == true
                    report?.invoke(
                        when {
                            result == null -> "none"
                            degraded -> "degraded"
                            else -> "full"
                        },
                    )
                    if (result != null) photo = result
                }
                onDispose {
                    live = false
                    PHLivePhoto.cancelLivePhotoRequestWithRequestID(request)
                }
            }

            photo?.let { current ->
                UIKitView(
                    factory = {
                        PHLivePhotoView().apply {
                            contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                            backgroundColor = UIColor.clearColor
                            livePhoto = current
                            startPlaybackWithStyle(PHLivePhotoViewPlaybackStyleHint)
                        }
                    },
                    // A better answer (the full photo after the degraded one) updates the same
                    // view rather than rebuilding it.
                    update = { view -> if (view.livePhoto != current) view.livePhoto = current },
                    modifier = modifier,
                    onRelease = { view -> view.stopPlayback() },
                )
            }
        }
    }
}
