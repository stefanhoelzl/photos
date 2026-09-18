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
import net.stho.photos.app.Day
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
 * At density 1 the grid is 400 px wide: four 100 px tiles a row, with a 40 px year mark above each
 * year's first row and nothing above that — the albums label is below the photos, and there are no
 * albums here. Points are aimed at tile centres, well inside their tiles. The list is shorter than
 * the window, so opening at its end is opening at its top.
 *
 * Six photos in 2019 and six in 2020, oldest first as the picker lists them (§8), so every year
 * has a short last row and there is a boundary to drag across — and, in the one test that scrolls,
 * a mark pinned over the photos it names.
 */
class PickerDragTest {

    private val assets = (0 until 12).map {
        val day = Day.of(if (it < 6) 2019 else 2020, 6, 1 + it % 6)
        GalleryAsset("a$it", "IMG_$it.heic", MediaType.PHOTO, day.midnight)
    }

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
        move(tile(10))
        release(tile(10))
        assertEquals(emptySet(), selected)
    }

    @Test
    fun aDragCarriesOnAcrossAYearMark() = picker {
        press(tile(4))
        move(tile(5))
        move(tile(6))
        release(tile(6))
        assertEquals(setOf("a4", "a5", "a6"), selected)
    }

    /** A mark between two years is no photo: the gesture never starts, and the mark has no tap of its own. */
    @Test
    fun aDragFromAYearMarkSelectsNothing() = picker {
        press(INLINE_YEAR_MARK)
        move(INLINE_YEAR_MARK + Offset(100f, 0f))
        release(INLINE_YEAR_MARK + Offset(100f, 0f))
        assertEquals(emptySet(), selected)
    }

    /**
     * At the end in a 300 px window, 2019's mark is pinned over 2019's last row — and the finger in
     * that band is on those photos, which is what keeps selecting-while-scrolling working.
     *
     * The window is bottom-aligned once the list is at its end, so where that row sits is fixed by
     * the rows and marks below it. The Upload bar that comes with the first photo selected leaves
     * it there until the finger lifts: moved under the finger, the drag would carry on elsewhere.
     */
    @Test
    fun theFingerIsOnThePhotosUnderAPinnedYearMark() = picker(height = 300) {
        scrollToTheEnd()
        press(PINNED_BAND)
        move(PINNED_BAND + Offset(100f, 0f))
        release(PINNED_BAND + Offset(100f, 0f))
        assertEquals(setOf("a4", "a5"), selected)
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

        /** Vertical drags, which the picker leaves to the list; far more than it can scroll, so it ends at its end. */
        fun scrollToTheEnd() = repeat(3) {
            press(Offset(200f, 280f))
            move(Offset(200f, 20f))
            release(Offset(200f, 20f))
        }

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

    private fun picker(
        initially: Set<String> = emptySet(),
        height: Int = 800,
        body: Touches.() -> Unit,
    ) = runTest {
        val state = mutableStateOf(PickerUi(access = GalleryAccess.Full, assets = assets, selected = initially))
        current = state
        val scene = ImageComposeScene(
            width = 400,
            height = height,
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
                scroll = null,
                onScrolled = { _, _, _, _ -> },
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

    /**
     * The centre of the [index]th photo tile: 100 px columns, rows of 100 px below the year marks
     * above it — one for 2019's photos, two for 2020's, whose rows start afresh.
     */
    private fun tile(index: Int): Offset {
        val within = if (index < 6) index else index - 6
        val row = within / 4 + if (index < 6) 0 else 2
        val marks = if (index < 6) 1 else 2
        return Offset(
            x = 50f + (within % 4) * 100f,
            y = FIRST_ROW_TOP + marks * YEAR_MARK + 50f + row * 99.5f,
        )
    }

    private companion object {
        const val STEPS = 6

        /** Nothing above the first year mark: the list starts with the photos. */
        const val FIRST_ROW_TOP = 0f

        /** [YEAR_MARK_HEIGHT] in pixels, at this scene's density of 1. */
        val YEAR_MARK = YEAR_MARK_HEIGHT.value

        /** The middle of 2020's mark where the list draws it, between 2019's last row and 2020's first. */
        val INLINE_YEAR_MARK = Offset(200f, FIRST_ROW_TOP + YEAR_MARK + 2 * 99.5f + YEAR_MARK / 2)

        /** The first column of the band a pinned mark covers. */
        val PINNED_BAND = Offset(50f, 20f)
    }
}
