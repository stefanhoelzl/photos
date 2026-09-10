package net.stho.photos.ingest

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import net.stho.photos.catalog.AlbumInfo
import net.stho.photos.catalog.AlbumState
import net.stho.photos.catalog.blobId
import net.stho.photos.catalog.SHARD_SCHEMA_VERSION
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.ShardProbe
import net.stho.photos.catalog.deleteTemporaryDirectories
import net.stho.photos.model.PhotoRow

/**
 * The rules that decide what the zone should contain.
 *
 * This is the file that matters most: every rule here can lose photographs, and none of them needs
 * a network or an encoder to be wrong. §7's whole safety story is four sentences — a `stat` decides
 * existence, `.photosignore` decides uploads, a missing directory deletes, and no marker means no
 * run — and each of them is asserted below.
 */
class ReconcilerTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()

    // ------------------------------------------------------------------------------------ shape

    @Test
    fun everyDirectoryHoldingMediaBecomesAnAlbum() {
        val library = LibraryFixture()
        library.file("Rauhöd 2019/a.jpg")
        library.file("Rauhöd 2019/b.jpg")
        library.file("Neuseeland/c.jpg")

        val plan = library.plan()

        assertEquals(listOf("Neuseeland", "Rauhöd 2019"), plan.albums.map(AlbumPlan::sourcePath))
        assertTrue(plan.albums.all(AlbumPlan::isNew))
        assertEquals(3, plan.uploadCount)
    }

    /**
     * §10: `LibraryWalker` emits nothing for a directory that holds only sub-directories, so
     * `Kalifornien` — 17 children, no photos of its own — has to be invented here or the hierarchy
     * has a hole in it.
     */
    @Test
    fun aContainerWithNoPhotosOfItsOwnIsSynthesisedAndParentsItsChildren() {
        val library = LibraryFixture()
        library.file("Kalifornien/Yosemite/a.jpg")
        library.file("Kalifornien/Big Sur/b.jpg")

        val plan = library.plan()

        assertEquals(
            listOf("Kalifornien", "Kalifornien/Big Sur", "Kalifornien/Yosemite"),
            plan.albums.map(AlbumPlan::sourcePath),
        )
        val container = assertNotNull(plan.albums.firstOrNull { it.sourcePath == "Kalifornien" })
        assertNull(container.parent)
        assertTrue(container.uploads.isEmpty())
        for (child in plan.albums.filter { it.sourcePath != "Kalifornien" }) {
            assertEquals(container.id, child.parent)
        }
    }

    /**
     * §2: an album has sub-albums XOR photos. The children are unambiguous, so they go up; the
     * loose files are not, so they do not.
     */
    @Test
    fun aMixedFolderLosesItsOwnFilesNeverItsChildren() {
        val library = LibraryFixture()
        library.file("Weihnachten/stray.jpg")
        library.file("Weihnachten/2002/a.jpg")

        val plan = library.plan()

        val parent = assertNotNull(plan.albums.firstOrNull { it.sourcePath == "Weihnachten" })
        assertTrue(parent.uploads.isEmpty())
        assertEquals(1, parent.mixedFileCount)

        val child = assertNotNull(plan.albums.firstOrNull { it.sourcePath == "Weihnachten/2002" })
        assertEquals(1, child.uploads.size)
    }

    /**
     * A stray beside the album folders must not re-parent all 240 of them under a new root album,
     * so the root is never an album at all.
     */
    @Test
    fun looseFilesAtTheLibraryRootAreReportedNotIngested() {
        val library = LibraryFixture()
        library.file("stray.jpg")
        library.file("Neuseeland/a.jpg")

        val plan = library.plan()

        assertEquals(1, plan.looseRootFiles.size)
        assertEquals(listOf("Neuseeland"), plan.albums.map(AlbumPlan::sourcePath))
        assertNull(plan.albums[0].parent)
    }

    // ------------------------------------------------------------------------ adding and dropping

    @Test
    fun aNewFileInAKnownAlbumIsTheOnlyThingUploaded() {
        val library = LibraryFixture()
        library.file("Rauhöd/a.jpg")
        library.file("Rauhöd/b.jpg")
        val shard = library.shard("Rauhöd", photos = listOf("a.jpg"))

        val plan = library.plan(shards = listOf(shard))

        val album = assertNotNull(plan.albums.firstOrNull())
        assertEquals(shard.info.id, album.id) // identity survives; no new album
        assertEquals(listOf("b.jpg"), album.uploads.map(Path::name))
        assertEquals(1, album.keep.size)
        assertTrue(album.drop.isEmpty())
    }

    @Test
    fun aDeletedFileDropsItsRowAndLeavesTheRestAlone() {
        val library = LibraryFixture()
        library.file("Rauhöd/a.jpg")
        val shard = library.shard("Rauhöd", photos = listOf("a.jpg", "b.jpg"))

        val plan = library.plan(shards = listOf(shard))

        val album = assertNotNull(plan.albums.firstOrNull())
        assertEquals(listOf("b.jpg"), album.drop.map(PhotoRow::filename))
        assertEquals(listOf("a.jpg"), album.keep.map(PhotoRow::filename))
        assertTrue(album.uploads.isEmpty())
    }

    /**
     * The rule the whole deletion model rests on. `.photosignore` says what may be *uploaded*;
     * existence is a `stat`. If the two were the same question, broadening a rule would silently
     * delete every photo it newly matched.
     */
    @Test
    fun anIgnoredFileThatStillExistsIsNotADeletion() {
        val library = LibraryFixture()
        library.marker("*.jpg\n")
        library.file("Rauhöd/a.jpg")
        val shard = library.shard("Rauhöd", photos = listOf("a.jpg"))

        val plan = library.plan(shards = listOf(shard))

        val album = assertNotNull(plan.albums.firstOrNull())
        assertTrue(album.drop.isEmpty()) // the file is there; the rule only stops uploads
        assertEquals(1, album.keep.size)
        assertTrue(album.uploads.isEmpty())
        assertTrue(plan.deletions.isEmpty())
    }

    /**
     * Deleting an album's files with `rm` is not how an album is deleted. The directory is still there, so the album is
     * still there — with nothing in it.
     */
    @Test
    fun anAlbumEmptiedOfFilesSurvivesWithZeroPhotos() {
        val library = LibraryFixture()
        library.directory("Rauhöd")
        val shard = library.shard("Rauhöd", photos = listOf("a.jpg", "b.jpg"))

        val plan = library.plan(shards = listOf(shard))

        assertTrue(plan.deletions.isEmpty())
        val album = assertNotNull(plan.albums.firstOrNull())
        assertEquals(2, album.drop.size)
        assertTrue(album.keep.isEmpty())
        assertEquals(shard.info.id, album.id)
    }

    /** `rm -rf` is the unambiguous gesture, and the only one that means this. */
    @Test
    fun aDirectoryThatIsGoneDeletesTheAlbum() {
        val library = LibraryFixture()
        library.file("Neuseeland/a.jpg")
        val gone = library.shard("Rauhöd", photos = listOf("a.jpg"))
        val kept = library.shard("Neuseeland", photos = listOf("a.jpg"))

        val plan = library.plan(shards = listOf(gone, kept))

        assertEquals(listOf("Rauhöd"), plan.deletions.map(AlbumDeletion::sourcePath))
        assertEquals(listOf("Neuseeland"), plan.albums.map(AlbumPlan::sourcePath))
    }

    /**
     * §7's decision 3, in one test: identity comes from `source_path` alone, so a rename is an
     * upload and a deletion rather than a metadata write. Expensive and never wrong.
     */
    @Test
    fun aRenamedFolderIsANewAlbumPlusADeletion() {
        val library = LibraryFixture()
        library.file("Neuseeland 2019/a.jpg")
        val old = library.shard("Neuseeland", photos = listOf("a.jpg"))

        val plan = library.plan(shards = listOf(old))

        assertEquals(listOf("Neuseeland"), plan.deletions.map(AlbumDeletion::sourcePath))
        val album = assertNotNull(plan.albums.firstOrNull())
        assertEquals("Neuseeland 2019", album.sourcePath)
        assertTrue(album.isNew)
        assertNotEquals(old.info.id, album.id)
    }

    // ------------------------------------------------------------------- the byte-size assertion

    @Test
    fun aFileWhoseSizeChangedAbortsNamingTheFile() {
        val library = LibraryFixture()
        library.file("Rauhöd/a.jpg", bytes = 128)
        val shard = library.shard("Rauhöd", photos = listOf("a.jpg"), bytes = 64)

        val plan = library.plan(shards = listOf(shard))

        assertEquals(1, plan.mismatches.size)
        assertEquals("a.jpg", plan.mismatches[0].filename)
        assertEquals(64, plan.mismatches[0].recorded)
        assertEquals(128, plan.mismatches[0].found)
    }

    /**
     * §3: `bytes` describes the blob a tap fetches. For a video that is the transcode and for a
     * carved RAW the extracted JPEG, so neither has anything on disk it should equal.
     */
    @Test
    fun videoAndCarvedRawRowsAreNeverSizeChecked() {
        val library = LibraryFixture()
        library.file("Kalifornien/IMG_1234.CR2", bytes = 22_000)
        library.file("Kalifornien/VID_0001.MOV", bytes = 41_000)
        val shard = library.shard("Kalifornien", photos = emptyList()).copy(
            photos = listOf(
                library.rawRow(source = "IMG_1234.CR2"),
                library.videoRow(source = "VID_0001.MOV"),
            ),
        )

        val plan = library.plan(shards = listOf(shard))

        assertTrue(plan.mismatches.isEmpty())
        val album = assertNotNull(plan.albums.firstOrNull())
        assertEquals(2, album.keep.size) // found by their source names
        assertTrue(album.uploads.isEmpty())
        assertTrue(album.drop.isEmpty())
    }

    /**
     * After a pull, `IMG_1234.CR2` is gone and the zone's own name is what is on disk. The row
     * has to survive that, or the next run deletes it and uploads the file again as new.
     */
    @Test
    fun aRowMatchesItsZoneNameTooSoAPulledAlbumStillReconciles() {
        val library = LibraryFixture()
        library.file("Kalifornien/IMG_1234.heic", bytes = 22_000)
        val shard = library.shard("Kalifornien", photos = emptyList())
            .copy(photos = listOf(library.rawRow(source = "IMG_1234.CR2")))

        val plan = library.plan(shards = listOf(shard))

        val album = assertNotNull(plan.albums.firstOrNull())
        assertTrue(album.drop.isEmpty())
        assertTrue(album.uploads.isEmpty())
    }

    // --------------------------------------------------------------- shards this build cannot read

    /**
     * §3's hazard: a shard too new to read must be *unreadable*, never *absent*. Its directory is
     * left completely alone — not uploaded, not deleted, not re-minted.
     */
    @Test
    fun aDirectoryClaimedByATooNewShardIsLeftEntirelyAlone() {
        val library = LibraryFixture()
        library.file("Neuseeland/a.jpg")
        val probe = ShardProbe(
            albumId = Uuid.random(),
            sourcePath = "Neuseeland",
            schemaVersion = SHARD_SCHEMA_VERSION + 1,
        )

        val plan = library.plan(unreadable = listOf(probe))

        assertTrue(plan.albums.isEmpty())
        assertTrue(plan.deletions.isEmpty())
        assertEquals(1, plan.blockedByUnreadable.size)
    }

    // ------------------------------------------------------------------------------------ scope

    /** A scoped run that deleted everything outside its scope would be a trap. */
    @Test
    fun theAlbumFilterScopesDeletionsAsWellAsUploads() {
        val library = LibraryFixture()
        library.file("Kalifornien/a.jpg")
        val outside = library.shard("Neuseeland", photos = listOf("a.jpg")) // directory is gone

        val plan = library.plan(shards = listOf(outside), filter = "Kalifornien")

        assertTrue(plan.deletions.isEmpty())
        assertEquals(listOf("Kalifornien"), plan.albums.map(AlbumPlan::sourcePath))
    }

    // --------------------------------------------------------------- pulling the phone's albums down

    @Test
    fun anAlbumWithNoSourcePathIsPulledIntoANameThatIsFree() {
        val library = LibraryFixture()
        library.file("Wochenende/a.jpg")
        val mine = library.shard("Wochenende", photos = listOf("a.jpg"))
        val phone = phoneAlbum(library, "Wochenende")

        val plan = library.plan(shards = listOf(mine, phone))

        val pull = assertNotNull(plan.pulls.firstOrNull())
        assertEquals(phone.info.id, pull.shard.info.id)
        // §2 permits duplicate names, so the phone's album cannot simply land on top of the
        // laptop's directory of the same name.
        assertNotEquals("Wochenende", pull.sourcePath)
        assertTrue(pull.sourcePath.startsWith("Wochenende ("))
    }

    @Test
    fun aPhoneAlbumIsNeverADeletionCandidateHavingNeverHadADirectory() {
        val library = LibraryFixture()
        val phone = phoneAlbum(library, "Garten")

        val plan = library.plan(shards = listOf(phone))

        assertTrue(plan.deletions.isEmpty())
        assertEquals(1, plan.pulls.size)
    }

    @Test
    fun thePlanIsTheSameTwiceOverForAnUnchangedLibrary() {
        val library = LibraryFixture()
        library.file("Rauhöd/a.jpg")
        val shard = library.shard("Rauhöd", photos = listOf("a.jpg"))

        val first = library.plan(shards = listOf(shard))
        val second = library.plan(shards = listOf(shard))

        assertContentEquals(
            first.albums.map(AlbumPlan::sourcePath),
            second.albums.map(AlbumPlan::sourcePath),
        )
        assertTrue(first.albums.none(AlbumPlan::needsWrite))
        assertFalse(first.hasWork)
    }

    /**
     * A phone album that has finished uploading: `uploaded`, at encoding version 0, with no
     * `source_path` because nothing has claimed it yet. The only state the CLI pulls from.
     */
    private fun phoneAlbum(
        library: LibraryFixture,
        name: String,
        state: AlbumState = AlbumState.UPLOADED,
        sourcePath: String? = null,
    ): Shard = Shard(
        info = AlbumInfo(
            id = Uuid.random(),
            name = name,
            sourcePath = sourcePath,
            thumbsId = blobId(),
            state = state,
            encodingVersion = 0,
            addedAt = fixtureAddedAt,
        ),
        photos = listOf(library.row("p.jpg")),
    )
}
