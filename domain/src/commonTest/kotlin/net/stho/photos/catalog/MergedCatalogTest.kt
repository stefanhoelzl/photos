package net.stho.photos.catalog

import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.io.files.Path
import net.stho.photos.model.PhotoRow

/**
 * The merged DB is what every screen reads. §4 rebuilds it wholesale so stale rows are impossible
 * by construction — these check that the construction actually holds.
 */
class MergedCatalogTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()


    private class Built(val reader: CatalogReader, val summary: RebuildSummary, val path: Path)

    private fun build(shards: List<Shard>, label: String = "merged"): Built {
        val path = Path(temporaryDirectory(label), "merged.db")
        val summary = CatalogWriter(path, testDrivers).use { it.rebuild(shards) }
        return Built(CatalogReader(path, testDrivers), summary, path)
    }

    @Test
    fun albumsAndPhotosBothLand() {
        val built = build(
            listOf(
                album("Neuseeland", photos = (0 until 5).map { photo("IMG_$it.jpg") }),
                album("Island", photos = (0 until 3).map { photo("DSC_$it.jpg") }),
            ),
        )

        assertEquals(2, built.summary.albums)
        assertEquals(8, built.summary.photos)
        assertEquals(2, built.reader.allAlbums().size)
        assertEquals(8, built.reader.photoCount())
    }

    /**
     * §4's rebuild has no incremental path. Replaying a *smaller* set must leave nothing behind
     * from the larger one, or "stale rows are impossible" is merely a wish.
     */
    @Test
    fun aRebuildReplacesRatherThanAccumulates() {
        val path = Path(temporaryDirectory("wholesale"), "merged.db")
        CatalogWriter(path, testDrivers).use { writer ->
            writer.rebuild(
                listOf(
                    album("First", photos = (0 until 10).map { photo("a$it.jpg") }),
                    album("Second", photos = (0 until 10).map { photo("b$it.jpg") }),
                ),
            )
            writer.rebuild(listOf(album("Only", photos = listOf(photo("c.jpg")))))
        }

        val reader = CatalogReader(path, testDrivers)
        assertEquals(listOf("Only"), reader.allAlbums().map(Album::name))
        assertEquals(1, reader.photoCount())
    }

    @Test
    fun theSameShardsRebuiltTwiceGiveTheSameCatalog() {
        val shards = containerTree("Rauhöd", listOf("2019", "2020", "2021"))
        val path = Path(temporaryDirectory("idempotent"), "merged.db")

        val first: List<Album>
        val second: List<Album>
        CatalogWriter(path, testDrivers).use { writer ->
            writer.rebuild(shards)
            first = CatalogReader(path, testDrivers).allAlbums()
            writer.rebuild(shards.reversed())
            second = CatalogReader(path, testDrivers).allAlbums()
        }

        assertEquals(first, second)
    }

    // ------------------------------------------------------------------------------ hierarchy

    @Test
    fun childrenHangOffTheirContainer() {
        val shards = containerTree("Kalifornien", listOf("Yosemite", "Big Sur"))
        val built = build(shards)

        assertEquals(listOf("Kalifornien"), built.reader.albums(under = null).map(Album::name))
        assertEquals(
            setOf("Yosemite", "Big Sur"),
            built.reader.albums(under = shards[0].info.id).map(Album::name).toSet(),
        )
    }

    /**
     * §2: no album may become unreachable because one object failed to arrive. A dangling parent
     * surfaces the album at the root and is reported — it is not an error and it is not a hidden
     * album.
     */
    @Test
    fun anAlbumWhoseParentShardIsMissingSurfacesAtTheRoot() {
        val orphans = containerTree("Immling", listOf("2003", "2004")).drop(1)
        val built = build(orphans)

        assertEquals(setOf("2003", "2004"), built.reader.albums(under = null).map(Album::name).toSet())
        assertEquals(orphans.map { it.info.id }.toSet(), built.summary.orphanedAlbums.toSet())
        for (album in built.reader.allAlbums()) assertNull(album.parent)
    }

    // ------------------------------------------------------------------------------- ordering

    /**
     * §3: oldest first, undated last by filename. There is no stored sort column, so this is
     * checking the query and its expression index agree with the rule.
     */
    @Test
    fun photosComeBackOldestFirstUndatedLastByFilename() {
        val base = Instant.fromEpochSeconds(1_000_000_000)
        val shard = album(
            "Mixed",
            photos = listOf(
                photo("c.jpg", takenAt = base + 200.seconds),
                photo("a.jpg", takenAt = base),
                photo("z_undated.jpg", takenAt = null),
                photo("b.jpg", takenAt = base + 100.seconds),
                photo("m_undated.jpg", takenAt = null),
            ),
        )
        val built = build(listOf(shard))

        assertEquals(
            listOf("a.jpg", "b.jpg", "c.jpg", "m_undated.jpg", "z_undated.jpg"),
            built.reader.photos(inAlbum = shard.info.id).map(PhotoRow::filename),
        )
    }

    /**
     * If the query and the index ever state the rule differently, the planner silently stops
     * using the index — costing speed, not correctness. This is what notices.
     */
    @Test
    fun theOrderingIndexIsActuallyUsed() {
        val shard = album("Big", photos = (0 until 50).map { photo("IMG_$it.jpg") })
        val path = Path(temporaryDirectory("plan"), "merged.db")
        CatalogWriter(path, testDrivers).use { it.rebuild(listOf(shard)) }

        val plan = explainQueryPlan(
            path,
            "SELECT id FROM photo WHERE album_id = '${shard.info.id}' " +
                "ORDER BY taken_at IS NULL, taken_at, filename",
        )

        assertTrue(plan.any { "ix_photo_album" in it }, plan.toString())
        assertTrue(plan.none { "TEMP B-TREE" in it }, plan.toString())
    }

    // --------------------------------------------------------------------------------- covers

    @Test
    fun theDefaultCoverIsTheAlbumsEarliestPhoto() {
        val base = Instant.fromEpochSeconds(1_000_000_000)
        val shard = album(
            "Trip",
            photos = listOf(
                photo("late.jpg", takenAt = base + 500.seconds),
                photo("early.jpg", takenAt = base),
            ),
        )
        val built = build(listOf(shard))

        assertEquals("early.jpg", built.reader.coverPhoto(of = shard.info.id)?.filename)
    }

    @Test
    fun anExplicitCoverWins() {
        val photos = listOf(photo("a.jpg"), photo("chosen.jpg"))
        val shard = album("Trip", photos = photos, coverPhotoId = photos[1].id)
        val built = build(listOf(shard))

        assertEquals("chosen.jpg", built.reader.coverPhoto(of = shard.info.id)?.filename)
    }

    /** §3: a container owns no photos, so its cover is found by descending into children. */
    @Test
    fun aContainersCoverIsFoundByDescendingIntoItsChildren() {
        val shards = containerTree("Weihnachten", listOf("2002", "2003"))
        val built = build(shards)

        val cover = assertNotNull(built.reader.coverPhoto(of = shards[0].info.id))
        assertTrue(cover.filename.startsWith("2002_"), cover.filename)
    }

    @Test
    fun anExplicitCoverOnAContainerIsNotOverriddenByTheDescent() {
        val shards = containerTree("Weihnachten", listOf("2002", "2003")).toMutableList()
        val chosen = shards[2].photos[1]
        shards[0] = shards[0].let { it.copy(info = it.info.copy(coverPhotoId = chosen.id)) }
        val built = build(shards)

        assertEquals(chosen.id, built.reader.coverPhoto(of = shards[0].info.id)?.id)
    }

    // ------------------------------------------------------------------------------ locations

    /**
     * §3: the pin is computed at rebuild from photo rows, never stored in a shard — so it cannot
     * drift from the photos beneath it.
     */
    @Test
    fun anAlbumsPinIsTheCentroidOfItsTaggedPhotos() {
        val shard = album(
            "Somewhere",
            photos = listOf(
                photo("a.jpg", latitude = 10.0, longitude = 20.0),
                photo("b.jpg", latitude = 20.0, longitude = 40.0),
            ),
        )
        val built = build(listOf(shard))

        val album = assertNotNull(built.reader.album(shard.info.id))
        assertEquals(15.0, album.latitude)
        assertEquals(30.0, album.longitude)
    }

    @Test
    fun untaggedPhotosDoNotDragTheCentroidTowardZero() {
        val shard = album(
            "Mostly Untagged",
            photos = listOf(
                photo("a.jpg", latitude = 47.99, longitude = 12.26),
                photo("b.jpg", latitude = null, longitude = null),
                photo("c.jpg", latitude = null, longitude = null),
            ),
        )
        val built = build(listOf(shard))

        val album = assertNotNull(built.reader.album(shard.info.id))
        assertTrue(abs(assertNotNull(album.latitude) - 47.99) < 0.0001)
    }

    /**
     * This replaces §3's old downward cascade: placing a container happens by placing its
     * descendants' photos, and the container's pin rolls up from them.
     */
    @Test
    fun aContainersPinRollsUpFromItsDescendants() {
        val tree = containerTree("Rauhöd", listOf("2019", "2020"), photosEach = 1)
        val shards = listOf(
            tree[0],
            tree[1].copy(photos = listOf(tree[1].photos[0].copy(latitude = 10.0, longitude = 20.0))),
            tree[2].copy(photos = listOf(tree[2].photos[0].copy(latitude = 20.0, longitude = 40.0))),
        )
        val built = build(shards)

        val container = assertNotNull(built.reader.album(shards[0].info.id))
        assertEquals(0, container.photoCount) // owns none itself
        assertEquals(15.0, container.latitude) // but is placed by its children
        assertEquals(30.0, container.longitude)
    }

    @Test
    fun anAlbumWithNoTaggedPhotosIsNotOnTheMap() {
        val shard = album(
            "Weihnachten",
            photos = listOf(photo("a.jpg", latitude = null, longitude = null)),
        )
        val built = build(listOf(shard))

        assertNull(built.reader.album(shard.info.id)?.latitude)
        assertTrue(built.reader.placedAlbums().isEmpty())
    }

    // --------------------------------------------------------------------------------- search

    @Test
    fun searchIsCaseAndDiacriticInsensitive() {
        val built = build(listOf("Grün", "Rauhöd", "Straße", "Kalifornien").map { album(it) })

        assertEquals(listOf("Grün"), built.reader.searchAlbums("grun").map(Album::name))
        assertEquals(listOf("Rauhöd"), built.reader.searchAlbums("RAUHOD").map(Album::name))
        assertEquals(listOf("Straße"), built.reader.searchAlbums("strasse").map(Album::name))
        assertEquals(listOf("Kalifornien"), built.reader.searchAlbums("kali").map(Album::name))
    }

    @Test
    fun searchIsSubstringNotPrefix() {
        val built = build(listOf(album("Neuseeland 2019")))
        assertEquals(1, built.reader.searchAlbums("seeland").size)
    }

    /** A name containing `%` would otherwise match everything. */
    @Test
    fun likeWildcardsInTheQueryTextAreEscaped() {
        val built = build(listOf(album("Rabatt 50%"), album("Andere")))

        assertEquals(listOf("Rabatt 50%"), built.reader.searchAlbums("50%").map(Album::name))
        assertEquals(1, built.reader.searchAlbums("%").size)
    }

    @Test
    fun anEmptyQueryMatchesNothingRatherThanEverything() {
        val built = build(listOf(album("Anything")))
        assertTrue(built.reader.searchAlbums("").isEmpty())
    }

    @Test
    fun theFoldHandlesSection3sThreeExamples() {
        assertEquals("grun", "Grün".foldedForSearch())
        assertEquals("rauhod", "Rauhöd".foldedForSearch())
        assertEquals("strasse", "Straße".foldedForSearch())
    }

    /** A decomposed needle folds to the same thing as its composed twin. */
    @Test
    fun theFoldDropsCombiningMarksToo() {
        assertEquals("grun", "Gru\u0308n".foldedForSearch())
    }

    // ----------------------------------------------------------------------------- duplicates

    /**
     * §2: two devices creating one album no longer collide on a key. Both are shown and reported;
     * merging would be a destructive guess about intent.
     */
    @Test
    fun duplicateNamesUnderOneParentAreReportedAndBothAlbumsSurvive() {
        val built = build(
            listOf(
                album("Sommer", photos = listOf(photo("a.jpg"))),
                album("Sommer", photos = listOf(photo("b.jpg"), photo("c.jpg"))),
                album("Winter"),
            ),
        )

        assertEquals(3, built.reader.allAlbums().size)
        val duplicates = built.reader.duplicateNames()
        assertEquals(1, duplicates.size)
        assertEquals("Sommer", duplicates.first().name)
        assertEquals(2, duplicates.first().albums.size)
        assertEquals(3, built.reader.photoCount())
    }

    @Test
    fun theSameNameUnderDifferentParentsIsNotADuplicate() {
        val built = build(
            containerTree("A", listOf("2019"), photosEach = 1) +
                containerTree("B", listOf("2019"), photosEach = 1),
        )

        assertTrue(built.reader.duplicateNames().isEmpty())
    }
}
