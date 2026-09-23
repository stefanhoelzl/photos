package net.stho.photos.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.stho.photos.app.UploadStage
import net.stho.photos.app.UploadStatus

/**
 * The sheet's ceiling: however many albums are queued, it keeps to half the window.
 *
 * It sits in the layout now rather than over it ([AppChrome]), so its height is the screen's loss.
 * Eight albums' rows would otherwise take the lot, and the picker's button — one bar above it —
 * would go with it.
 */
class UploadBarTest {

    private fun statuses(count: Int) = (1..count).map {
        UploadStatus(
            albumId = Uuid.random(),
            target = Uuid.random(),
            name = "Album $it",
            parent = null,
            path = "Trips / Album $it",
            stage = UploadStage.Waiting,
            files = 12,
        )
    }

    @Test
    fun aCollapsedPillIsOnlyAsTallAsItNeeds() = bar(statuses(8)) {
        assertTrue(height in 1..(HEIGHT / 4), "a pill, not a sheet: $height px")
    }

    @Test
    fun theSheetStopsAtHalfTheWindowHoweverManyAreQueued() = bar(statuses(8)) {
        expand()
        assertTrue(height <= HEIGHT / 2, "the sheet took $height px of $HEIGHT")
        assertTrue(height > HEIGHT / 4, "and it did grow into a sheet: $height px")
    }

    @Test
    fun aSheetOfTwoTakesOnlyWhatItNeeds() = bar(statuses(2)) {
        expand()
        assertTrue(height < HEIGHT / 2, "two rows do not fill the cap: $height px")
    }

    // ------------------------------------------------------------------------------ harness

    private inner class Bar(private val scene: ImageComposeScene) {
        val height: Int get() = measured

        /** Taps the collapsed pill, which is the whole of it. */
        fun expand() {
            val at = Offset(200f, HEIGHT - measured / 2f)
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = 16, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = 32, type = PointerType.Touch)
            scene.render(32_000_000)
        }
    }

    private var measured = 0

    private fun bar(shown: List<UploadStatus>, body: Bar.() -> Unit) = runTest {
        measured = 0
        val scene = ImageComposeScene(
            width = 400,
            height = HEIGHT,
            density = Density(1f),
            coroutineContext = StandardTestDispatcher(testScheduler),
        ) {
            // The pill as the chrome hands it its space: the bottom of a full-height column.
            AppChrome(
                pill = {
                    Box(Modifier.fillMaxWidth().onSizeChanged { measured = it.height }) {
                        UploadBar(shown, onCancel = {}, onRetry = {})
                    }
                },
            ) {
                Box(Modifier.fillMaxSize())
            }
        }
        try {
            scene.render()
            testScheduler.runCurrent()
            scene.render()
            Bar(scene).body()
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val HEIGHT = 800
    }
}
