package net.stho.photos.iostest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.stho.photos.catalog.AlbumState
import net.stho.photos.model.MediaType

/**
 * §8 on the phone: PhotoKit as the gallery, a background `URLSession` as the transfer.
 *
 * The simulator's photo library is shared by every scenario on the device and holds whatever it
 * shipped with, so the upload takes *every* asset the picker lists and asserts the album against
 * that list rather than against a count it cannot know. Deleting from the library is not driven
 * here: iOS asks through a system alert no control server can answer.
 */
class UploadTest {

    @Test
    fun photosFromTheLibraryLandAsANewAlbum() = iosScenario("upload-library") {
        install()
        allowPhotos()
        addToLibrary(STILL, VIDEO)
        launch(); setUp()

        post("/upload/open")
        val picker = awaitState("the library's assets") { state ->
            state.picker().getValue("assets").jsonArray.any { it.jsonObject.string("type") == "VIDEO" }
        }.picker()
        assertEquals("Full", picker.string("access"))
        val ids = picker.getValue("assets").jsonArray.map { it.jsonObject.string("id") }

        post("/upload/select?ids=${ids.joinToString(",").encoded()}")
        post("/upload/name?album=${NAME.encoded()}&new=true")
        val upload = post("/upload/confirm").uploads().single()
        val uploadId = Uuid.parse(upload.string("album"))
        val albumId = Uuid.parse(upload.string("target"))
        screenshot("upload-progress")

        awaitState("the upload to land") { state ->
            val upload = state.uploads().single()
            check(upload.string("stage") != "Failed") { "the upload failed: ${upload.nullableString("failure")}" }
            upload.string("stage") == "Done"
        }
        awaitState("the album in the list") { state -> state.albums().any { it.string("id") == albumId.toString() && it.string("name") == NAME } }

        // Every upload is an addition (§8): a new album is one no shard is yet, made by the laptop's pull.
        val shard = assertNotNull(zone.shard(uploadId))
        assertEquals(albumId, shard.info.addsTo)
        assertEquals(NAME, shard.info.name)
        assertEquals(AlbumState.UPLOADED, shard.info.state)
        assertEquals(0, shard.info.encodingVersion)
        assertEquals(ids.size, shard.photos.size, "one row per asset picked")
        assertTrue(shard.objectIds.none { it.isContentAddressed }, "the phone never hashes (§2)")
        for (row in shard.photos) {
            val sent = when (row.mediaType) {
                MediaType.VIDEO -> {
                    assertEquals(null, row.imageId, "no poster: the pull archives image_id before video_id")
                    row.videoId
                }
                else -> row.imageId
            }
            val bytes = assertNotNull(zone.bytes(assertNotNull(sent)), "${row.filename} is in the zone")
            assertEquals(row.bytes, bytes.size.toLong(), "${row.filename}'s row names the size that landed")
        }
        assertNotNull(zone.bytes(assertNotNull(shard.info.thumbsId)), "the pack went up with the album")

        // The fixture still went up as the library's own bytes, not re-encoded on the way.
        val still = File(System.getProperty("photos.fixture.media"), STILL).readBytes()
        assertTrue(
            shard.photos.filter { it.mediaType == MediaType.PHOTO }.any { zone.bytes(it.imageId!!)?.contentEquals(still) == true },
            "the seeded HEIC is among the rows, byte for byte",
        )
        screenshot("upload-landed")
    }

    /**
     * §8's addition on the phone: upload from inside an album sends the photos into it, as a shard
     * of their own naming the album, and the album's grid shows them once they land.
     */
    @Test
    fun photosFromTheLibraryAddedToAnAlbumLandAsAnAddition() = iosScenario("upload-add") {
        val alps = zone.album("Alps", photos = 2)
        val before = assertNotNull(zone.shard(alps))
        install()
        allowPhotos()
        addToLibrary(STILL)
        launch(); setUp()

        post("/nav?to=album/$alps")
        post("/upload/open")
        val picker = awaitState("the library's assets") { state -> state.picker().assets().isNotEmpty() }.picker()
        assertEquals(alps.toString(), picker.string("addTo"))
        val ids = picker.assets().map { it.string("id") }

        post("/upload/select?ids=${ids.joinToString(",").encoded()}")
        assertEquals(alps.toString(), awaitState("the album pre-selected") { state ->
            state.picker()["naming"] is JsonObject
        }.picker().getValue("naming").jsonObject.getValue("selected").jsonObject.string("id"))
        val additionId = confirmAndLand()

        val addition = assertNotNull(zone.shard(additionId))
        assertEquals(alps, addition.info.addsTo)
        assertEquals(AlbumState.UPLOADED, addition.info.state)
        assertEquals("Alps", addition.info.name)
        assertEquals(ids.size, addition.photos.size, "one row per asset picked")
        assertEquals(before, zone.shard(alps), "the phone never rewrites the album it adds to")
        awaitState("the added photos in the album's grid") { state -> state.getValue("photos").jsonArray.size == 2 + ids.size }
        screenshot("upload-add-landed")
    }

