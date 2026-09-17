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
import net.stho.photos.app.Naming
import net.stho.photos.app.Resolution
import net.stho.photos.app.Screen
import net.stho.photos.app.UploadModel
import net.stho.photos.app.UploadTarget
import net.stho.photos.app.UploadStage
import net.stho.photos.catalog.AlbumState
import net.stho.photos.model.MediaType

/**
 * §8 on the harness: a gallery album goes up as an addition — to a new album or to one that exists —
 * lands `uploaded`, and appears.
 *
 * The zone is asserted exhaustively for the rows, because those are the contract §7's pull reads
 * back — which file it archives under which name — and a mistake there costs a photograph rather
 * than a pixel.
 */
class UploadTest {

    @Test
    fun aGalleryAlbumLandsAsANewAlbumAndAppears() = runBlocking {
        scenario("upload") {
            val folder = zone.galleryAlbum(gallery, "Weekend")
            launch(withGallery = true)

            val uploadId = uploadWholeGalleryAlbum(expectedParent = null, screenshots = "upload") { naming ->
                assertEquals("Weekend", naming.text, "the path is pre-filled from the gallery album")
                assertNull(naming.selected, "a path no album has is nothing to upload to yet")
                assertTrue(uploads.addNew())
                assertEquals(UploadTarget.Kind.New, uploads.picker.value.naming?.selected?.kind)
            }

            val addition = assertNotNull(zone.shard(uploadId))
            val albumId = assertNotNull(addition.info.addsTo, "every upload is an addition (§8)")
            assertNull(zone.shard(albumId), "a new album is made by the laptop's pull, not by the phone")
            await("the new album in the list") { ui -> ui.albums.any { it.id == albumId && it.name == "Weekend" } }

            assertEquals(AlbumState.UPLOADED, addition.info.state)
            assertEquals(0, addition.info.encodingVersion, "as uploaded, never encoded here")
            assertNull(addition.info.parent, "from the root, a top-level album")
            assertEquals("Weekend", addition.info.name)
            val rows = addition.photos.associateBy { it.filename }
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

            assertTrue(addition.objectIds.none { it.isContentAddressed }, "the phone never hashes (§2)")
            assertNotNull(zone.bytes(assertNotNull(addition.info.thumbsId)), "the pack went up with the photos")
            assertTrue(File(folder, "IMG_0001.heic").isFile, "nothing leaves the gallery unless asked")
            assertFalse(File(cacheRoot.toString(), "uploads/$uploadId").exists(), "a finished upload leaves no state behind")
            screenshot("upload-landed")
        }
    }

    @Test
    fun anUploadStartedInAContainerMakesANewAlbumInsideIt() = runBlocking {
        scenario("upload-container") {
            zone.galleryAlbum(gallery, "Glacier")
            val trips = zone.album("Trips", photos = 0, thumbnails = false)
            zone.album("Alps", photos = 2, parent = trips)
            val ui = launch(withGallery = true)

            model.open(ui.albums.single { it.id == trips })
            assertTrue(model.state.value.screen is Screen.Container, "Trips holds an album, so it is a container")
            val uploadId = uploadWholeGalleryAlbum(expectedParent = trips) { naming ->
                assertEquals("Trips / Glacier", naming.text, "the container's path, then the gallery album's name")
                assertTrue(uploads.addNew())
            }

            val addition = assertNotNull(zone.shard(uploadId))
            assertEquals(trips, addition.info.parent)
            val albumId = assertNotNull(addition.info.addsTo)
            await("the new album inside the container") { it.albums.any { album -> album.id == albumId && album.parent == trips } }
        }
    }

