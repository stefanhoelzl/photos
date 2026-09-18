package net.stho.photos.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** The order the picker fetches its thumbnails in: outward from where it opens (§8). */
class PickerThumbnailsTest {

    @Test
    fun fromTheEndItRunsBackThroughTheLibrary() {
        assertEquals(listOf(4, 3, 2, 1, 0), outwardFrom(4, 5))
    }

    @Test
    fun fromARowItTakesTheNearestOnEitherSideNext() {
        assertEquals(listOf(2, 3, 1, 4, 0, 5, 6), outwardFrom(2, 7))
    }

    @Test
    fun anEmptyLibraryHasNothingToFetch() {
        assertEquals(emptyList(), outwardFrom(-1, 0))
    }
}
