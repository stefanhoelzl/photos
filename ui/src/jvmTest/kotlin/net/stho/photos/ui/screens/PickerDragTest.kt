package net.stho.photos.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.stho.photos.app.GalleryAccess
import net.stho.photos.app.GalleryAsset
import net.stho.photos.app.PickerUi
import net.stho.photos.model.MediaType

/**
 * The picker's drag, performed: synthetic touches on the real composable, rendered offscreen.
 *
 * On the phone a drag across the photos selected nothing — it waited for a long press behind a list
 * that took the drag as a scroll. These press, move and release as a finger would, and assert the
 * selection the picker hands back.
 *
 * At density 1 the grid is 400 px wide: four 100 px tiles a row, with two labels above the first
 * row. Points are aimed at tile centres, well inside their tiles.
 */
class PickerDragTest {

    private val assets = (0 until 12).map { GalleryAsset("a$it", "IMG_$it.heic", MediaType.PHOTO) }

    @Test
    fun aSidewaysDragSelectsEveryPhotoItCrosses() = picker {
        press(tile(0))
        move(tile(1))
        move(tile(2))
        release(tile(2))
        assertEquals(setOf("a0", "a1", "a2"), selected)
    }

    @Test
    fun draggingBackShrinksTheRange() = picker {
        press(tile(0))
        move(tile(3))
        move(tile(1))
        release(tile(1))
        assertEquals(setOf("a0", "a1"), selected)
    }

    @Test
    fun startingOnASelectedPhotoTakesTheRangeOut() = picker(initially = setOf("a0", "a1", "a2", "a3")) {
        press(tile(1))
        move(tile(2))
        release(tile(2))
        assertEquals(setOf("a0", "a3"), selected)
    }

    @Test
    fun aPressHeldStillThenDraggedDownSelectsInReadingOrder() = picker {
        press(tile(1))
        hold()
        move(tile(5))
        release(tile(5))
        assertEquals(setOf("a1", "a2", "a3", "a4", "a5"), selected)
    }

    @Test
    fun aVerticalDragWithoutHoldingSelectsNothing() = picker {
        press(tile(0))
        move(tile(4))
        move(tile(8))
        release(tile(8))
        assertEquals(emptySet(), selected)
    }

    @Test
    fun aTapStillTogglesOnePhoto() = picker {
        press(tile(2))
        release(tile(2))
        assertEquals(setOf("a2"), selected)
    }

    // ------------------------------------------------------------------------------ harness

    private inner class Touches(private val scope: TestScope, private val scene: ImageComposeScene) {
        private val state get() = requireNotNull(current)
        val selected: Set<String> get() = state.value.selected
        private var time = 0L

        fun press(at: Offset) = send(PointerEventType.Press, at)

        /** Moves in small steps, as a finger reports, so the gesture sees the slop crossed on the way. */
        fun move(to: Offset) {
            val from = last
            for (step in 1..STEPS) send(PointerEventType.Move, from + (to - from) * (step / STEPS.toFloat()))
        }

        fun release(at: Offset) = send(PointerEventType.Release, at)

        /** Past the long-press timeout, with the finger still. */
        fun hold() {
            time += 1_000
            scope.testScheduler.advanceTimeBy(1_000)
            scope.testScheduler.runCurrent()
            scene.render()
        }

        private var last = Offset.Zero

        private fun send(type: PointerEventType, at: Offset) {
            time += 16
            last = at
            scene.sendPointerEvent(eventType = type, position = at, timeMillis = time, type = PointerType.Touch)
            scope.testScheduler.runCurrent()
            scene.render(time * 1_000_000)
        }
    }

    private var current: androidx.compose.runtime.MutableState<PickerUi>? = null

    private fun picker(initially: Set<String> = emptySet(), body: Touches.() -> Unit) = runTest {
        val state = mutableStateOf(PickerUi(access = GalleryAccess.Full, assets = assets, selected = initially))
        current = state
        val scene = ImageComposeScene(
            width = 400,
            height = 800,
            density = Density(1f),
            coroutineContext = StandardTestDispatcher(testScheduler),
        ) {
            GalleryPicker(
                picker = state.value,
                onAlbum = {},
                onToggle = { id ->
                    val chosen = state.value.selected
                    state.value = state.value.copy(selected = if (id in chosen) chosen - id else chosen + id)
                },
                onSelection = { state.value = state.value.copy(selected = it) },
                onUseSelection = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        try {
            scene.render()
            testScheduler.runCurrent()
            scene.render()
            Touches(this, scene).body()
        } finally {
            scene.close()
        }
    }

    /** The centre of the [index]th photo tile: 100 px columns, rows of 100 px below the two labels. */
    private fun tile(index: Int): Offset =
        Offset(x = 50f + (index % 4) * 100f, y = FIRST_ROW_TOP + 50f + (index / 4) * 99.5f)

    private companion object {
        const val STEPS = 6

        /** Two section labels, each 13 sp of text with 22 px of padding: about 40 px apiece. */
        const val FIRST_ROW_TOP = 80f
    }
}