    /**
     * A second upload into a new album the laptop has not pulled: the dialog lists it, typing its path
     * picks it, and the photos go into that one album rather than a second of the same name.
     */
    @Test
    fun aSecondUploadIntoANewAlbumGoesIntoTheSameAlbum() = runBlocking {
        scenario("upload-twice") {
            zone.galleryAlbum(gallery, "Weekend")
            launch(withGallery = true)

            val first = uploadWholeGalleryAlbum(expectedParent = null) { assertTrue(uploads.addNew()) }
            val albumId = assertNotNull(assertNotNull(zone.shard(first)).info.addsTo)
            await("the new album in the list") { ui -> ui.albums.any { it.id == albumId } }

            val second = uploadWholeGalleryAlbum(expectedParent = null) { naming ->
                assertEquals(albumId, naming.selected?.id, "its path picks the album, not a new one")
            }

            assertEquals(albumId, assertNotNull(zone.shard(second)).info.addsTo)
            val listed = await("both uploads in the one album") { ui -> ui.albums.any { it.id == albumId && it.photoCount == 6 } }
            assertEquals(1, listed.albums.count { it.name == "Weekend" })
        }
    }

    /** A path the phone cannot make an album at says why, and nothing can be uploaded to it. */
    @Test
    fun aPathThroughAPhotoAlbumIsRefused() = runBlocking {
        scenario("upload-refused") {
            zone.galleryAlbum(gallery, "Weekend")
            zone.album("Alps", photos = 2)
            launch(withGallery = true)

            val screen = assertNotNull(model.openUpload())
            uploads.open(screen.parent, screen.addTo)
            awaitTrue("the gallery's albums") { uploads.picker.value.albums.isNotEmpty() }
            uploads.chooseAlbum(uploads.picker.value.albums.single())

            uploads.type("Alps / Weekend")
            val naming = assertNotNull(uploads.picker.value.naming)
            assertEquals(Resolution.Refused("\"Alps\" holds photos, not albums"), naming.resolution)
            assertFalse(uploads.addNew())
            assertNull(uploads.confirm())
        }
    }

