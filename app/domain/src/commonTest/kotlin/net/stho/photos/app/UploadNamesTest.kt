package net.stho.photos.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** §3: two rows in one album may not claim one name, and the pull writes files under these names. */
class UploadNamesTest {

    @Test
    fun theCameraStemKeepsTheExtensionOfTheBytesSent() {
        // An edited photo exports as `FullSizeRender.heic`; the row keeps the camera's stem.
        assertEquals("IMG_1234.heic" to null, UploadNames().claim("IMG_1234", "heic"))
    }

    @Test
    fun aClashGainsASuffix() {
        val names = UploadNames()
        names.claim("IMG_0001", "heic")
        // The clash is found ignoring case; the extension stays as the bytes' own.
        assertEquals("IMG_0001 (2).HEIC" to null, names.claim("IMG_0001", "HEIC"))
        assertEquals("IMG_0001 (3).heic" to null, names.claim("IMG_0001", "heic"))
    }

    @Test
    fun aLivePhotoClaimsItsMovTooSoAVideoCannotTakeIt() {
        val names = UploadNames()
        assertEquals("IMG_0007.heic" to "IMG_0007.MOV", names.claim("IMG_0007", "heic", pairedExtension = "MOV"))
        assertEquals("IMG_0007 (2).mov" to null, names.claim("IMG_0007", "mov"))
    }

    @Test
    fun aPairMovesTogetherWhenEitherHalfClashes() {
        val names = UploadNames()
        names.claim("IMG_0009", "MOV")
        assertEquals("IMG_0009 (2).heic" to "IMG_0009 (2).MOV", names.claim("IMG_0009", "heic", pairedExtension = "MOV"))
    }
}
