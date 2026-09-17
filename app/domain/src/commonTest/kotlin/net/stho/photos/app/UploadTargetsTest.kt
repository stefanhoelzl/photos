package net.stho.photos.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid
import net.stho.photos.catalog.Album

/** §8's album field: a path from the library root, matched against §2's tree of containers and albums. */
class UploadTargetsTest {

    private val trips = album("Trips")
    private val italy = album("Italy", parent = trips, photos = 40)
    private val balkans = album("Balkans", parent = trips)
    private val sarajevo = album("Sarajevo", parent = balkans, photos = 12)
    private val garden = album("Garden", photos = 3)
    private val empty = album("Empty")

    private val targets = UploadTargets(
        albums = listOf(trips, italy, balkans, sarajevo, garden, empty),
        containers = setOf(trips.id, balkans.id),
    )

    @Test
    fun onlyAlbumsThatTakePhotosAreListedEachByItsPath() {
        assertEquals(
            listOf("Trips / Italy", "Trips / Balkans / Sarajevo", "Garden", "Empty"),
            targets.entries.map(UploadTarget::path),
        )
    }

    @Test
    fun aPathMatchesItsAlbumIgnoringCaseAndTheSpacesAroundEachSlash() {
        val resolved = assertIs<Resolution.Entry>(targets.resolve("trips/balkans /  SARAJEVO"))
        assertEquals(sarajevo.id, resolved.target.id)
    }

    @Test
    fun aPathNoAlbumHasIsANewAlbumInTheContainerItNames() {
        assertEquals(
            Resolution.New(name = "Pula", parent = balkans.id, parentPath = "Trips / Balkans", path = "Trips / Balkans / Pula"),
            targets.resolve("trips / balkans / Pula"),
        )
        assertEquals(Resolution.New("Pula", null, "", "Pula"), targets.resolve("Pula"), "a bare name is top-level")
    }

    @Test
    fun aPathThePhoneCannotMakeAnAlbumAtSaysWhy() {
        assertEquals(Resolution.Refused("No container \"Trps\""), targets.resolve("Trps / Spain"))
        assertEquals(Resolution.Refused("\"Italy\" holds photos, not albums"), targets.resolve("Trips / Italy / Day 1"))
        assertEquals(Resolution.Refused("\"Trips\" holds albums, not photos"), targets.resolve("trips"))
    }

    @Test
    fun aPathStillBeingTypedNamesNothingYet() {
        assertEquals(Resolution.Incomplete, targets.resolve(""))
        assertEquals(Resolution.Incomplete, targets.resolve("Trips / "))
        assertEquals(Resolution.Incomplete, targets.resolve("Trips // Pula"))
    }

    /** An album added in the dialog, or one this phone is still uploading, is picked by its path like any other. */
    @Test
    fun addedAndUploadingAlbumsAreMatchedToo() {
        val uploading = UploadTarget(Uuid.random(), "Pula", balkans.id, "Trips / Balkans / Pula", UploadTarget.Kind.Uploading)
        val added = UploadTarget(Uuid.random(), "Zagreb", balkans.id, "Trips / Balkans / Zagreb", UploadTarget.Kind.New)
        val withUploading = UploadTargets(listOf(trips, balkans), setOf(trips.id, balkans.id), listOf(uploading, uploading))

        assertEquals(listOf(uploading), withUploading.entries)
        assertEquals(Resolution.Entry(uploading), withUploading.resolve("Trips / Balkans / pula"))
        assertEquals(Resolution.Entry(added), withUploading.resolve("trips / balkans / zagreb", listOf(added)))
    }

    /** Once it lands, an album this phone uploaded is in the catalog, and listed once. */
    @Test
    fun anUploadingAlbumTheCatalogAlreadyShowsIsListedOnce() {
        val landed = UploadTarget(garden.id, "Garden", null, "Garden", UploadTarget.Kind.Uploading)
        val withLanded = UploadTargets(listOf(garden), emptySet(), listOf(landed))

        assertEquals(listOf(UploadTarget.Kind.Album), withLanded.entries.map(UploadTarget::kind))
    }

    @Test
    fun theFieldStartsWhereTheUploadDid() {
        assertEquals("Trips / Italy", targets.prefill(start = trips.id, addTo = italy.id, galleryName = "Croatia"))
        assertEquals("Trips / Balkans / Croatia", targets.prefill(start = balkans.id, addTo = null, galleryName = "Croatia"))
        assertEquals("Trips / Balkans / ", targets.prefill(start = balkans.id, addTo = null, galleryName = null))
        assertEquals("Croatia", targets.prefill(start = null, addTo = null, galleryName = "Croatia"))
        assertEquals("", targets.prefill(start = null, addTo = null, galleryName = null))
    }

    private fun album(name: String, parent: Album? = null, photos: Int = 0) = Album(
        id = Uuid.random(),
        name = name,
        nameFolded = name.lowercase(),
        parent = parent?.id,
        photoCount = photos,
        dateMin = null,
        dateMax = null,
        latitude = null,
        longitude = null,
        coverPhotoId = null,
        thumbsId = null,
    )
}