    @Test
    fun anAlbumStillUploadingIsNotListedButOneThatLandedIs() = iosScenario("upload-visibility") {
        zone.album("In flight", photos = 2, state = AlbumState.UPLOADING)
        zone.album("Landed", photos = 2, state = AlbumState.UPLOADED)
        install(); launch()

        val state = setUp()

        assertEquals(listOf("Landed"), state.albums().map { it.string("name") })
    }

    /**
     * §8's delete-from-gallery, answered as a person would: iOS confirms every deletion with an
     * alert, which `SystemAlerts` taps. Only the assets this scenario added are uploaded, so the
     * library's own photos are what proves the picker read the library again afterwards.
     */
    @Test
    fun deletingFromTheLibraryHappensOnceTheAlbumHasLanded() = iosScenario("upload-delete") {
        install()
        allowPhotos()
        // Before launch: xcodebuild installs the app it tests. It answers the deletion alert, and the
        // full-access alert the simulator can raise again mid-upload.
        val tapper = startUiTest(
            "SystemAlerts/testTapAlerts",
            mapOf("PHOTOS_TAP" to "Allow Full Access,Delete"),
            ready = "PHOTOS_TAPPER_READY",
        )
        launch(); setUp()

        val before = libraryIds()
        post("/nav?to=back")
        addToLibrary(STILL, VIDEO)
        post("/upload/open")
        val added = awaitState("the two added assets in the picker") { state ->
            state.picker().assets().count { it.string("id") !in before } == 2
        }.picker().assets().map { it.string("id") }.filterNot { it in before }

        post("/upload/select?ids=${added.joinToString(",").encoded()}")
        post("/upload/name?album=${NAME.encoded()}&new=true&delete=true")
        val albumId = confirmAndLand()
        assertTrue("PHOTOS_TAPPED Delete" in tapper.awaitSuccess(), "iOS asked, and Delete was tapped")

        assertEquals(AlbumState.UPLOADED, assertNotNull(zone.shard(albumId)).info.state, "the album landed first")
        post("/upload/open")
        awaitState("the library without the uploaded assets") { state ->
            val ids = state.picker().assets().map { it.string("id") }.toSet()
            ids.containsAll(before) && added.none { it in ids }
        }
        screenshot("upload-delete")
    }

    /**
     * §8's delete-from-gallery for an album chosen whole: its photos and the album go in one PhotoKit
     * change, for which iOS asks twice — once for the album, once for its photos — so `SystemAlerts`
     * taps Delete on both. `AlbumSeed` makes the album, since `simctl` can add photos but not group
     * them.
     */
    @Test
    fun deletingAWholeGalleryAlbumRemovesTheAlbumToo() = iosScenario("upload-delete-album") {
        install()
        allowPhotos()
        val seeded = startUiTest(
            "AlbumSeed/testSeedAlbum",
            mapOf(
                "PHOTOS_ALBUM_NAME" to GALLERY_ALBUM,
                "PHOTOS_ALBUM_FILES" to listOf(STILL, VIDEO).joinToString(",") { fixture(it).absolutePath },
            ),
        ).awaitSuccess()
        val (galleryAlbum, assetList) = Regex("PHOTOS_SEEDED_ALBUM (\\S+) (\\S+)").find(seeded)?.destructured
            ?: error("the seeder named no album")
        val assetIds = assetList.split(',')
        val tapper = startUiTest(
            "SystemAlerts/testTapAlerts",
            mapOf("PHOTOS_TAP" to "Allow Full Access,Delete,Delete"),
            ready = "PHOTOS_TAPPER_READY",
        )
        launch(); setUp()

        post("/upload/open")
        awaitState("the seeded album in the picker") { state -> state.picker().albums().any { it.string("id") == galleryAlbum } }
        val naming = post("/upload/album?id=${galleryAlbum.encoded()}").picker().getValue("naming").jsonObject
        assertEquals(GALLERY_ALBUM, naming.string("text"), "the path is pre-filled from the gallery album")
        assertEquals(galleryAlbum, naming.string("album"))
        post("/upload/name?new=true&delete=true")
        val albumId = confirmAndLand()
        val taps = Regex("PHOTOS_TAPPED Delete").findAll(tapper.awaitSuccess()).count()
        assertEquals(2, taps, "iOS asked for the album and for its photos, and Delete was tapped on both")

        assertEquals(assetIds.size, assertNotNull(zone.shard(albumId)).photos.size, "the album landed first")
        // Opening the picker empties it until the library has been read, and an empty album list would
        // pass any "not there" check. The simulator's own photos mark the read done: albums and assets
        // arrive in one update, and the deletion finished before the upload counted as done.
        post("/upload/open")
        val picker = awaitState("the library read again") { state -> state.picker().assets().isNotEmpty() }.picker()
        assertTrue(picker.albums().none { it.string("id") == galleryAlbum }, "the gallery album was deleted")
        assertTrue(picker.albums().none { it.string("name") == GALLERY_ALBUM }, "no album by that name is left")
        assertTrue(picker.assets().none { it.string("id") in assetIds }, "its photos were deleted with it")
        screenshot("upload-delete-album")
    }

