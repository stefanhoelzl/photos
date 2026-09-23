package net.stho.photos.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons as MaterialIcons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.ByteBuffer
import kotlinx.coroutines.delay
import net.stho.photos.ui.screens.LivePhotoSurface
import net.stho.photos.ui.screens.LocalPlayToggle
import net.stho.photos.ui.screens.VideoSurface
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * A video, played through libvlc, with the controls a desktop expects: a click pauses and
 * resumes, and a bar along the bottom shows where it is and seeks.
 *
 * The **callback** player rather than an embedded window: vlcj hands frames back as pixel
 * buffers, which become a Compose image directly. Embedding vlcj's own AWT surface would put a
 * heavyweight native window over a Skia canvas — exactly the compositing problem §6 avoided by
 * drawing everything itself.
 *
 * When libvlc is not on this machine nothing is drawn and the still already on screen underneath
 * simply stays: a missing codec pack must not stop the rest of the app being used.
 */
public class VlcVideoSurface : VideoSurface {

    @Composable
    override fun Render(path: String, modifier: Modifier) {
        if (!vlcAvailable) return
        val playback = rememberPlayback(path)
        onPlayToggle(path) { playback.toggle() }
        Box(modifier.fillMaxSize().clickable(onClick = playback::toggle)) {
            playback.frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            Controls(playback, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * A Live Photo (§11): the still, and a click that plays its MOV over it once, then the still again.
 *
 * The phone plays one through `PHLivePhotoView`; the desktop has no such view, so the pair is two
 * files in the library and libvlc plays the moving half. The still is the viewer's own picture
 * underneath, drawn at the same fit, so the motion starts and ends where the photograph is.
 */
public class VlcLivePhotoSurface : LivePhotoSurface {

    @Composable
    override fun Render(still: String, video: String, modifier: Modifier) {
        if (!vlcAvailable) return
        var playing by remember(video) { mutableStateOf(false) }
        onPlayToggle(video) { playing = !playing }
        Box(modifier.fillMaxSize().clickable { playing = !playing }) {
            if (playing) {
                val playback = rememberPlayback(video, onFinished = { playing = false })
                playback.frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
    }
}

/** Runs [action] on each press of the root's play/pause key made while [key] is on screen. */
@Composable
private fun onPlayToggle(key: Any, action: () -> Unit) {
    val toggle = LocalPlayToggle.current
    val seen = remember(key) { toggle.presses }
    val act by rememberUpdatedState(action)
    LaunchedEffect(key, toggle.presses) { if (toggle.presses != seen) act() }
}

/** Whether libvlc could be found at all. Looked for once, on first use. */
private val vlcAvailable: Boolean by lazy { runCatching { NativeDiscovery().discover() }.getOrDefault(false) }

/**
 * One file playing, as Compose state: the frame on screen, whether it is running, and where it is.
 * vlcj reports from its own threads; snapshot state takes writes from any of them.
 */
@Stable
private class Playback(private val path: String) {
    var frame: ImageBitmap? by mutableStateOf(null)
    var playing: Boolean by mutableStateOf(false)
    var finished: Boolean by mutableStateOf(false)
    var position: Float by mutableFloatStateOf(0f)
    var length: Long by mutableLongStateOf(0L)
    var player: MediaPlayer? = null

    /** Pause, resume — or, once it has played to the end, from the start again. */
    fun toggle() {
        val player = player ?: return
        when {
            finished -> {
                finished = false
                player.media().play(path)
            }
            else -> player.controls().pause()
        }
    }

    fun seek(to: Float) {
        val player = player ?: return
        if (finished) {
            finished = false
            player.media().play(path)
        }
        player.controls().setPosition(to.coerceIn(0f, 1f))
        position = to
    }
}

/** Starts [path] playing for as long as the caller is on screen, and releases the player after. */
@Composable
private fun rememberPlayback(path: String, onFinished: () -> Unit = {}): Playback {
    val playback = remember(path) { Playback(path) }
    val finish by rememberUpdatedState(onFinished)
    DisposableEffect(path) {
        val bitmap = Bitmap()
        val format = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                // RV32 is BGRA on a little-endian machine, which is what Skia calls BGRA_8888 --
                // so the frame is installed without a channel shuffle.
                bitmap.allocPixels(ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
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
                playback.frame = Image.makeFromBitmap(bitmap).toComposeImageBitmap()
            }

            override fun unlock(mediaPlayer: MediaPlayer) = Unit
        }
        val component = CallbackMediaPlayerComponent(null, null, null, false, render, format, null)
        val player = component.mediaPlayer()
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                playback.playing = true
                playback.length = mediaPlayer.status().length()
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                playback.length = newLength
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                playback.playing = false
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                playback.playing = false
                playback.finished = true
                playback.position = 1f
                finish()
            }
        })
        playback.player = player
        player.media().play(path)
        onDispose {
            playback.player = null
            player.controls().stop()
            component.release()
            bitmap.close()
        }
    }
    // Where it is, read while it runs: libvlc reports position only when asked.
    LaunchedEffect(playback, playback.playing) {
        while (playback.playing) {
            playback.player?.let {
                playback.position = it.status().position()
                playback.length = it.status().length()
            }
            delay(POLL_MS)
        }
    }
    return playback
}

/** Play or pause, the bar that seeks, and the time — over a scrim, white, as over any photo (§6). */
@Composable
private fun Controls(playback: Playback, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            // The bar's own ground: a click that misses the button or the slider is not a pause.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (playback.playing) MaterialIcons.Filled.Pause else MaterialIcons.Filled.PlayArrow,
            contentDescription = if (playback.playing) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(24.dp).clickable(onClick = playback::toggle),
        )
        Slider(
            value = playback.position,
            onValueChange = playback::seek,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
            modifier = Modifier.weight(1f),
        )
        Text(
            "${clock((playback.position * playback.length).toLong())} / ${clock(playback.length)}",
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}

private fun clock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private const val POLL_MS = 200L
