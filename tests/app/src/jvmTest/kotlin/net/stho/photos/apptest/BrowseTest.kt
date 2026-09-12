package net.stho.photos.apptest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.stho.photos.app.Screen
import net.stho.photos.app.SyncStatus

/**
 * The app, against a zone, doing what a person does with it.
 *
 * These assert **state, never pixels** (decision 22): a frame is for a person to look at, and
 * a test that compares images fails when a font hints differently. What is checked here is
 * what the app believes — which is also exactly what `GET /state` reports, so a failure here
 * and a surprise while driving it by hand are the same failure.
 */
class BrowseTest {

    @Test
    fun aSyncedZoneBecomesTheAlbumList() = runBlocking {
        scenario("browse") {
            zone.album("Iceland", photos = 3)
            zone.album("Alps", photos = 5)

            val ui = launch()

            assertTrue(ui.sync is SyncStatus.Succeeded, "sync said ${ui.sync}")
            assertEquals(setOf("Alps", "Iceland"), ui.albums.map { it.name }.toSet())
            assertEquals(Screen.Albums, ui.screen)
        }
    }

    /** §10 requires it explicitly: emptying a directory leaves an album with no photos. */
    @Test
    fun anAlbumWithNoPhotosRendersAsAnAlbumWithNoPhotos() = runBlocking {
        scenario("empty-album") {
            zone.album("Emptied", photos = 0)

            val ui = launch()
            val album = ui.albums.single()
            assertEquals(0, album.photoCount)

            model.open(album)
            assertTrue(model.state.value.screen is Screen.Grid, "an empty album still opens")
            assertEquals(emptyList(), model.state.value.photos)
        }
    }

    @Test
    fun openingAnAlbumShowsItsPhotosInSectionThreesOrder() = runBlocking {
        scenario("open-album") {
            zone.album("Coast Road", photos = 4)

            val ui = launch()
            model.open(ui.albums.single())

            val photos = model.state.value.photos
            assertEquals(4, photos.size)
            assertEquals(
                listOf("IMG_0000.jpg", "IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg"),
                photos.map { it.filename },
                "oldest first, filename breaking the tie",
            )
        }
    }

    /**
     * §6: nothing is ever disabled while loading.
     *
     * An album whose pack never arrives still opens, and the grid draws one placeholder per
     * photo — which is what tells it apart from the empty album above, where there are none.
     */
    @Test
    fun anAlbumWhosePackIsMissingStillOpens() = runBlocking {
        scenario("no-pack") {
            zone.album("Unpacked", photos = 6, thumbnails = false)

            val ui = launch()
            model.open(ui.albums.single())

            val state = model.state.value
            assertTrue(state.screen is Screen.Grid)
            assertEquals(6, state.photos.size, "the tiles are counted from the catalog")
            assertEquals(emptyMap(), state.thumbnails, "and none of them has a thumbnail yet")
        }
    }

    @Test
    fun searchFiltersByNameAndSaysSo() = runBlocking {
        scenario("search") {
            zone.album("Iceland", photos = 1)
            zone.album("Alps", photos = 1)

            launch()
            model.search("ice")

            assertEquals(listOf("Iceland"), model.state.value.albums.map { it.name })
            assertEquals("1 matching", model.state.value.subtitle)
        }
    }
}
