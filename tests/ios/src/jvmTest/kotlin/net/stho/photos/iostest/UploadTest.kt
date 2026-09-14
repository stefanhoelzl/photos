package net.stho.photos.iostest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
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
    fun photosFromTheLibraryLandAsAnUploadedAlbum() = iosScenario("upload-library") {
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
        post("/upload/name?name=${NAME.encoded()}")
        val albumId = Uuid.parse(post("/upload/confirm").uploads().single().string("album"))
        screenshot("upload-progress")

        awaitState("the upload to land") { state ->
            val upload = state.uploads().single()
            check(upload.string("stage") != "Failed") { "the upload failed: ${upload.nullableString("failure")}" }
            upload.string("stage") == "Done"
        }
        awaitState("the album in the list") { state -> state.albums().any { it.string("name") == NAME } }

        val shard = assertNotNull(zone.shard(albumId))
        assertEquals(AlbumState.UPLOADED, shard.info.state)
        assertEquals(0, shard.info.encodingVersion)
        assertEquals(ids.size, shard.photos.size, "one row per asset picked")
        assertTrue(shard.objectIds.none { it.isContentAddressed }, "the phone never hashes (§2)")
        assertEquals(shard.photos.size, shard.photos.map { it.filename.lowercase() }.toSet().size, "no two rows claim a name")
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

    @Test
    fun anAlbumStillUploadingIsNotListedButOneThatLandedIs() = iosScenario("upload-visibility") {
        zone.album("In flight", photos = 2, state = AlbumState.UPLOADING)
        zone.album("Landed", photos = 2, state = AlbumState.UPLOADED)
        install(); launch()

        val state = setUp()

        assertEquals(listOf("Landed"), state.albums().map { it.string("name") })
    }

    private fun JsonObject.picker(): JsonObject = getValue("picker").jsonObject

    private fun JsonObject.uploads(): List<JsonObject> = getValue("uploads").jsonArray.map { it.jsonObject }

    private companion object {
        const val NAME = "From the phone"
        const val STILL = "photo.heic"
        const val VIDEO = "video.mp4"
    }
}
