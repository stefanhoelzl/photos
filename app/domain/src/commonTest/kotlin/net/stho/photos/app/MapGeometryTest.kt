package net.stho.photos.app

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The projection, the frame and the stand-in's pan — the arithmetic every pin's position rests on. */
class MapGeometryTest {

    @Test
    fun projectingAPlaceAndBackIsTheSamePlace() {
        for ((latitude, longitude) in listOf(0.0 to 0.0, 48.137 to 11.575, -33.86 to 151.21, 64.14 to -21.94)) {
            val point = Mercator.project(latitude, longitude)
            assertEquals(latitude, Mercator.latitudeOf(point), 1e-9)
            assertEquals(longitude, Mercator.longitudeOf(point), 1e-9)
        }
    }

    @Test
    fun theEquatorOnThePrimeMeridianIsTheMiddleOfTheWorld() {
        assertEquals(WorldPoint(0.5, 0.5), Mercator.project(0.0, 0.0))
    }

    @Test
    fun theCameraCentreIsTheMiddleOfTheViewport() {
        val camera = MapCamera(48.137, 11.575, 9.3)
        val at = camera.toScreen(camera.center, 390.0, 640.0)
        assertEquals(195.0, at.x, 1e-6)
        assertEquals(320.0, at.y, 1e-6)
    }

    @Test
    fun oneZoomLevelCloserDoublesEveryDistanceOnScreen() {
        val rome = Mercator.project(41.9, 12.5)
        val far = MapCamera(48.137, 11.575, 5.0).toScreen(rome, 390.0, 640.0)
        val near = MapCamera(48.137, 11.575, 6.0).toScreen(rome, 390.0, 640.0)
        assertEquals((far.y - 320) * 2, near.y - 320, 1e-6)
    }

    @Test
    fun aPinJustAcrossTheAntimeridianIsDrawnBesideTheCentre() {
        val camera = MapCamera(-17.0, 179.9, 6.0)
        val at = camera.toScreen(Mercator.project(-17.0, -179.9), 390.0, 640.0)
        assertTrue(at.x > 195.0 && at.x < 390.0, "drawn a little east of the centre, at ${at.x}")
    }

    @Test
    fun aFrameShowsEveryPointAndFillsTheViewportAlongOneAxis() {
        val points = listOf(Mercator.project(48.137, 11.575), Mercator.project(41.9, 12.5), Mercator.project(48.85, 2.35))
        val camera = frame(points, 390.0, 640.0)
        val onScreen = points.map { camera.toScreen(it, 390.0, 640.0) }
        val pad = MapLimits.FIT_PADDING
        for (at in onScreen) {
            assertTrue(at.x >= pad - 1e-6 && at.x <= 390 - pad + 1e-6, "x ${at.x} inside the padding")
            assertTrue(at.y >= pad - 1e-6 && at.y <= 640 - pad + 1e-6, "y ${at.y} inside the padding")
        }
        val spanX = onScreen.maxOf { it.x } - onScreen.minOf { it.x }
        val spanY = onScreen.maxOf { it.y } - onScreen.minOf { it.y }
        assertTrue(abs(spanX - (390 - 2 * pad)) < 1e-6 || abs(spanY - (640 - 2 * pad)) < 1e-6, "as close as the box allows")
    }

    @Test
    fun aSinglePlaceIsFramedAtTheTownNotTheDoorstep() {
        val camera = frame(listOf(Mercator.project(64.14, -21.94)), 390.0, 640.0)
        assertEquals(MapLimits.FIT_MAX_ZOOM, camera.zoom)
        assertEquals(64.14, camera.latitude, 1e-9)
    }

    @Test
    fun nothingToFrameShowsTheWorld() {
        assertEquals(MapLimits.WORLD, frame(emptyList(), 390.0, 640.0))
    }

    @Test
    fun draggingTheMapCarriesThePlaceUnderTheFingerWithIt() {
        val camera = MapCamera(48.137, 11.575, 8.0)
        val moved = camera.panned(dx = 100.0, dy = -40.0)
        val at = moved.toScreen(camera.center, 390.0, 640.0)
        assertEquals(295.0, at.x, 1e-6)
        assertEquals(280.0, at.y, 1e-6)
    }

    @Test
    fun zoomStaysBetweenTheWorldAndTheStreet() {
        assertEquals(MapLimits.MAX_ZOOM.toDouble(), MapCamera(0.0, 0.0, 17.5).zoomedBy(3.0).zoom)
        assertEquals(0.0, MapCamera(0.0, 0.0, 0.5).zoomedBy(-3.0).zoom)
    }
}
