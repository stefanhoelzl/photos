package net.stho.photos.apptest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.stho.photos.app.Screen

/**
 * Back from the viewer, the grid is where it was left (§6) — and shows the photo a swipe carried
 * the viewer to. The grid restores and reveals as it is drawn, so the frames are drawn: the model
 * then reports where the grid really came to rest.
 */
class ScrollTest {

    @Test
    fun backFromTheViewerFindsTheGridWhereItWasLeftShowingTheLastPhoto() = runBlocking {
        scenario("scroll-back") {
            zone.album("Coast Road", photos = 60)

            val ui = launch()
            model.open(ui.albums.single())
            val photos = model.state.value.photos
            model.scrollTo(8)
            screenshot("scroll-left", frames = FRAMES)
            assertEquals(photos[8].id.toString(), model.state.value.stack.scroll?.key, "the grid came to rest on the row scrolled to")

            model.openPhoto(9)
            model.showPhoto(50)
            model.back()
            val back = requireNotNull(model.state.value.stack.scroll)
            assertTrue(model.state.value.screen is Screen.Grid)
            assertEquals(photos[8].id.toString(), back.key, "Back remembers where the grid was left")
            assertEquals(50, back.reveal, "and asks for the photo the viewer was on")

            screenshot("scroll-back", frames = FRAMES)
            val rest = requireNotNull(model.state.value.stack.scroll)
            assertNull(rest.reveal, "the grid showed it")
            // Photo 50 is on row 12, far below rows 2 onwards: its row lands on the bottom edge,
            // so the first row on screen is a handful above it — never the row it was left on.
            assertTrue(rest.index in 9..50, "scrolled down towards photo 50, to ${rest.index}")
            assertTrue(50 - rest.index < 4 * 10, "with photo 50 on screen, from ${rest.index}")
            assertEquals(photos[rest.index].id.toString(), rest.key)
        }
    }

    private companion object {
        const val FRAMES = 8
    }
}
