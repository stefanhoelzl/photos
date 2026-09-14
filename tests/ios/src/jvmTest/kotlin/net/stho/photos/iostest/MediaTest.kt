package net.stho.photos.iostest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonObject
import net.stho.photos.model.MediaType

/**
 * Motion reaches the viewer on the phone: the files the app hands its players are the bytes the
 * zone declared, read straight out of the app's container.
 *
 * What these cannot show is the players themselves — whether AVPlayer and `PHLivePhotoView` open
 * those files. The screenshot is written so a person can look.
 */
class MediaTest {

    @Test
    fun aVideoHandsTheViewerItsTranscodeByteForByte() = iosScenario("media-video") {
        val album = zone.mediaAlbum("Weekend")
        install(); launch(); setUp()

        val index = post("/nav?to=album/${album.id}").photos().indexOfFirst { it.string("type") == "VIDEO" }
        post("/nav?to=photo/${album.id}/$index")
        val opened = awaitState("the transcode") { it.nullableString("videoPath") != null }

        val video = album.row(MediaType.VIDEO)
        assertContentEquals(album.bytesOf(requireNotNull(video.videoId)), File(opened.string("videoPath")).readBytes())
        screenshot("media-video")
    }

    @Test
    fun aLivePhotoHandsTheViewerItsStillAndMovByteForByte() = iosScenario("media-live") {
        val album = zone.mediaAlbum("Weekend")
        install(); launch(); setUp()

        val index = post("/nav?to=album/${album.id}").photos().indexOfFirst { it.string("type") == "LIVE_PHOTO" }
        post("/nav?to=photo/${album.id}/$index")
        val opened = awaitState("both halves of the Live Photo") { it["livePair"] !is kotlinx.serialization.json.JsonNull && it["livePair"] != null }

        val live = album.row(MediaType.LIVE_PHOTO)
        val pair = opened.getValue("livePair").jsonObject
        assertContentEquals(album.bytesOf(requireNotNull(live.liveStillId)), File(pair.string("still")).readBytes())
        assertContentEquals(album.bytesOf(requireNotNull(live.liveVideoId)), File(pair.string("video")).readBytes())
        assertNull(opened.nullableString("videoPath"), "a Live Photo is not a video")

        // The pair is one iOS assembles (FixtureMedia), so the app's own view, handed the app's
        // own files, must get a *full* Live Photo from PHLivePhoto -- not the degraded one it
        // builds from the still alone, and not the `none` an unassemblable pair ends with.
        awaitState("PHLivePhoto to assemble the pair in full") { it.nullableString("livePhoto") == "full" }
        screenshot("media-live")
    }
}
