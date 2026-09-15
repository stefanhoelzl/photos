package net.stho.photos.apptest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import net.stho.photos.app.CalendarMonth
import net.stho.photos.app.DateRange
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

    // ------------------------------------------------------------------------------ the date filter

    /**
     * A month's title picks every day of it (§6). A scenario cannot tap, so it applies the range
     * the tap applies — against dates the sync read from real shards, at both edges of the month.
     */
    @Test
    fun pickingAWholeMonthKeepsTheAlbumsWithPhotosInIt() = runBlocking {
        scenario("date-month") {
            zone.album("Reykjavík", photos = 2, taken = Instant.parse("2024-03-31T23:59:59Z"))
            zone.album("Lofoten", photos = 3, taken = Instant.parse("2024-04-01T00:00:00Z"))
            zone.album("Alps", photos = 1, taken = Instant.parse("2023-03-15T12:00:00Z"))

            launch()
            model.openCalendar()
            val march = CalendarMonth(2024, 3)
            assertEquals(2, requireNotNull(model.state.value.calendar).photosInMonth(march), "the title's total")

            assertTrue(model.applyRange(march.days))
            val ui = model.state.value
            assertEquals(listOf("Reykjavík"), ui.albums.map { it.name }, "March's last second is in it; April's first is not")
            assertEquals("1 matching", ui.subtitle)
            assertEquals("1 – 31 Mar 2024", ui.range?.label)
        }
    }

    /** A year's heading picks 1 January to 31 December, and coming back from an album keeps it (§6). */
    @Test
    fun pickingAWholeYearKeepsItsAlbumsAndSurvivesOpeningOne() = runBlocking {
        scenario("date-year") {
            zone.album("New Year", photos = 1, taken = Instant.parse("2024-01-01T00:00:00Z"))
            zone.album("Silvester", photos = 2, taken = Instant.parse("2024-12-31T23:59:59Z"))
            zone.album("Before", photos = 1, taken = Instant.parse("2023-12-31T23:59:59Z"))

            launch()
            model.openCalendar()
            assertEquals(3, requireNotNull(model.state.value.calendar).photosInYear(2024), "the heading's total")

            assertTrue(model.applyRange(DateRange.year(2024)))
            assertEquals(setOf("New Year", "Silvester"), model.state.value.albums.map { it.name }.toSet())
            assertEquals("2 matching", model.state.value.subtitle)

            model.open(model.state.value.albums.single { it.name == "Silvester" })
            assertTrue(model.state.value.screen is Screen.Grid)
            model.back()
            assertEquals(DateRange.year(2024), model.state.value.range, "the range outlives the round trip")
            assertEquals(setOf("New Year", "Silvester"), model.state.value.albums.map { it.name }.toSet())
        }
    }
}