    @Test
    fun askingToDeleteFromTheGalleryRemovesItsCopiesOnceTheAlbumHasLanded() = runBlocking {
        scenario("upload-delete") {
            val folder = zone.galleryAlbum(gallery, "Weekend")
            launch(withGallery = true)

            uploadWholeGalleryAlbum(expectedParent = null) {
                assertTrue(uploads.addNew())
                uploads.deleteAfterUpload(true)
            }

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
                assertTrue(uploads.addNew())
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

    /**
     * §8's addition, on the harness: upload from inside an album pre-selects it, and the photos go
     * into it. They go up as an addition naming the album — not by rewriting the album's shard —
     * under the camera's own names, and the album's grid shows them, thumbnails and all, from the
     * addition's pack beside its own.
     */
    @Test
    fun aGalleryAlbumAddedToAnAlbumLandsAsAnAdditionAndShowsInItsGrid() = runBlocking {
        scenario("upload-add") {
            val folder = zone.galleryAlbum(gallery, "Weekend")
            val trips = zone.album("Trips", photos = 0, thumbnails = false)
            val alps = zone.album("Alps", photos = 2, parent = trips)
            val before = assertNotNull(zone.shard(alps))
            val ui = launch(withGallery = true)

            model.open(ui.albums.single { it.id == alps })
            val additionId = uploadWholeGalleryAlbum(expectedParent = trips, addTo = alps, screenshots = "upload-add") { naming ->
                assertEquals("Trips / Alps", naming.text)
                assertEquals(alps, naming.selected?.id, "the album the upload started in is pre-selected")
                uploads.deleteAfterUpload(true)
            }

            val addition = assertNotNull(zone.shard(additionId))
            assertEquals(alps, addition.info.addsTo)
            assertEquals(AlbumState.UPLOADED, addition.info.state)
            assertEquals("Alps", addition.info.name, "the album's name, for when it is gone before the merge")
            assertEquals(trips, addition.info.parent)
            assertEquals(setOf("IMG_0001.heic", "IMG_0002.mp4", "IMG_0003.heic"), addition.photos.map { it.filename }.toSet())
            assertEquals(before, zone.shard(alps), "the phone never rewrites the album it adds to")
            assertFalse(folder.exists(), "deleting from the gallery works the same when adding")

            val grid = await("the added photos in the album's grid") { it.photos.size == 5 && it.thumbnails.size == 5 }
            assertEquals(Screen.Grid(alps, "Alps"), grid.screen)
            assertEquals("5 photos", grid.photosSubtitle)
            screenshot("upload-add-landed")
        }
    }

    /**
     * Additions naming an album no shard is — gone before the laptop merged them, or a new one not
     * pulled yet — are listed as that one album, where they said it is.
     */
    @Test
    fun additionsWhoseAlbumIsNotThereAreListedAsThatAlbum() = runBlocking {
        scenario("upload-add-orphan") {
            val trips = zone.album("Trips", photos = 0, thumbnails = false)
            zone.album("Rome", photos = 1, parent = trips)
            val albumId = kotlin.uuid.Uuid.random()
            zone.addition(to = albumId, name = "Alps", photos = 2, parent = trips)
            zone.addition(to = albumId, name = "Alps", photos = 1, parent = trips)

            val ui = launch()

            val alps = ui.albums.single { it.name == "Alps" }
            assertEquals(albumId, alps.id)
            assertEquals(trips, alps.parent)
            assertEquals(3, alps.photoCount)
        }
    }

    /** The fixture gallery album's photos in the order an upload sends them. */
    private val oldestFirst = listOf("IMG_0001.heic", "IMG_0002.mp4", "IMG_0003.heic")

    /**
     * §8's two orders. The grid shows the newest photo first — the picker answers "what did I just
     * shoot" — while the upload it makes still sends the oldest first, so a clashing filename lands
     * on the newer photo and a whole gallery album names its photos the same way a loose pick does.
     */
    @Test
    fun thePickerShowsTheNewestFirstAndSendsTheOldestFirst() = runBlocking {
        scenario("upload-order") {
            zone.galleryAlbum(gallery, "Weekend")
            launch(withGallery = true)

            val screen = assertNotNull(model.openUpload())
            uploads.open(screen.parent, screen.addTo)
            awaitTrue("the gallery's albums and thumbnails") {
                val picker = uploads.picker.value
                picker.albums.isNotEmpty() && picker.thumbnails.size == picker.assets.size
            }

            val shown = uploads.picker.value.assets
            assertEquals(listOf("IMG_0003.heic", "IMG_0002.mp4", "IMG_0001.heic"), shown.map { it.filename })

            uploads.setSelection(shown.map { it.id }.toSet())
            uploads.chooseSelected()
            assertEquals(oldestFirst, uploads.namedFilenames(), "what the loose pick sends")

            uploads.dismissNaming()
            uploads.chooseAlbum(uploads.picker.value.albums.single())
            assertEquals(oldestFirst, uploads.namedFilenames(), "and a whole gallery album alike")
        }
    }

    /** The harness names an asset by its path under the gallery root, so a row's name is its last segment. */
    private fun UploadModel.namedFilenames() =
        assertNotNull(picker.value.naming).assetIds.map { it.substringAfterLast('/') }

    /**
     * Picks the gallery's one album whole, lets [choose] settle where it goes — the dialog as
     * pre-filled — uploads, and waits for it to land. The upload's id, which is its addition's.
     */
    private suspend fun Scenario.uploadWholeGalleryAlbum(
        expectedParent: kotlin.uuid.Uuid?,
        addTo: kotlin.uuid.Uuid? = null,
        /** A name prefix for frames of the picker and the album dialog, for a person to look at. */
        screenshots: String? = null,
        choose: (Naming) -> Unit = {},
    ): kotlin.uuid.Uuid {
        val screen = assertNotNull(model.openUpload())
        assertEquals(expectedParent, screen.parent)
        assertEquals(addTo, screen.addTo)
        uploads.open(screen.parent, screen.addTo)
        awaitTrue("the gallery's albums and thumbnails") {
            val picker = uploads.picker.value
            picker.albums.isNotEmpty() && picker.thumbnails.size == picker.assets.size
        }
        screenshots?.let { screenshot("$it-picker") }
        uploads.chooseAlbum(uploads.picker.value.albums.single())
        choose(assertNotNull(uploads.picker.value.naming))
        screenshots?.let { screenshot("$it-album") }
        val uploadId = assertNotNull(uploads.confirm())
        model.back()
        awaitTrue("the upload to land") {
            val status = uploads.statuses.value.single { it.albumId == uploadId }
            check(status.stage != UploadStage.Failed) { "upload failed: ${status.failure}" }
            status.stage == UploadStage.Done
        }
        return uploadId
    }
}
