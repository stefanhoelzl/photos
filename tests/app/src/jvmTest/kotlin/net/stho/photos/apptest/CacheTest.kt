package net.stho.photos.apptest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.stho.photos.app.CacheAction

/**
 * §6's explicit download and clear, checked against the disk rather than the strip.
 *
 * The strip is the model's report of what is held; `blobs/` is what is held. A download that
 * fetched one blob too few would still be caught by the model's byte count, but one that fetched
 * a blob too *many* — or a clear that left a file behind — only shows up here.
 */
class CacheTest {

    @Test
    fun downloadingAnAlbumFetchesExactlyItsObjectsAndClearingKeepsItsPack() = runBlocking {
        scenario("cache-download-clear") {
            val album = zone.mediaAlbum("Weekend")
            val ui = launch()
            val row = ui.albums.single()
            // The pack arrives on its own at launch (§6), never by asking. Waited for first, so
            // the clear below is shown to leave something that was genuinely there.
            awaitTrue("the thumbnail pack") { packsOnDisk().isNotEmpty() }

            model.act(row, CacheAction.Download)
            await("the album to be held") { it.cacheOf(row).complete }
            assertEquals(album.objectIds.map { it.toString() }.toSet(), blobsOnDisk(), "exactly the rows' six objects")
            screenshot("cache-downloaded")

            model.act(row, CacheAction.Clear)
            await("the album to be cleared") { it.cacheOf(row).empty }
            assertTrue(blobsOnDisk().isEmpty(), "clear leaves nothing in blobs/: ${blobsOnDisk()}")
            assertEquals(setOf("${album.thumbsId}.db"), packsOnDisk(), "packs are never cleared (§6)")
            screenshot("cache-cleared")
        }
    }
}
