// `NSUUID()` and `NSURL.fileURLWithPath` are Objective-C initialisers, which Kotlin/Native
// still gates behind BetaInteropApi.
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package net.stho.photos.adapter.ios

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID

/**
 * The two things this adapter does beyond naming a directory: it creates it, and it takes the
 * directory out of the iCloud backup. The second is the one worth a test — it is a single
 * resource value that would fail silently, and nothing on screen would ever show it missing.
 */
class ContainerPathsTest {

    private val container: NSURL = NSURL.fileURLWithPath(
        NSTemporaryDirectory() + "photos-container-" + NSUUID().UUIDString,
        isDirectory = true,
    )

    @AfterTest
    fun removeContainer() {
        NSFileManager.defaultManager.removeItemAtURL(container, error = null)
    }

    @Test
    fun `creates the cache root under the container`() {
        val paths = ContainerPaths(container)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(paths.cacheRoot))
        assertEquals(container.path + "/net.stho.photos", paths.cacheRoot)
    }

    @Test
    fun `excludes the cache root from backup`() {
        val paths = ContainerPaths(container)
        val url = NSURL.fileURLWithPath(paths.cacheRoot, isDirectory = true)
        memScoped {
            val value = alloc<ObjCObjectVar<Any?>>()
            val failure = alloc<ObjCObjectVar<NSError?>>()
            url.getResourceValue(value.ptr, forKey = NSURLIsExcludedFromBackupKey, error = failure.ptr)
            assertNull(failure.value, "reading the backup flag failed")
            assertEquals(true, value.value, "§6 keeps ~0.5 GB of re-downloadable packs; iCloud must not")
        }
    }

    @Test
    fun `config and cache are one directory`() {
        val paths = ContainerPaths(container)
        assertEquals(paths.cacheRoot, paths.configRoot)
    }
}
