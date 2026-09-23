package net.stho.photos.apptest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.stho.photos.app.AppUi
import net.stho.photos.app.Cluster
import net.stho.photos.app.MapCamera
import net.stho.photos.app.MapPin
import net.stho.photos.app.Screen

/**
 * The map (§6), against a zone: pins come from EXIF GPS through the real rebuild, and a tap goes
 * where a person's would.
 *
 * The basemap here is `:ui:shared`'s stand-in — MapLibre needs a window — so the frames this writes show
 * the pins and clusters on a plain ground, which is exactly the part the app decides.
 */
class MapTest {

    @Test
    fun theMapPlacesLocatedAlbumsOpensOneAndComesBackWhereItWas() = runBlocking {
        scenario("map") {
            zone.album("Iceland", photos = 2, at = REYKJAVIK)
            zone.album("Rome", photos = 3, at = ROME)
            zone.album("Garden", photos = 1)

            launch()
            model.toggleMap()
            val albums = await("the album list's map") { it.map != null && it.stack.map?.camera != null }
            assertEquals("2 of 3 albums on the map", albums.subtitle, "Garden has no location")
            screenshot("map-albums")

            // Read after the frame, not before: drawing the map reports its size, and the model
            // refits the untouched first frame to it -- so this is the camera the map is left at.
            val left = model.state.value
            val camera = requireNotNull(left.stack.map?.camera)
            val iceland = left.clusters().single { it.isPin && left.nameOf(it) == "Iceland" }
            model.tapMap(iceland)
            assertTrue(model.state.value.screen is Screen.Grid, "a pin opens its album")

            // On its own map: the album's photos where they were taken, not its grid.
            val album = await("the album's photos on its map") { ui ->
                ui.showingMap && ui.map?.pins?.let { pins -> pins.isNotEmpty() && pins.all { it is MapPin.OfPhoto } } == true
            }
            assertEquals("2 of 2 photos on the map", album.photosSubtitle)
            screenshot("map-album")

            model.back()
            val back = await("the album list's map again") { it.showingMap && it.map != null }
            assertEquals(camera, back.stack.map?.camera, "Back returns to the map as it was left")
        }
    }

    /** A cluster zooms until it splits; albums sharing one spot never split, and are listed (§6). */
    @Test
    fun aClusterZoomsInAndAlbumsInOneSpotAreListed() = runBlocking {
        scenario("map-cluster") {
            val first = zone.album("Reykjavík 2019", photos = 1, at = REYKJAVIK)
            val second = zone.album("Reykjavík 2023", photos = 1, at = REYKJAVIK)
            zone.album("Akureyri", photos = 1, at = AKUREYRI)
            zone.album("Rome", photos = 1, at = ROME)

            launch()
            model.toggleMap()
            await("the map") { it.map != null && it.stack.map?.camera != null }
            // Zoomed out over the North Atlantic, Iceland's three albums are one circle.
            model.moveCamera(MapCamera(64.9, -20.0, 2.0))
            val iceland = model.state.value.clusters().single { it.members.size == 3 }
            screenshot("map-cluster")

            model.tapMap(iceland)
            val zoomed = model.state.value
            assertTrue(requireNotNull(zoomed.stack.map?.camera).zoom > 2.0, "the tap zoomed in")
            val split = zoomed.clusters()
            assertTrue(split.any { it.isPin && zoomed.nameOf(it) == "Akureyri" }, "Akureyri is a pin of its own now")

            model.tapMap(split.single { it.members.size == 2 })
            val sheet = requireNotNull(model.state.value.map?.sheet)
            assertEquals(setOf(first, second), sheet.map { it.id }.toSet(), "the two in one spot are listed")
            screenshot("map-sheet")

            model.openFromSheet(sheet.single { it.id == second })
            assertEquals(Screen.Grid(second, "Reykjavík 2023"), model.state.value.screen)
            assertTrue(model.state.value.showingMap, "opened on its map, as its pin would be")
            model.back()
            assertEquals(null, model.state.value.map?.sheet, "the list does not reopen on the way back")
        }
    }

    @Test
    fun aSearchNarrowsTheMapAsItNarrowsTheList() = runBlocking {
        scenario("map-search") {
            zone.album("Reykjavík 2019", photos = 1, at = REYKJAVIK)
            zone.album("Reykjavík 2023", photos = 1, at = REYKJAVIK)
            zone.album("Reykjanes", photos = 1)
            zone.album("Rome", photos = 1, at = ROME)

            launch()
            model.search("reyk")
            model.toggleMap()
            val ui = await("the searched map") { it.map != null }

            assertEquals("2 of 3 matching on the map", ui.subtitle, "Reykjanes matches but has no location")
            assertEquals(
                setOf("Reykjavík 2019", "Reykjavík 2023"),
                requireNotNull(ui.map).pins.map { (it as MapPin.OfAlbum).album.name }.toSet(),
            )
        }
    }

    @Test
    fun anAlbumsMapOpensTheViewerAtThePhotoTapped() = runBlocking {
        scenario("map-photos") {
            zone.album("Ring Road", photos = 3, places = listOf(REYKJAVIK, null, AKUREYRI))

            val ui = launch()
            model.open(ui.albums.single())
            model.toggleMap()
            val map = await("the album's map") { it.map != null && it.stack.map?.camera != null }
            assertEquals("2 of 3 photos on the map", map.photosSubtitle, "the second photo has no location")

            val akureyri = map.clusters().single { cluster ->
                cluster.isPin && (requireNotNull(map.map).pins[cluster.members.single()] as MapPin.OfPhoto).index == 2
            }
            screenshot("map-photos")
            model.tapMap(akureyri)
            assertEquals(2, (model.state.value.screen as Screen.Photo).index, "the viewer opens at that photo")

            model.back()
            assertTrue(model.state.value.showingMap, "and Back is the album's map again")
        }
    }

    private companion object {
        val REYKJAVIK = 64.14 to -21.94

        /** About 250 km from Reykjavík: one circle over the Atlantic, two pins over Iceland. */
        val AKUREYRI = 65.68 to -18.09
        val ROME = 41.9 to 12.5
    }
}

/** What the map draws at its camera's zoom. */
private fun AppUi.clusters(): List<Cluster> =
    requireNotNull(map).clusters.at(requireNotNull(stack.map?.camera).zoom)

/** The album a single pin stands for. */
private fun AppUi.nameOf(pin: Cluster): String =
    (requireNotNull(map).pins[pin.members.single()] as MapPin.OfAlbum).album.name
