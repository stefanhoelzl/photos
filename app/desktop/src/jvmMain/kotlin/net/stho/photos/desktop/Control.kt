package net.stho.photos.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/**
 * The desktop's half of the control server: the one endpoint that differs by root.
 *
 * The server itself is `:app:control`'s, shared with iOS. What only this root can do is render
 * the *same* composition offscreen, so what `/screenshot` returns is the app's real state — and
 * it needs no display at all, which is what lets an agent on a headless machine look at the UI.
 */
internal fun offscreen(content: @Composable () -> Unit): (width: Int, height: Int) -> ByteArray =
    { width, height -> renderFrame(width, height, content) }

/**
 * One PNG of [content], rendered offscreen.
 *
 * **On one thread, which is also the scene's coroutine context.** Left to its default an
 * `ImageComposeScene` runs effects unconfined, and an effect that delays — the album list's
 * `scrollToItem` does — resumes through Swing's dispatcher on the AWT event thread and measures the
 * scene there while the caller renders it: "multithreaded access to SnapshotStateObserver" or
 * "performMeasureAndLayout called during measure layout", about one frame in six in `RenderTest`.
 * Here the scene is built, rendered and closed on [frames], and an effect waiting to resume queues
 * behind that work on the same thread, so it can never run in the middle of a frame.
 *
 * Public for `:tests:app`, whose screenshots are drawn exactly as `/screenshot` draws them.
 */
public fun renderFrame(width: Int, height: Int, content: @Composable () -> Unit): ByteArray =
    runBlocking(frames) {
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(2f),
            coroutineContext = frames,
            content = content,
        )
        try {
            requireNotNull(scene.render().encodeToData()) { "skia declined to encode the frame" }.bytes
        } finally {
            scene.close()
        }
    }

/** The thread every offscreen frame is drawn on. A daemon, so it never holds the JVM open. */
private val frames = Executors.newSingleThreadExecutor { task ->
    Thread(task, "offscreen-frames").apply { isDaemon = true }
}.asCoroutineDispatcher()
