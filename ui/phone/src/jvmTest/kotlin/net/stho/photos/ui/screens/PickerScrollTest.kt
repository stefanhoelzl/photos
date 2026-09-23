package net.stho.photos.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.stho.photos.app.Day
import net.stho.photos.app.GalleryAccess
import net.stho.photos.app.GalleryAlbum
import net.stho.photos.app.GalleryAsset
import net.stho.photos.app.PickerUi
import net.stho.photos.app.Scroll
import net.stho.photos.app.photoRowKey
import net.stho.photos.model.MediaType

/**
 * The picker opening at its end, or where the last upload left it (§8), rendered offscreen: it is
 * asserted on where the picker reports it came to rest, which is the position it really restored to.
 *
 * The library arrives *after* the picker is composed, as it does on the phone — `open()` publishes
 * the access grant before it has read the albums and the assets. A restore that did not wait for
 * the rows would land at the top and report that, which is the failure these tests are here for.
 *
 * At density 1 the list is 400 px wide: rows of four 99 px tiles under the year mark the photos are
 * grouped by — these assets carry no date, so there is one, reading *Undated* — and then the albums
 * label and two album rows.
 */
class PickerScrollTest {

    private val albums = listOf(GalleryAlbum("g1", "Weekend", 3), GalleryAlbum("g2", "Iceland", 9))
    private val assets = (0 until 120).map { GalleryAsset("a$it", "IMG_$it.heic", MediaType.PHOTO) }

    /** One year mark, so `a40`'s row — the 11th — is item 11. */
    private val rowOfA40 = 11

    @Test
    fun theRememberedRowComesBackOnceTheLibraryHasArrived() =
        picker(Scroll(photoRowKey("a40"), rowOfA40, 7)) {
            frames()
            assertNull(reported, "no rows to restore into yet, and nothing reported over the saved position")

            libraryArrives()
            frames()
            assertEquals(Rest(photoRowKey("a40"), rowOfA40, 7), reported)
        }

    @Test
    fun aRowWhosePhotosAreGoneComesBackAtItsOldPosition() =
        // What an upload that deleted its photos from the device leaves behind: the key is gone,
        // the rows below have moved up, and the position is the first photo it did not take.
        picker(Scroll(photoRowKey("gone"), rowOfA40, 30)) {
            libraryArrives()
            frames()
            assertEquals(Rest(photoRowKey("a40"), rowOfA40, 0), reported)
        }

    /**
     * Two years, so the remembered row sits below two marks — which the key list has to count, or
     * the fallback position would be a mark short per year and land on the wrong photo.
     */
    @Test
    fun aRowUnderASecondYearMarkComesBackToo() {
        // 60 photos in 2025, then 60 in 2026: `a80`'s row is the 6th of the second year.
        val dated = assets.mapIndexed { index, asset ->
            asset.copy(takenAt = Day.of(if (index < 60) 2025 else 2026, 6, 1).midnight)
        }
        // The first mark, 2025's 15 rows, the second mark, then 5 rows.
        val rowOfA80 = 1 + 15 + 1 + 5
        picker(Scroll(photoRowKey("a80"), rowOfA80, 0), assets = dated) {
            libraryArrives()
            frames()
            assertEquals(Rest(photoRowKey("a80"), rowOfA80, 0), reported)
        }
    }

    /**
     * The first open, and a picker left at its end: the newest photos, and the albums below them.
     * Reported as the end, so the next open goes there too rather than to a row.
     */
    @Test
    fun nothingRememberedOpensAtTheEnd() =
        picker(scroll = null) {
            frames()
            assertNull(reported, "no rows to scroll to the end of yet")

            libraryArrives()
            frames()
            // 30 rows under a mark, then the label and two albums: 31 × 99 + 40 + 2 albums below
            // the top of the mark, so a 400 px window at the end starts on a row well past it.
            val rest = assertNotNull(reported)
            assertTrue(rest.atEnd, "at the end: $rest")
            assertTrue(rest.index > rowOfA40, "somewhere near the newest photos: $rest")
        }

    /**
     * The Upload bar arriving with the first photo selected takes its height from the list's
     * bottom, not its top: the rows move up by it, and back again when the selection empties.
     */
    @Test
    fun theUploadBarTakesItsRoomFromTheTop() =
        picker(Scroll(photoRowKey("a40"), rowOfA40, 7)) {
            libraryArrives()
            frames()
            val before = assertNotNull(reported)

            select("a0")
            frames()
            val during = assertNotNull(reported)
            assertEquals(before.key, during.key, "the bar is shorter than a row")
            assertTrue(during.offset > before.offset, "the rows moved up under the bar: $before → $during")

            select()
            frames()
            assertEquals(before, reported, "and down again once it has gone")
        }

    @Test
    fun theEndStaysTheEndAsTheUploadBarComesAndGoes() =
        picker(scroll = null) {
            libraryArrives()
            frames()
            select("a119")
            frames()
            assertTrue(assertNotNull(reported).atEnd, "the bar came, and the albums are still in view above it")
            select()
            frames()
            assertTrue(assertNotNull(reported).atEnd, "the bar went, and nothing is left below the albums")
        }

    // ------------------------------------------------------------------------------ harness

    /** Where the picker reported a scroll coming to rest. */
    private data class Rest(val key: String?, val index: Int, val offset: Int, val atEnd: Boolean = false)

    private inner class Run(
        private val scope: TestScope,
        private val scene: ImageComposeScene,
        val arriving: List<GalleryAsset>,
    ) {
        val reported: Rest? get() = this@PickerScrollTest.reported

        /** The selection as a tap or a drag leaves it; the Upload bar is there while it is not empty. */
        fun select(vararg ids: String) {
            val state = requireNotNull(current)
            state.value = state.value.copy(selected = ids.toSet())
        }

        /** What `open()`'s second update does: the albums and the assets, once the platform answers. */
        fun libraryArrives() {
            val state = requireNotNull(current)
            state.value = state.value.copy(albums = albums, assets = arriving)
        }

        fun frames(count: Int = FRAMES) {
            repeat(count) {
                scene.render(frame * 16_000_000L)
                scope.testScheduler.runCurrent()
                frame++
            }
        }

        private var frame = 0L
    }

    private var current: MutableState<PickerUi>? = null
    private var reported: Rest? = null

    private fun picker(
        scroll: Scroll?,
        assets: List<GalleryAsset> = this.assets,
        body: Run.() -> Unit,
    ) = runTest {
        // Access granted, the library not yet read: the state the picker is first composed in.
        val state = mutableStateOf(PickerUi(access = GalleryAccess.Full))
        current = state
        reported = null
        val scene = ImageComposeScene(
            width = 400,
            height = 400,
            density = Density(1f),
            coroutineContext = StandardTestDispatcher(testScheduler),
        ) {
            GalleryPicker(
                picker = state.value,
                onAlbum = {},
                onToggle = {},
                onSelection = {},
                onUseSelection = {},
                scroll = scroll,
                onScrolled = { key, at, by, atEnd -> reported = Rest(key, at, by, atEnd) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        try {
            Run(this, scene, assets).body()
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val FRAMES = 8
    }
}
