package net.stho.photos.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** §6's clustering, as numbers: which points merge at which zoom, and when a cluster splits. */
class ClusterIndexTest {
    private val munich = Mercator.project(48.137, 11.575)

    /** About 400 m away: one circle over the city, two pins over the street. */
    private val munichEast = Mercator.project(48.139, 11.580)
    private val sydney = Mercator.project(-33.86, 151.21)

    @Test
    fun placesFarApartAreNeverMerged() {
        val index = ClusterIndex(listOf(munich, sydney))
        for (zoom in 0..MapLimits.MAX_ZOOM) {
            assertEquals(2, index.at(zoom.toDouble()).size, "at zoom $zoom")
        }
    }

    @Test
    fun nearbyPlacesMergeZoomedOutAndSplitZoomedIn() {
        val index = ClusterIndex(listOf(munich, munichEast))
        assertEquals(listOf(listOf(0, 1)), index.at(5.0).map { it.members })
        assertEquals(listOf(listOf(0), listOf(1)), index.at(18.0).map { it.members })
    }

    @Test
    fun aClusterSitsAtItsMembersCentroid() {
        val cluster = ClusterIndex(listOf(munich, munichEast)).at(5.0).single()
        assertEquals((munich.x + munichEast.x) / 2, cluster.center.x, 1e-12)
        assertEquals((munich.y + munichEast.y) / 2, cluster.center.y, 1e-12)
    }

    /** A cluster at one zoom is a union of clusters at the next, so zooming in never regroups. */
    @Test
    fun zoomingInOnlyEverSplitsClusters() {
        val random = Random(7)
        val points = List(300) { Mercator.project(random.nextDouble(44.0, 50.0), random.nextDouble(5.0, 15.0)) }
        val index = ClusterIndex(points)
        for (zoom in 0 until MapLimits.MAX_ZOOM) {
            val owner = IntArray(points.size) { -1 }
            index.at(zoom.toDouble()).forEachIndexed { at, cluster ->
                cluster.members.forEach { member ->
                    assertEquals(-1, owner[member], "point $member is in one cluster at zoom $zoom")
                    owner[member] = at
                }
            }
            assertEquals(-1, owner.indexOf(-1), "every point is placed at zoom $zoom")
            for (deeper in index.at(zoom + 1.0)) {
                assertEquals(1, deeper.members.map { owner[it] }.toSet().size, "zoom ${zoom + 1} inside zoom $zoom")
            }
        }
    }

    @Test
    fun theExpansionIsTheFirstLevelAtWhichTheClusterSplits() {
        val index = ClusterIndex(listOf(munich, munichEast))
        val expansion = assertNotNull(index.expansion(index.at(5.0).single(), 5))
        assertEquals(2, index.at(expansion.toDouble()).size)
        assertEquals(1, index.at(expansion - 1.0).size)
    }

    /** Several trips to one town: no zoom separates them, so the map lists them instead (§6). */
    @Test
    fun placesInTheSameSpotCannotBeSeparated() {
        val index = ClusterIndex(listOf(munich, sydney, munich))
        val together = index.at(3.0).single { it.members.size == 2 }
        assertEquals(listOf(0, 2), together.members)
        assertNull(index.expansion(together, 3))
        assertEquals(listOf(0, 2), index.at(MapLimits.MAX_ZOOM.toDouble()).single { !it.isPin }.members)
    }

    @Test
    fun theLevelDrawnIsTheWholePartOfTheZoom() {
        val index = ClusterIndex(listOf(munich))
        assertEquals(4, index.level(4.99))
        assertEquals(0, index.level(-1.0))
        assertEquals(MapLimits.MAX_ZOOM, index.level(30.0))
    }
}
