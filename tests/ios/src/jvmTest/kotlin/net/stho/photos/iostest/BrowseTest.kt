package net.stho.photos.iostest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.jsonObject

/** `:tests:app`'s browse scenarios, against the Debug app on a simulator (decision 15). */
class BrowseTest {

    @Test
    fun aSyncedZoneBecomesTheAlbumList() = iosScenario("browse") {
        zone.album("Iceland", photos = 3)
        zone.album("Alps", photos = 5)
        install(); launch()

        val state = setUp()
        assertEquals("albums", state.string("screen"))
        assertEquals(setOf("Alps", "Iceland"), state.albums().map { it.string("name") }.toSet())
        screenshot("browse")
    }

    /** §10 requires it explicitly: emptying a directory leaves an album with no photos. */
    @Test
    fun anAlbumWithNoPhotosRendersAsAnAlbumWithNoPhotos() = iosScenario("empty-album") {
        val id = zone.album("Emptied", photos = 0)
        install(); launch()
        val album = setUp().album("Emptied")
        assertEquals(0, album.string("photos").toInt())

        val opened = post("/nav?to=album/$id")
        assertTrue(opened.string("screen").startsWith("grid/"), "an empty album still opens: ${opened.string("screen")}")
        assertEquals(emptyList(), opened.photos())
    }

    @Test
    fun openingAnAlbumShowsItsPhotosInSectionThreesOrder() = iosScenario("open-album") {
        val id = zone.album("Coast Road", photos = 4)
        install(); launch(); setUp()

        val photos = post("/nav?to=album/$id").photos()
        assertEquals(
            listOf("IMG_0000.jpg", "IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg"),
            photos.map { it.string("filename") },
            "oldest first, filename breaking the tie",
        )
        screenshot("open-album")
    }

    /**
     * §6: nothing is ever disabled while loading. An album whose pack never arrives still opens
     * and lists its photos. (`/state` does not carry thumbnails, so unlike the desktop scenario
     * this cannot also assert that none arrived; the screenshot shows the placeholders.)
     */
    @Test
    fun anAlbumWhosePackIsMissingStillOpens() = iosScenario("no-pack") {
        val id = zone.album("Unpacked", photos = 6, thumbnails = false)
        install(); launch(); setUp()

        val opened = post("/nav?to=album/$id")
        assertTrue(opened.string("screen").startsWith("grid/"))
        assertEquals(6, opened.photos().size, "the tiles are counted from the catalog")
        screenshot("no-pack")
    }

    @Test
    fun searchFiltersByNameAndSaysSo() = iosScenario("search") {
        zone.album("Iceland", photos = 1)
        zone.album("Alps", photos = 1)
        install(); launch(); setUp()

        val searched = post("/search?q=ice")
        assertEquals(listOf("Iceland"), searched.albums().map { it.string("name") })
        assertEquals("1 matching", searched.string("subtitle"))
    }

    /** `:tests:app`'s whole-month scenario, through `/calendar` and the range a month title's tap applies. */
    @Test
    fun pickingAWholeMonthKeepsTheAlbumsWithPhotosInIt() = iosScenario("date-month") {
        zone.album("Reykjavík", photos = 2, taken = Instant.parse("2024-03-31T23:59:59Z"))
        zone.album("Lofoten", photos = 3, taken = Instant.parse("2024-04-01T00:00:00Z"))
        zone.album("Alps", photos = 1, taken = Instant.parse("2023-03-15T12:00:00Z"))
        install(); launch(); setUp()

        val days = post("/calendar").getValue("calendar").jsonObject.getValue("days").jsonObject
        assertEquals("2", days.string("2024-03-31"))
        assertEquals("3", days.string("2024-04-01"))

        val picked = post("/range?from=2024-03-01&to=2024-03-31")
        assertEquals(listOf("Reykjavík"), picked.albums().map { it.string("name") }, "March's last second is in it; April's first is not")
        assertEquals("1 matching", picked.string("subtitle"))
        assertEquals("1 – 31 Mar 2024", picked.getValue("range").jsonObject.string("label"))
        screenshot("date-month")
    }

    /** `:tests:app`'s whole-year scenario: the range a year heading's tap applies, kept across opening an album. */
    @Test
    fun pickingAWholeYearKeepsItsAlbumsAndSurvivesOpeningOne() = iosScenario("date-year") {
        zone.album("New Year", photos = 1, taken = Instant.parse("2024-01-01T00:00:00Z"))
        val silvester = zone.album("Silvester", photos = 2, taken = Instant.parse("2024-12-31T23:59:59Z"))
        zone.album("Before", photos = 1, taken = Instant.parse("2023-12-31T23:59:59Z"))
        install(); launch(); setUp()

        val picked = post("/range?from=2024-01-01&to=2024-12-31")
        assertEquals(setOf("New Year", "Silvester"), picked.albums().map { it.string("name") }.toSet())
        assertEquals("2 matching", picked.string("subtitle"))

        post("/nav?to=album/$silvester")
        val back = post("/nav?to=back")
        assertEquals("1 Jan – 31 Dec 2024", back.getValue("range").jsonObject.string("label"), "the range outlives the round trip")
        assertEquals(setOf("New Year", "Silvester"), back.albums().map { it.string("name") }.toSet())
    }
}
