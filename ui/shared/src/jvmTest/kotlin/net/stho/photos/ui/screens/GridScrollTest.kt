package net.stho.photos.ui.screens

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.stho.photos.app.Scroll
import net.stho.photos.model.PhotoRow

/**
 * The grid coming back into view: restored where it was left, then scrolled as little as shows the
 * photo the viewer was on. Rendered offscreen, asserting where the grid reports it came to rest.
 *
 * At density 1 the grid is 396 px wide inside its side padding: four 99 px tiles a row, in a
 * 400 px viewport — so four whole rows, and a sliver of the fifth.
 */
class GridScrollTest {

    private val photos = (0 until 60).map { PhotoRow(id = Uuid.random(), filename = "IMG_$it.jpg") }

    @Test
    fun aPhotoAlreadyWhollyInViewMovesNothing() =
        assertRestsAt(Scroll(key(0), 0, 0, reveal = 2), index = 0, offset = 0)

    @Test
    fun aPhotoBelowLandsOnTheBottomEdge() =
        // Row 10 ends at 1089, which is 689 scrolled: row 6 (from 594) is first, 95 past its top.
        assertRestsAt(Scroll(key(0), 0, 0, reveal = 40), index = 24, offset = 95)

    @Test
    fun aPhotoAboveLandsOnTheTopEdge() =
        assertRestsAt(Scroll(key(40), 40, 0, reveal = 0), index = 0, offset = 0)

    @Test
    fun aPhotoCutByTheBottomEdgeIsScrolledJustIntoView() =
        // Row 4 runs 396 to 495 and shows 4 px of itself: 95 more.
        assertRestsAt(Scroll(key(0), 0, 0, reveal = 16), index = 0, offset = 95)

    @Test
    fun aPhotoCutByTheTopEdgeIsScrolledJustIntoView() =
        assertRestsAt(Scroll(key(4), 4, 50, reveal = 4), index = 4, offset = 0)

    @Test
    fun aPhotoGoneSinceComesBackAtItsOldPosition() =
        assertRestsAt(Scroll(Uuid.random().toString(), 8, 30), index = 8, offset = 0)

    @Test
    fun theSamePhotoIsFoundWhereverItHasMoved() {
        val saved = Scroll(key(12), 3, 20)
        assertRestsAt(saved, index = 12, offset = 20)
    }

    // ------------------------------------------------------------------------------ harness

    private fun key(index: Int) = photos[index].id.toString()

    private fun assertRestsAt(scroll: Scroll, index: Int, offset: Int) = runTest {
        var reported: Triple<String?, Int, Int>? = null
        val scene = ImageComposeScene(
            width = 400,
            height = 400,
            density = Density(1f),
            coroutineContext = StandardTestDispatcher(testScheduler),
        ) {
            PhotoGrid(
                photos = photos,
                thumbnails = emptyMap(),
                columns = 4,
                scroll = scroll,
                onScrolled = { key, at, by -> reported = Triple(key, at, by) },
                onDensity = {},
                onOpen = {},
            )
        }
        try {
            repeat(FRAMES) { frame ->
                scene.render(frame * 16_000_000L)
                testScheduler.runCurrent()
            }
        } finally {
            scene.close()
        }
        assertEquals(Triple(key(index), index, offset), reported)
    }

    private companion object {
        const val FRAMES = 8
    }
}
