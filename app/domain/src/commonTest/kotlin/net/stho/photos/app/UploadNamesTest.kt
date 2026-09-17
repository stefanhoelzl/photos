package net.stho.photos.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** The names an upload's rows carry, which the laptop's pull makes unique against the album's folder (§7). */
class UploadNamesTest {

    @Test
    fun theCameraStemKeepsTheExtensionOfTheBytesSent() {
        // An edited photo exports as `FullSizeRender.heic`; the row keeps the camera's stem.
        assertEquals("IMG_1234.heic" to null, UploadNames().claim("IMG_1234", "heic"))
    }

    @Test
    fun aLivePhotosMovIsNamedBesideItsStill() {
        assertEquals("IMG_0007.heic" to "IMG_0007.MOV", UploadNames().claim("IMG_0007", "heic", pairedExtension = "MOV"))
    }

    /** Only the laptop can see the album's folder, so only the laptop makes a name unique (§7). */
    @Test
    fun clashesAreLeftToTheLaptop() {
        val names = UploadNames()
        assertEquals("IMG_0001.heic" to "IMG_0001.MOV", names.claim("IMG_0001", "heic", pairedExtension = "MOV"))
        assertEquals("IMG_0001.heic" to null, names.claim("IMG_0001", "heic"))
    }
}
