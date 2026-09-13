package net.stho.photos.apptest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import net.stho.photos.model.MediaType

/**
 * Motion reaches the viewer: the transcode for a video, both halves for a Live Photo.
 *
 * Asserted on the files the model hands over, not merely on a path being non-null — the bytes
 * on disk must be the bytes declared in the zone, which is what a player will actually open.
 */
class MediaTest {

    @Test
    fun aVideoHandsTheViewerItsTranscodeByteForByte() = runBlocking {
        scenario("media-video") {
            val album = zone.mediaAlbum("Weekend")
            val ui = launch()
            model.open(ui.albums.single())
            model.openPhoto(model.state.value.photos.indexOfFirst { it.mediaType == MediaType.VIDEO })

            val opened = await("the transcode") { it.videoPath != null }
            val video = album.row(MediaType.VIDEO)
            assertContentEquals(album.bytesOf(requireNotNull(video.videoId)), File(requireNotNull(opened.videoPath)).readBytes())
            assertNull(opened.livePair, "a video is not a Live Photo")
            screenshot("media-video")
        }
    }

    @Test
    fun aLivePhotoHandsTheViewerItsStillAndMovByteForByte() = runBlocking {
        scenario("media-live") {
            val album = zone.mediaAlbum("Weekend")
            val ui = launch()
            model.open(ui.albums.single())
            model.openPhoto(model.state.value.photos.indexOfFirst { it.mediaType == MediaType.LIVE_PHOTO })

            val opened = await("both halves of the Live Photo") { it.livePair != null }
            val live = album.row(MediaType.LIVE_PHOTO)
            val pair = requireNotNull(opened.livePair)
            // The untouched still, not the viewing image: the pairing identifier lives only there.
            assertContentEquals(album.bytesOf(requireNotNull(live.liveStillId)), File(pair.still).readBytes())
            assertContentEquals(album.bytesOf(requireNotNull(live.liveVideoId)), File(pair.video).readBytes())
            assertNull(opened.videoPath, "a Live Photo is not a video")
            screenshot("media-live")
        }
    }
}
