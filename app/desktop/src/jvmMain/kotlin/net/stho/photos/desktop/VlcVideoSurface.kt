package net.stho.photos.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.nio.ByteBuffer
import net.stho.photos.ui.screens.VideoSurface
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * §5's HEVC transcode, played through libvlc.
 *
 * The **callback** player rather than an embedded window: vlcj hands frames back as pixel
 * buffers, which become a Compose image directly. Embedding vlcj's own AWT surface would put a
 * heavyweight native window over a Skia canvas — exactly the compositing problem §6 avoided by
 * drawing everything itself.
 *
 * When libvlc is not on this machine nothing is drawn and §5's poster, already on screen
 * underneath, simply stays. That is deliberate: playback is a development affordance — the
 * phone uses AVPlayer through `UIKitView` — and a missing codec pack must not stop the rest of
 * the app being reviewed.
 */
public class VlcVideoSurface : VideoSurface {

    private val available: Boolean by lazy {
        runCatching { NativeDiscovery().discover() }.getOrDefault(false)
    }

    @Composable
    override fun Render(path: String, modifier: Modifier) {
        if (!available) return
        var frame by remember(path) { mutableStateOf<ImageBitmap?>(null) }

        DisposableEffect(path) {
            val bitmap = Bitmap()
            val format = object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    // RV32 is BGRA on a little-endian machine, which is what Skia calls
                    // BGRA_8888 -- so the frame is installed without a channel shuffle.
                    bitmap.allocPixels(
                        ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL),
                    )
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }

                override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit

                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
            }
            val render = object : RenderCallback {
                override fun lock(mediaPlayer: MediaPlayer) = Unit

                override fun display(
                    mediaPlayer: MediaPlayer,
                    nativeBuffers: Array<out ByteBuffer>,
                    bufferFormat: BufferFormat,
                    displayWidth: Int,
                    displayHeight: Int,
                ) {
                    val buffer = nativeBuffers[0]
                    buffer.rewind()
                    val pixels = ByteArray(buffer.remaining()).also(buffer::get)
                    bitmap.installPixels(pixels)
                    frame = Image.makeFromBitmap(bitmap).toComposeImageBitmap()
                }

                override fun unlock(mediaPlayer: MediaPlayer) = Unit
            }
            val player = CallbackMediaPlayerComponent(null, null, null, false, render, format, null)
            player.mediaPlayer().media().play(path)
            onDispose {
                player.mediaPlayer().controls().stop()
                player.release()
                bitmap.close()
            }
        }

        Box(modifier.fillMaxSize()) {
            frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
    }
}
