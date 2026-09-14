package net.stho.photos.iostest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
