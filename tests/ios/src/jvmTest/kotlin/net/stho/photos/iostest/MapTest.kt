package net.stho.photos.iostest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `:tests:app`'s map scenarios, against the Debug app on a simulator.
 *
 * The one run in which the basemap is MapLibre rather than `:ui:phone`'s stand-in, so its screenshots are
 * the frames of the map that show tiles under the pins. MapLibre animates a camera the model moves,
 * and only the settled position is reported back — so after a move these wait for it to land before
 * reading clusters, which `/map/tap` indexes at the camera's current zoom.
 */
class MapTest {

    @Test
    fun theMapPlacesLocatedAlbumsOpensOneAndComesBack() = iosScenario("map") {
        val iceland = zone.album("Iceland", photos = 2, at = REYKJAVIK)
        zone.album("Rome", photos = 3, at = ROME)
        zone.album("Garden", photos = 1)
        install(); launch(); setUp()

        post("/map")
        val shown = awaitMap()
        assertEquals("2 of 3 albums on the map", shown.string("subtitle"), "Garden has no location")
        // Long enough for the first tiles to draw; the frame is for a person, never compared.
        delay(TILES_MS)
        screenshot("map-albums")

        val pin = state().clusters().indexOfFirst { it.memberCount() == 1 && it.clusterAlbums() == listOf(iceland.toString()) }
        assertTrue(pin >= 0, "Iceland is a pin of its own: ${state().clusters()}")
        val opened = post("/map/tap?cluster=$pin")
        assertEquals("grid/$iceland", opened.string("screen"), "a pin opens its album")
        assertTrue(requireNotNull(opened.map()).flag("showing"), "on its own map, not its grid")
        val photos = awaitState("the album's photos on its map") { it.map()?.get("camera") is JsonObject }
        assertEquals("2 of 2 photos on the map", photos.string("subtitle"))
        delay(TILES_MS)
        screenshot("map-album")

        val back = post("/nav?to=back")
        assertEquals("albums", back.string("screen"))
        assertTrue(requireNotNull(back.map()).flag("showing"), "Back returns to the map")
    }

    @Test
    fun aClusterZoomsInAndAlbumsInOneSpotAreListed() = iosScenario("map-cluster") {
        val first = zone.album("Reykjavík 2019", photos = 1, at = REYKJAVIK)
        val second = zone.album("Reykjavík 2023", photos = 1, at = REYKJAVIK)
        zone.album("Akureyri", photos = 1, at = AKUREYRI)
        zone.album("Rome", photos = 1, at = ROME)
        install(); launch(); setUp()

        post("/map")
        awaitMap()
        post("/map/camera?latitude=64.9&longitude=-20.0&zoom=2.0")
        val over = settled()
        val iceland = over.clusters().indexOfFirst { it.memberCount() == 3 }
        assertTrue(iceland >= 0, "Iceland's three albums are one circle: ${over.clusters()}")
        delay(TILES_MS)
        screenshot("map-cluster")

        post("/map/tap?cluster=$iceland")
        val zoomed = settled()
        assertTrue(zoomed.zoom() > 2.0, "the tap zoomed in, to ${zoomed.zoom()}")
        val pair = zoomed.clusters().indexOfFirst { it.memberCount() == 2 }
        assertTrue(pair >= 0, "the two in one spot are still one circle: ${zoomed.clusters()}")

        val listed = post("/map/tap?cluster=$pair")
        val sheet = requireNotNull(listed.map()).getValue("sheet").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf(first.toString(), second.toString()), sheet, "listed rather than zoomed")
        delay(1_000)
        screenshot("map-sheet")

        val opened = post("/map/sheet?album=$second")
        assertEquals("grid/$second", opened.string("screen"))
        assertTrue(requireNotNull(opened.map()).flag("showing"), "opened on its map, as its pin would be")
    }

    @Test
    fun aSearchNarrowsTheMap() = iosScenario("map-search") {
        zone.album("Reykjavík 2019", photos = 1, at = REYKJAVIK)
        zone.album("Reykjavík 2023", photos = 1, at = REYKJAVIK)
        zone.album("Reykjanes", photos = 1)
        zone.album("Rome", photos = 1, at = ROME)
        install(); launch(); setUp()

        post("/search?q=reyk")
        post("/map")
        assertEquals("2 of 3 matching on the map", awaitMap().string("subtitle"), "Reykjanes has no location")
    }

    @Test
    fun anAlbumsMapOpensTheViewerAtThePhotoTapped() = iosScenario("map-photos") {
        val id = zone.album("Ring Road", photos = 3, places = listOf(REYKJAVIK, null, AKUREYRI))
        install(); launch(); setUp()

        post("/nav?to=album/$id")
        post("/map")
        assertEquals(
            "2 of 3 photos on the map",
            awaitState("the album's map subtitle") { it.string("subtitle").endsWith("on the map") }.string("subtitle"),
        )
        awaitMap()
        delay(TILES_MS)
        screenshot("map-photos")

        val pin = settled().clusters().indexOfFirst { it.memberCount() == 1 && it.clusterPhotos() == listOf(2) }
        assertTrue(pin >= 0, "the third photo is a pin of its own: ${state().clusters()}")
        val opened = post("/map/tap?cluster=$pin")
        assertEquals("photo/$id/2", opened.string("screen"), "the viewer opens at that photo")
    }

    /** Waits for the showing map to be framed. */
    private suspend fun IosScenario.awaitMap(): JsonObject =
        awaitState("the map") { state -> state.map()?.let { it["camera"] is JsonObject } == true }

    /**
     * The state once MapLibre has landed a camera move. It reports only the settled position, so
     * the camera stops changing when it has; two equal readings apart is settled.
     */
    private suspend fun IosScenario.settled(): JsonObject {
        var last = state()
        repeat(20) {
            delay(500)
            val now = state()
            if (now.camera() == last.camera()) return now
            last = now
        }
        return last
    }

    private companion object {
        val REYKJAVIK = 64.14 to -21.94
        val AKUREYRI = 65.68 to -18.09
        val ROME = 41.9 to 12.5
        const val TILES_MS = 3_000L
    }
}

private fun JsonObject.map(): JsonObject? = get("map") as? JsonObject

private fun JsonObject.camera(): JsonObject? = map()?.get("camera") as? JsonObject

private fun JsonObject.zoom(): Double = requireNotNull(camera()).getValue("zoom").jsonPrimitive.double

private fun JsonObject.clusters(): List<JsonObject> =
    requireNotNull(map()).getValue("clusters").jsonArray.map { it.jsonObject }

// Named for the cluster rather than `count`/`albums`/`photos`: a JsonObject is a Map, which already
// has `count()`, and `Harness.kt` owns `albums()` and `photos()` for `/state`'s top-level lists.
private fun JsonObject.memberCount(): Int = getValue("count").jsonPrimitive.int

private fun JsonObject.clusterAlbums(): List<String> = getValue("albums").jsonArray.map { it.jsonPrimitive.content }

private fun JsonObject.clusterPhotos(): List<Int> = getValue("photos").jsonArray.map { it.jsonPrimitive.int }
