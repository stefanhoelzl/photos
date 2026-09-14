package net.stho.photos.iostest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §6's download and clear, checked against the app container's own `blobs/` and `packs/`. */
class CacheTest {

    @Test
    fun downloadingAnAlbumFetchesExactlyItsObjectsAndClearingKeepsItsPack() = iosScenario("cache-download-clear") {
        val album = zone.mediaAlbum("Weekend")
        install(); launch(); setUp()
        awaitTrue("the thumbnail pack") { packsOnDisk().isNotEmpty() }

        post("/cache?album=${album.id}&action=download")
        awaitState("the album to be held") { it.album("Weekend").cache().flag("complete") }
        assertEquals(album.objectIds.map { it.toString() }.toSet(), blobsOnDisk(), "exactly the rows' six objects")
        screenshot("cache-downloaded")

        post("/cache?album=${album.id}&action=clear")
        awaitState("the album to be cleared") { it.album("Weekend").cache().flag("empty") }
        assertTrue(blobsOnDisk().isEmpty(), "clear leaves nothing in blobs/: ${blobsOnDisk()}")
        assertEquals(setOf("${album.thumbsId}.db"), packsOnDisk(), "packs are never cleared (§6)")
    }
}
