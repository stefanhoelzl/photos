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
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import net.stho.photos.catalog.AlbumInfo
import net.stho.photos.catalog.AlbumState
import net.stho.photos.catalog.blobId
import net.stho.photos.catalog.SHARD_SCHEMA_VERSION
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.ShardProbe
import net.stho.photos.catalog.deleteTemporaryDirectories
import net.stho.photos.model.MediaType
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

    /**
     * A Live Photo's MOV is the one file in the library no row is named after — §3 keeps the pair
     * as one row with two blobs — so the row has to claim both files or the reconciler reads the
     * MOV as never ingested. It cannot be fixed downstream: deriving the pair again produces the
     * same single row, so the album would report itself changed on every run for ever.
     */
    @Test
    fun aLivePhotosMovIsClaimedByTheRowThatOwnsIt() {
        val library = LibraryFixture()
        library.file("Wochenende/IMG_0679.HEIC", bytes = 3_000)
        library.file("Wochenende/IMG_0679.mov", bytes = 2_000)
        val row = library.row("IMG_0679.HEIC", sourceBytes = 3_000)
            .copy(mediaType = MediaType.LIVE_PHOTO, liveVideoFilename = "IMG_0679.mov")
        val shard = library.shard("Wochenende", photos = emptyList()).copy(photos = listOf(row))

        val album = assertNotNull(library.plan(shards = listOf(shard)).albums.firstOrNull())

        assertTrue(album.uploads.isEmpty())
        assertTrue(album.drop.isEmpty())
        assertFalse(album.needsWrite)
    }

    /**
     * The same album as a shard written before schema 4 has it. The run is not a no-op — it must
     * still reach `commit`, which is what fills the name in — so what is asserted here is only
     * that the reconciler notices, not that it has the answer.
     */
    @Test
    fun aLivePhotoShardFromBeforeSchema4StillPlansAWrite() {
        val library = LibraryFixture()
        library.file("Wochenende/IMG_0679.HEIC", bytes = 3_000)
        library.file("Wochenende/IMG_0679.mov", bytes = 2_000)
        val row = library.row("IMG_0679.HEIC", sourceBytes = 3_000)
            .copy(mediaType = MediaType.LIVE_PHOTO)
        val shard = library.shard("Wochenende", photos = emptyList()).copy(photos = listOf(row))

        val album = assertNotNull(library.plan(shards = listOf(shard)).albums.firstOrNull())

        assertEquals(listOf("IMG_0679.mov"), album.uploads.map { it.name })
        assertTrue(album.drop.isEmpty())
        assertTrue(album.needsWrite)
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

    /**
     * An addition to an album whose shard is too new to read is not an addition to an album that is
     * gone: adopting it would make a second album of photos that belong in the first. It waits.
     */
    @Test
    fun anAdditionToATooNewAlbumIsNeitherMergedNorAdopted() {
        val library = LibraryFixture()
        library.file("Neuseeland/a.jpg")
        val probe = ShardProbe(albumId = Uuid.random(), sourcePath = "Neuseeland", schemaVersion = SHARD_SCHEMA_VERSION + 1)
        val addition = Shard(
            AlbumInfo(
                id = Uuid.random(),
                name = "Neuseeland",
                thumbsId = blobId(),
                state = AlbumState.UPLOADED,
                encodingVersion = 0,
                addedAt = fixtureAddedAt,
                addsTo = probe.albumId,
            ),
            listOf(library.row("b.jpg")),
        )

        val plan = library.plan(shards = listOf(addition), unreadable = listOf(probe))

        assertTrue(plan.merges.isEmpty())
        assertTrue(plan.pulls.isEmpty(), "not adopted as an album of its own")
    }

    /**
     * Two albums claiming one folder: picking either would upload into it and leave the other
     * lingering unseen, and neither run would ever say so. The folder is left alone and named.
     */
    @Test
    fun aDirectoryClaimedByTwoAlbumsIsLeftAloneAndNamed() {
        val library = LibraryFixture()
        library.file("Uropa/a.jpg")
        library.file("Uropa/b.jpg")
        val first = library.shard("Uropa", photos = listOf("a.jpg"))
        val second = library.shard("Uropa/", photos = listOf("a.jpg"))

        val plan = library.plan(shards = listOf(first, second))

        assertTrue(plan.albums.isEmpty())
        assertTrue(plan.deletions.isEmpty())
        assertEquals(
            listOf(DoubleClaim("Uropa", listOf(first.info.id, second.info.id).sortedBy(Uuid::toString))),
            plan.doublyClaimed,
        )
        assertFalse(plan.hasWork)
    }

    /** Once one of the two is gone, the folder reconciles against the survivor as usual. */
    @Test
    fun aSingleClaimOnTheSameFolderIsNotADoubleClaim() {
        val library = LibraryFixture()
        library.file("Uropa/a.jpg")
        val phone = phoneAlbum(library, "Uropa", state = AlbumState.UPLOADING, sourcePath = "Uropa")
        val mine = library.shard("Uropa", photos = listOf("a.jpg"))

        val plan = library.plan(shards = listOf(mine, phone))

        assertTrue(plan.doublyClaimed.isEmpty())
        assertEquals(mine.info.id, plan.albums.single().id)
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
    fun aNewPhoneAlbumIsPulledFromItsAdditionUnderTheIdThePhoneMinted() {
        val library = LibraryFixture()
        val phone = phoneAlbum(library, "Garten")

        val plan = library.plan(shards = listOf(phone))

        assertTrue(plan.deletions.isEmpty())
        val pull = plan.pulls.single()
        assertEquals(phone.info.id, pull.shard.info.id)
        assertEquals(phone.info.addsTo, pull.albumId)
        assertEquals("Garten", pull.sourcePath)
        assertFalse(pull.claimed)
    }

    /** The parent the phone chose is a folder on the laptop, so the album lands inside it. */
    @Test
    fun aNewPhoneAlbumLandsInItsParentsFolder() {
        val library = LibraryFixture()
        library.file("Reisen/Italien/a.jpg")
        val container = library.shard("Reisen", photos = emptyList())
        val italy = library.shard("Reisen/Italien", photos = listOf("a.jpg"), parent = container.info.id)
        val phone = phoneAlbum(library, "Kroatien", parent = container.info.id)

        val plan = library.plan(shards = listOf(container, italy, phone))

        assertEquals("Reisen/Kroatien", plan.pulls.single().sourcePath)
    }

    /** One album, however many uploads went into it before the laptop saw any: pulled once, merged after. */
    @Test
    fun theSecondAdditionToANewAlbumMergesIntoTheOneThePullMakes() {
        val library = LibraryFixture()
        val albumId = Uuid.random()
        val first = phoneAlbum(library, "Garten", albumId = albumId, addedAt = fixtureAddedAt)
        val second = phoneAlbum(library, "Garten", albumId = albumId, addedAt = Instant.fromEpochSeconds(fixtureAddedAt.epochSeconds + 60))

        val plan = library.plan(shards = listOf(second, first))

        val pull = plan.pulls.single()
        assertEquals(first.info.id, pull.shard.info.id)
        val merge = plan.merges.single()
        assertEquals(second.info.id, merge.addition.info.id)
        assertEquals(albumId, merge.target)
        assertEquals("Garten", merge.sourcePath)
    }

    /**
     * Names are unique per parent, ignoring case (§2). A name taken after the phone chose it is not
     * renamed and not landed beside: the album waits in the zone, named, until one is renamed.
     */
    @Test
    fun aNewPhoneAlbumWhoseNameIsTakenIsHeldBack() {
        val library = LibraryFixture()
        library.file("Wochenende/a.jpg")
        val mine = library.shard("Wochenende", photos = listOf("a.jpg"))
        val phone = phoneAlbum(library, "wochenende")
        val later = phoneAlbum(library, "wochenende", albumId = requireNotNull(phone.info.addsTo))

        val plan = library.plan(shards = listOf(mine, phone, later))

        assertTrue(plan.pulls.isEmpty())
        assertTrue(plan.merges.isEmpty(), "nothing to merge into while it waits")
        assertEquals(listOf(HeldBack("wochenende", requireNotNull(phone.info.addsTo))), plan.heldBack)
        assertEquals(listOf(mine.info.id), plan.albums.map(AlbumPlan::id))
    }

    /** A folder on the laptop no shard claims yet takes the name just the same. */
    @Test
    fun aFolderNoShardClaimsYetAlsoHoldsANewPhoneAlbumBack() {
        val library = LibraryFixture()
        library.file("Garten/a.jpg")
        val phone = phoneAlbum(library, "GARTEN")

        val plan = library.plan(shards = listOf(phone))

        assertTrue(plan.pulls.isEmpty())
        assertEquals("GARTEN", plan.heldBack.single().sourcePath)
    }

    /**
     * A run stopped in the middle of a pull: the addition is claimed, still `uploaded`, and its
     * folder holds some of its files. Those files are the pull's to finish, not a new album to
     * upload — walking them as one is how `Transdinarica` became two albums of 400 and 70 photos.
     */
    @Test
    fun aFolderAStoppedPullHalfFilledIsResumedRatherThanUploaded() {
        val library = LibraryFixture()
        library.file("Transdinarica/a.jpg")
        val phone = phoneAlbum(library, "Transdinarica", sourcePath = "Transdinarica", photos = listOf("a.jpg", "b.jpg"))

        val plan = library.plan(shards = listOf(phone))

        assertTrue(plan.albums.isEmpty(), "no album of its own: ${plan.albums.map(AlbumPlan::sourcePath)}")
        assertTrue(plan.deletions.isEmpty())
        assertTrue(plan.doublyClaimed.isEmpty())
        assertTrue(plan.heldBack.isEmpty(), "its own half-filled folder does not take its name")
        val pull = plan.pulls.single()
        assertEquals(phone.info.id, pull.shard.info.id)
        assertEquals("Transdinarica", pull.sourcePath)
        assertTrue(pull.claimed)
    }

    /**
     * A run stopped after the pull committed the album but before it deleted the addition. The album
     * is there and the addition names its folder: an ordinary merge, with nothing left to upload.
     */
    @Test
    fun aPullStoppedAfterItsCommitIsFinishedAsAMerge() {
        val library = LibraryFixture()
        library.file("Transdinarica/a.jpg")
        val album = library.shard("Transdinarica", photos = listOf("a.jpg"))
        val phone = phoneAlbum(
            library, "Transdinarica", albumId = album.info.id, sourcePath = "Transdinarica", photos = listOf("a.jpg"),
        )

        val plan = library.plan(shards = listOf(album, phone))

        assertTrue(plan.pulls.isEmpty())
        assertTrue(plan.doublyClaimed.isEmpty())
        assertEquals(phone.info.id, plan.merges.single().addition.info.id)
        assertTrue(plan.albums.single().uploads.isEmpty())
    }

    /**
     * A folder a stopped pull claims *and* an encoded album claims — what that bug left behind. It
     * is a double claim like any other: nothing uploaded into it, and the pull does not resume into
     * a folder another album owns.
     */
    @Test
    fun aFolderClaimedByAStoppedPullAndAnAlbumIsLeftAloneAndNamed() {
        val library = LibraryFixture()
        library.file("Transdinarica/a.jpg")
        library.file("Transdinarica/c.jpg")
        val mine = library.shard("Transdinarica", photos = listOf("a.jpg"))
        val phone = phoneAlbum(library, "Transdinarica", sourcePath = "Transdinarica", photos = listOf("a.jpg", "b.jpg"))

        val plan = library.plan(shards = listOf(mine, phone))

        assertTrue(plan.albums.isEmpty())
        assertTrue(plan.deletions.isEmpty())
        assertTrue(plan.pulls.isEmpty())
        assertEquals(
            listOf(DoubleClaim("Transdinarica", listOf(mine.info.id, phone.info.id).sortedBy(Uuid::toString))),
            plan.doublyClaimed,
        )
        assertFalse(plan.hasWork)
    }

    /**
     * `Reisen` and `reisen` side by side on a case-sensitive disk. Either could be the album, so
     * neither is — nor anything beneath them, which would otherwise lose its parent — and nothing
     * is deleted.
     *
     * Only a case-sensitive disk can hold the two at once, so only one can pose the question: on the
     * simulator's case-insensitive volume the second `mkdir` lands in the first folder, and there is
     * no clash to find. The Linux run is what asserts the rule.
     */
    @Test
    fun siblingFoldersNamedAlikeButForCaseAreLeftAloneWithTheirChildren() {
        val library = LibraryFixture()
        library.file("Reisen/Italien/a.jpg")
        library.file("reisen/Kroatien/b.jpg")
        // `exists` cannot tell the two apart here — it asks the filesystem, which folds the case.
        // What the walk *reports* can: one folder, or two.
        if (library.walk().albums.none { it.relativePath.trim('/') == "reisen/Kroatien" }) return
        library.file("Garten/c.jpg")
        val container = library.shard("Reisen", photos = emptyList())
        val italy = library.shard("Reisen/Italien", photos = listOf("a.jpg"), parent = container.info.id)

        val plan = library.plan(shards = listOf(container, italy))

        assertEquals(listOf(NameClash(listOf("Reisen", "reisen"))), plan.nameClashes)
        assertEquals(listOf("Garten"), plan.albums.map(AlbumPlan::sourcePath))
        assertTrue(plan.deletions.isEmpty())
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
     * A new album the phone finished uploading: an `uploaded` addition naming an album no shard is
     * yet, with no `source_path` because nothing has claimed it.
     */
    private fun phoneAlbum(
        library: LibraryFixture,
        name: String,
        albumId: Uuid = Uuid.random(),
        parent: Uuid? = null,
        state: AlbumState = AlbumState.UPLOADED,
        sourcePath: String? = null,
        photos: List<String> = listOf("p.jpg"),
        addedAt: Instant = fixtureAddedAt,
    ): Shard = Shard(
        info = AlbumInfo(
            id = Uuid.random(),
            name = name,
            parent = parent,
            sourcePath = sourcePath,
            thumbsId = blobId(),
            state = state,
            encodingVersion = 0,
            addedAt = addedAt,
            addsTo = albumId,
        ),
        photos = photos.map { library.row(it) },
    )
}