    /**
     * §8's open question: does an *edited* Live Photo's full-size still and paired video still carry
     * the content identifier that pairs them? Seeded and edited through PhotoKit, uploaded through the
     * app, and handed back to `PHLivePhoto` by the viewer, which must assemble it in full.
     */
    @Test
    fun anEditedLivePhotoStillPairsOnceUploaded() = livePhotoPairs("upload-live-edited", edited = true)

    /** The control: without it, a failure of the edited case could not be told apart from the harness. */
    @Test
    fun anUneditedLivePhotoPairsOnceUploaded() = livePhotoPairs("upload-live-unedited", edited = false)

    private fun livePhotoPairs(label: String, edited: Boolean) = iosScenario(label) {
        install()
        allowPhotos()
        val seeded = startUiTest(
            "LivePhotoSeed/testSeedLivePhoto",
            mapOf(
                "PHOTOS_LIVE_STILL" to fixture(LIVE_STILL).absolutePath,
                "PHOTOS_LIVE_VIDEO" to fixture(LIVE_VIDEO).absolutePath,
                "PHOTOS_LIVE_IDENTIFIER" to LIVE_IDENTIFIER,
                "PHOTOS_LIVE_EDIT" to if (edited) "yes" else "no",
            ),
        ).awaitSuccess()
        val assetId = Regex("PHOTOS_SEEDED (\\S+)").find(seeded)?.groupValues?.get(1)
            ?: error("the seeder named no asset")
        launch(); setUp()

        post("/upload/open")
        val asset = awaitState("the seeded Live Photo in the picker") { state ->
            state.picker().assets().any { it.string("id") == assetId }
        }.picker().assets().single { it.string("id") == assetId }
        assertEquals("LIVE_PHOTO", asset.string("type"))
        post("/upload/select?ids=${assetId.encoded()}")
        post("/upload/name?album=${NAME.encoded()}&new=true")
        val uploadId = confirmAndLand()

        val addition = assertNotNull(zone.shard(uploadId))
        val albumId = assertNotNull(addition.info.addsTo)
        val row = addition.photos.single()
        assertEquals(MediaType.LIVE_PHOTO, row.mediaType)
        assertNotNull(row.liveVideoId, "the paired video went up with the still")

        awaitState("the album in the list") { state -> state.albums().any { it.string("id") == albumId.toString() } }
        post("/nav?to=album/$albumId")
        post("/nav?to=photo/$albumId/0")
        awaitState("both halves of the uploaded Live Photo on disk") { it["livePair"] !is JsonNull && it["livePair"] != null }
        awaitState("PHLivePhoto to assemble the uploaded pair in full") { it.nullableString("livePhoto") == "full" }
        screenshot(label)
    }

    /** The library as the picker lists it, once it has read it. */
    private suspend fun IosScenario.libraryIds(): Set<String> {
        post("/upload/open")
        return awaitState("the library in the picker") { state ->
            state.picker().assets().isNotEmpty()
        }.picker().assets().map { it.string("id") }.toSet()
    }

    /** Upload, and wait for it to land — failing with the upload's own reason if it does not. The upload's id, its addition's. */
    private suspend fun IosScenario.confirmAndLand(): Uuid {
        val albumId = Uuid.parse(post("/upload/confirm").uploads().last().string("album"))
        awaitState("the upload to land") { state ->
            val upload = state.uploads().single { it.string("album") == albumId.toString() }
            check(upload.string("stage") != "Failed") { "the upload failed: ${upload.nullableString("failure")}" }
            upload.string("stage") == "Done"
        }
        return albumId
    }

    private fun JsonObject.picker(): JsonObject = getValue("picker").jsonObject

    private fun JsonObject.assets(): List<JsonObject> = getValue("assets").jsonArray.map { it.jsonObject }

    private fun JsonObject.uploads(): List<JsonObject> = getValue("uploads").jsonArray.map { it.jsonObject }

    private companion object {
        const val NAME = "From the phone"
        const val GALLERY_ALBUM = "Seeded album"
        const val STILL = "photo.heic"
        const val VIDEO = "video.mp4"
        const val LIVE_STILL = "live-still.heic"
        const val LIVE_VIDEO = "live.mov"

        /** `FixtureMedia.LIVE_IDENTIFIER`, spelled again: this suite cannot link that linuxX64 module. */
        const val LIVE_IDENTIFIER = "5E1C9A2B-7D40-4F3E-9B61-2A8C0D4E7F10"
    }
}
