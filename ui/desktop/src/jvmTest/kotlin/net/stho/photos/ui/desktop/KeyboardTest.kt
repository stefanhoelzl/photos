@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package net.stho.photos.ui.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import net.stho.photos.app.BlobRef
import net.stho.photos.app.Catalog
import net.stho.photos.app.DateRange
import net.stho.photos.app.Day
import net.stho.photos.app.DesktopModel
import net.stho.photos.app.LivePair
import net.stho.photos.app.Preview
import net.stho.photos.app.Previews
import net.stho.photos.app.Rebuilder
import net.stho.photos.app.Rebuilt
import net.stho.photos.app.Thumbnails
import net.stho.photos.app.TileSize
import net.stho.photos.app.Totals
import net.stho.photos.app.Videos
import net.stho.photos.catalog.Album
import net.stho.photos.model.PhotoRow
import net.stho.photos.ui.screens.PhotosTheme
import net.stho.photos.ui.screens.PlayToggle

/**
 * §11's keyboard, pressed on the real screen rendered offscreen: the sidebar's ↑/↓ and Enter, the
 * grid's arrows and Enter, the viewer's ←/→ and Esc, and the keys that work anywhere.
 */
class KeyboardTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop(): Unit = scopes.forEach(CoroutineScope::cancel)

    @Test
    fun theSidebarsArrowsChangeTheAlbumAndEnterMovesIntoItsGrid() = screen {
        press(Key.DirectionDown)
        assertEquals("Rome", model.state.value.selected?.name)
        press(Key.DirectionDown)
        assertEquals("Lisbon", model.state.value.selected?.name)
        press(Key.DirectionUp)
        assertEquals("Rome", model.state.value.selected?.name)

        press(Key.Enter)
        press(Key.DirectionRight)
        assertEquals(0, model.state.value.focus, "in the grid now, where → lands on the first tile")
        assertEquals("Rome", model.state.value.selected?.name, "and the sidebar's selection did not move")
    }

    @Test
    fun theGridsArrowsMoveTheFocusAndEnterOpensThePhoto() = screen {
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertEquals(1, model.state.value.focus)

        press(Key.Enter)
        assertEquals(1, model.state.value.open)
        press(Key.DirectionRight)
        assertEquals(2, model.state.value.open, "→ pages in the viewer")
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertEquals(0, model.state.value.open, "← too")

        press(Key.Escape)
        assertNull(model.state.value.open)
        assertEquals(0, model.state.value.focus, "back on the grid, on the photo the viewer was left on")
    }

    /** Space in the viewer is the open video's or Live Photo's play/pause, and nothing on the grid. */
    @Test
    fun spaceInTheViewerPressesPlay() = screen {
        press(Key.DirectionDown)
        press(Key.Enter)
        press(Key.Spacebar)
        assertEquals(0, playPresses(), "on the grid Space does nothing")
        press(Key.Enter)
        press(Key.Spacebar)
        press(Key.Spacebar)
        assertEquals(2, playPresses())
    }

    @Test
    fun ctrlPlusAndMinusStepTheTileSize() = screen {
        press(Key.Equals, ctrl = true)
        assertEquals(TileSize.Large, model.state.value.tile)
        press(Key.Minus, ctrl = true)
        press(Key.Minus, ctrl = true)
        assertEquals(TileSize.Small, model.state.value.tile)
    }

    // ------------------------------------------------------------------------------ harness

    private inner class Screen(val model: DesktopModel, private val scene: ImageComposeScene, private val play: PlayToggle) {
        fun playPresses(): Int = play.presses

        private var time = 0L

        suspend fun press(key: Key, ctrl: Boolean = false) {
            for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                time += 16
                scene.sendKeyEvent(KeyEvent(key, type, isCtrlPressed = ctrl))
                scene.render(time * 1_000_000)
                // What the key started — a state update, a focus request — runs on this thread next.
                repeat(4) { yield() }
            }
        }
    }

    /**
     * The scene, the model and the keys on one thread, which is also the scene's coroutine
     * context: an effect that suspends resumes there, never mid-frame (see `renderFrame`).
     */
    private fun screen(body: suspend Screen.() -> Unit) = runBlocking(frames) {
        val scope = CoroutineScope(frames).also { scopes += it }
        val library = Library()
        val model = DesktopModel(library, library, library, NoPreviews, NoVideos, scope)
        model.start()
        val play = PlayToggle()
        val scene = ImageComposeScene(width = 1280, height = 800, density = Density(1f), coroutineContext = frames) {
            PhotosTheme(dark = true) { DesktopApp(model, library, play) }
        }
        try {
            // A few frames: the first lays out, and the effect that puts the keyboard in the sidebar runs after it.
            repeat(3) {
                scene.render(it * 16_000_000L)
                repeat(4) { yield() }
            }
            Screen(model, scene, play).body()
        } finally {
            scene.close()
        }
    }

    /** Two albums of photos under a container, all in memory. */
    private class Library : Catalog, Rebuilder, Thumbnails {
        private val trips = album("Trips", 0).copy(photoCount = 0)
        private val rome = album("Rome", 2024).copy(parent = trips.id)
        private val lisbon = album("Lisbon", 2022).copy(parent = trips.id)
        private val albums = listOf(trips, rome, lisbon)
        private val photos = listOf(rome, lisbon).associate { album ->
            album.id to (1..5).map { PhotoRow(id = Uuid.random(), filename = "${album.name}-$it.jpg") }
        }

        override fun rebuild(): Rebuilt = Rebuilt(albums.size, 10, skipped = 0)
        override fun albums(under: Uuid?): List<Album> = albums.filter { it.parent == under }
        override fun search(text: String): List<Album> = albums.filter { it.nameFolded.contains(text.lowercase()) }
        override fun photos(inAlbum: Uuid): List<PhotoRow> = photos[inAlbum].orEmpty()
        override fun album(id: Uuid): Album? = albums.firstOrNull { it.id == id }
        override fun photosPerDay(): Map<Day, Int> = emptyMap()
        override fun photosIn(range: DateRange): Map<Uuid, Int> = emptyMap()
        override fun totals(): Totals = Totals(albums.size, 10)
        override fun blobs(): Map<Uuid, List<BlobRef>> = emptyMap()
        override fun has(album: Album): Boolean = true
        override fun cover(album: Album): ByteArray? = null
        override fun all(album: Album): Map<Uuid, ByteArray> = emptyMap()
        override fun prioritise(album: Album) = Unit
        override val arrivals = MutableStateFlow(0)
        override val outstanding = MutableStateFlow(0)

        private fun album(name: String, year: Int) = Album(
            id = Uuid.random(),
            name = name,
            nameFolded = name.lowercase(),
            parent = null,
            photoCount = 5,
            dateMin = Instant.parse("${year.coerceAtLeast(2000)}-01-01T00:00:00Z"),
            dateMax = Instant.parse("${year.coerceAtLeast(2000)}-12-31T00:00:00Z"),
            latitude = null,
            longitude = null,
            coverPhotoId = null,
            thumbsId = null,
        )
    }

    private object NoPreviews : Previews {
        override fun cached(photo: PhotoRow): Preview? = null
        override suspend fun load(photo: PhotoRow): Preview? = null
        override fun prefetch(photos: List<PhotoRow>, index: Int) = Unit
        override fun cancelPrefetch() = Unit
    }

    private object NoVideos : Videos {
        override suspend fun localFile(photo: PhotoRow): String? = null
        override suspend fun livePair(photo: PhotoRow): LivePair? = null
    }

    private companion object {
        val frames = Executors.newSingleThreadExecutor { Thread(it, "keyboard-test").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }
}
