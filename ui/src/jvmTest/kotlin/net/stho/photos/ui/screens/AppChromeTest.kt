package net.stho.photos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The window's furniture, pressed: what sits at a screen's bottom edge stays reachable.
 *
 * The upload pill used to be drawn over the screen rather than under it, and for as long as an
 * upload ran the picker's "Upload N selected" button — the last row of the picker's column — took
 * no taps at all: the pill swallowed them. These press where that button is, with a pill in place,
 * and assert the press arrives. Against the old arrangement they fail.
 *
 * Synthetic touches on the real composable, rendered offscreen, as [PickerDragTest] does.
 */
class AppChromeTest {

    @Test
    fun aButtonAtTheScreensBottomTakesAPressWhileThePillIsShowing() = chrome(pill = 100.dp) {
        press(bottomOfScreen())
        release(bottomOfScreen())
        assertTrue(pressed, "the screen's bottom row is above the pill, so the press is its own")
    }

    @Test
    fun theSameButtonTakesAPressWithNoPillAtAll() = chrome(pill = null) {
        press(bottomOfScreen(pill = 0f))
        release(bottomOfScreen(pill = 0f))
        assertTrue(pressed, "and without an upload it is simply the bottom of the window")
    }

    @Test
    fun aPressOnThePillIsNotTheScreensSecretly() = chrome(pill = 100.dp) {
        press(Offset(200f, 750f))
        release(Offset(200f, 750f))
        assertFalse(pressed, "the pill has the bottom 100 px to itself")
    }

    /** The toast floats over the content: a tap on it is the toast's, and dismisses it. */
    @Test
    fun aToastFloatsOverTheScreenRatherThanShrinkingIt() = chrome(pill = 100.dp, toast = 60.dp) {
        press(bottomOfScreen())
        release(bottomOfScreen())
        assertTrue(dismissed, "the toast is over the button, and a tap on it dismisses it")
        assertFalse(pressed, "so the button beneath does not also fire")
    }

    // ------------------------------------------------------------------------------ harness

    private inner class Presses(private val scene: ImageComposeScene) {
        private var time = 0L

        fun press(at: Offset) = send(PointerEventType.Press, at)

        fun release(at: Offset) = send(PointerEventType.Release, at)

        private fun send(type: PointerEventType, at: Offset) {
            time += 16
            scene.sendPointerEvent(eventType = type, position = at, timeMillis = time, type = PointerType.Touch)
            scene.render(time * 1_000_000)
        }
    }

    private var pressed = false
    private var dismissed = false

    /** The middle of the screen's own bottom row — [BUTTON] tall, immediately above the pill. */
    private fun bottomOfScreen(pill: Float = PILL): Offset = Offset(200f, HEIGHT - pill - BUTTON / 2)

    private fun chrome(pill: Dp?, toast: Dp? = null, body: Presses.() -> Unit) = runTest {
        pressed = false
        dismissed = false
        val scene = ImageComposeScene(
            width = 400,
            height = HEIGHT.toInt(),
            density = Density(1f),
            coroutineContext = StandardTestDispatcher(testScheduler),
        ) {
            AppChrome(
                toast = toast?.let {
                    {
                        Box(
                            Modifier.fillMaxWidth().height(it).background(Color.Yellow)
                                .clickable { dismissed = true },
                        )
                    }
                },
                pill = pill?.let { { Box(Modifier.fillMaxWidth().height(it).background(Color.Gray)) } },
            ) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.fillMaxWidth().height(BUTTON.dp).background(Color.Blue)
                        .clickable { pressed = true },
                )
            }
        }
        try {
            scene.render()
            testScheduler.runCurrent()
            scene.render()
            Presses(scene).body()
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val HEIGHT = 800f

        /** The pill the tests draw, in px at density 1 — the same number as the `dp` passed in. */
        const val PILL = 100f

        /** The screen's own bottom row, standing in for the picker's "Upload N selected". */
        const val BUTTON = 48f
    }
}
