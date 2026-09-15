package net.stho.photos.apptest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.stho.photos.app.Screen
import net.stho.photos.app.UploadStage
import net.stho.photos.catalog.AlbumState
import net.stho.photos.model.MediaType

/**
 * §8 on the harness: a gallery album goes up as a new album, lands `uploaded`, and appears.
 *
 * The zone is asserted exhaustively for the rows, because those are the contract §7's pull reads
 * back — which file it archives under which name — and a mistake there costs a photograph rather
 * than a pixel.
 */
class UploadTest {

    @Test
    fun aGalleryAlbumLandsAsAnUploadedAlbumAndAppears() = runBlocking {
        scenario("upload") {
            val folder = zone.galleryAlbum(gallery, "Weekend")
            launch(withGallery = true)

            val albumId = uploadWholeGalleryAlbum(expectedParent = null, screenshots = "upload")
            await("the new album in the list") { ui -> ui.albums.any { it.id == albumId } }

            val shard = assertNotNull(zone.shard(albumId))
            assertEquals(AlbumState.UPLOADED, shard.info.state)
            assertEquals(0, shard.info.encodingVersion, "as uploaded, never encoded here")
            assertNull(shard.info.parent, "from the root, a top-level album")
            assertEquals("Weekend", shard.info.name)
            val rows = shard.photos.associateBy { it.filename }
            assertEquals(setOf("IMG_0001.heic", "IMG_0002.mp4", "IMG_0003.heic"), rows.keys)

            val still = rows.getValue("IMG_0001.heic")
            assertEquals(MediaType.PHOTO, still.mediaType)
            assertContentEquals(File(folder, "IMG_0001.heic").readBytes(), zone.bytes(assertNotNull(still.imageId)))

            val video = rows.getValue("IMG_0002.mp4")
            assertEquals(MediaType.VIDEO, video.mediaType)
            assertNull(video.imageId, "no poster: the pull archives image_id before video_id")
            assertContentEquals(File(folder, "IMG_0002.mp4").readBytes(), zone.bytes(assertNotNull(video.videoId)))

            val live = rows.getValue("IMG_0003.heic")
            assertEquals(MediaType.LIVE_PHOTO, live.mediaType)
            assertEquals(live.imageId, live.liveStillId, "the still is the viewing image until the laptop encodes it")
            assertEquals("IMG_0003.mov", live.liveVideoFilename)
            assertContentEquals(File(folder, "IMG_0003.heic").readBytes(), zone.bytes(assertNotNull(live.liveStillId)))
            assertContentEquals(File(folder, "IMG_0003.mov").readBytes(), zone.bytes(assertNotNull(live.liveVideoId)))

            assertTrue(shard.objectIds.none { it.isContentAddressed }, "the phone never hashes (§2)")
            assertNotNull(zone.bytes(assertNotNull(shard.info.thumbsId)), "the pack went up with the album")
            assertTrue(File(folder, "IMG_0001.heic").isFile, "nothing leaves the gallery unless asked")
            assertFalse(File(cacheRoot.toString(), "uploads/$albumId").exists(), "a finished upload leaves no state behind")
            screenshot("upload-landed")
        }
    }

    @Test
    fun anUploadStartedInAContainerBecomesItsSubAlbum() = runBlocking {
        scenario("upload-container") {
            zone.galleryAlbum(gallery, "Glacier")
            val trips = zone.album("Trips", photos = 0, thumbnails = false)
            zone.album("Alps", photos = 2, parent = trips)
            val ui = launch(withGallery = true)

            model.open(ui.albums.single { it.id == trips })
            assertTrue(model.state.value.screen is Screen.Container, "Trips holds an album, so it is a container")
            val albumId = uploadWholeGalleryAlbum(expectedParent = trips)

            assertEquals(trips, assertNotNull(zone.shard(albumId)).info.parent)
            await("the new album inside the container") { it.albums.any { album -> album.id == albumId } }
        }
    }

    @Test
    fun askingToDeleteFromTheGalleryRemovesItsCopiesOnceTheAlbumHasLanded() = runBlocking {
        scenario("upload-delete") {
            val folder = zone.galleryAlbum(gallery, "Weekend")
            launch(withGallery = true)

            uploadWholeGalleryAlbum(expectedParent = null) { uploads.deleteAfterUpload(true) }

            // The still, the video and both halves of the pair — and then the album they left empty.
            assertFalse(folder.exists(), "a gallery album chosen whole goes with its photos")
        }
    }

    /** Deleting never takes what did not go up: a photo added after the album was chosen keeps its album. */
    @Test
    fun aGalleryAlbumThatGainedAPhotoSinceItWasChosenIsKept() = runBlocking {
        scenario("upload-delete-grown") {
            val folder = zone.galleryAlbum(gallery, "Weekend")
            launch(withGallery = true)

            uploadWholeGalleryAlbum(expectedParent = null) {
                uploads.deleteAfterUpload(true)
                File(folder, "IMG_0001.heic").copyTo(File(folder, "IMG_0004.heic"))
            }

            assertEquals(listOf("IMG_0004.heic"), folder.list().orEmpty().toList(), "the uploaded photos went, the album stayed")
        }
    }

    /** Phase 1's reader rule, end to end: a shard written first, naming objects not yet there, is not an album. */
    @Test
    fun anAlbumStillUploadingIsNotListedButOneThatLandedIs() = runBlocking {
        scenario("upload-visibility") {
            zone.album("In flight", photos = 2, state = AlbumState.UPLOADING)
            zone.album("Landed", photos = 2, state = AlbumState.UPLOADED)

            val ui = launch()

            assertEquals(listOf("Landed"), ui.albums.map { it.name })
        }
    }

    @Test
    fun anAlbumOfPhotosOffersNoUpload() = runBlocking {
        scenario("upload-grid") {
            zone.album("Alps", photos = 2)
            val ui = launch(withGallery = true)

            model.open(ui.albums.single())
            assertNull(model.openUpload(), "an album of photos cannot hold a sub-album (§2)")
            assertTrue(model.state.value.screen is Screen.Grid)
        }
    }

    /** Picks the gallery's one album whole, names it as prefilled, uploads, and waits for it to land. */
    private suspend fun Scenario.uploadWholeGalleryAlbum(
        expectedParent: kotlin.uuid.Uuid?,
        /** A name prefix for frames of the picker and the name dialog, for a person to look at. */
        screenshots: String? = null,
        beforeConfirm: () -> Unit = {},
    ): kotlin.uuid.Uuid {
        val screen = assertNotNull(model.openUpload())
        assertEquals(expectedParent, screen.parent)
        uploads.open(screen.parent, screen.parentName)
        awaitTrue("the gallery's albums and thumbnails") {
            val picker = uploads.picker.value
            picker.albums.isNotEmpty() && picker.thumbnails.size == picker.assets.size
        }
        screenshots?.let { screenshot("$it-picker") }
        val galleryAlbum = uploads.picker.value.albums.single()
        uploads.chooseAlbum(galleryAlbum)
        assertEquals(galleryAlbum.name, uploads.picker.value.naming?.name, "the name is prefilled from the gallery album")
        screenshots?.let { screenshot("$it-name") }
        beforeConfirm()
        val albumId = assertNotNull(uploads.confirm())
        model.back()
        awaitTrue("the upload to land") {
            val status = uploads.statuses.value.single { it.albumId == albumId }
            check(status.stage != UploadStage.Failed) { "upload failed: ${status.failure}" }
            status.stage == UploadStage.Done
        }
        return albumId
    }
}
