package net.stho.photos.ingest

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import net.stho.photos.IngestAbort
import net.stho.photos.catalog.ADDITION_PREFIX
import net.stho.photos.catalog.AlbumInfo
import net.stho.photos.catalog.AlbumState
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.catalog.FakeZone
import net.stho.photos.catalog.META_PREFIX
import net.stho.photos.catalog.SHARD_SCHEMA_VERSION
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.blobId
import net.stho.photos.catalog.blobKey
import net.stho.photos.catalog.deleteTemporaryDirectories
import net.stho.photos.catalog.futureShard
import net.stho.photos.catalog.readBytes
import net.stho.photos.catalog.readShard
import net.stho.photos.catalog.shardKey
import net.stho.photos.catalog.writeTo
import net.stho.photos.derivative.DerivativeSpec
import net.stho.photos.catalog.temporaryDirectory
import net.stho.photos.catalog.testDrivers
import net.stho.photos.catalog.zoneClient
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.pipeline.MediaClassifier

/**
 * The whole cycle, over a synthetic library: first sync, no-op sync, one photo deleted, one album
 * removed, the marker taken away, and the sweep.
 *
 * §10's gate 2. It runs against the catalog suite's [FakeZone] rather than a live endpoint: the
 * wire is the storage module's business and already proven both against S3Mock and against the
 * live zone, whereas what this has to pin down is ingest's own state machine — including a blob's
 * age, which a test can only state if it owns the clock.
 */
class IngestCycleTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()

    /**
     * One library, one zone, one cache — and a fresh [Ingest] per run, because that is what an
     * hourly systemd unit is.
     */
    private class Cycle(label: String) {
        val library = LibraryFixture(label)
        val zone = FakeZone()
        val cacheRoot: Path = temporaryDirectory("ingest-$label-cache")
        val ids = randomIds

        var identifiers: Map<String, String> = emptyMap()

        suspend fun run(dryRun: Boolean = false): IngestReport {
            val config = IngestConfig(
                libraryRoot = library.root,
                cacheRoot = cacheRoot,
                jobs = 2,
                uploadJobs = 1,
                dryRun = dryRun,
                sweepAge = 7.days,
            )
            val probe = FakeProbe(identifiers)
            val backend = FakeBackend(identifiers)
            return CatalogSync(zoneClient(zone.engine), cacheRoot, testDrivers).use { catalog ->
                Ingest(
                    config = config,
                    s3 = zoneClient(zone.engine),
                    catalog = catalog,
                    pipeline = FakePipeline(ids, config.workRoot),
                    classifier = MediaClassifier(probe, backend),
                    ids = ids,
                    drivers = testDrivers,
                ).use { it.run() }
            }
        }

        /** The shard the zone now holds for an album, read back through the real reader. */
        fun shard(named: String): Shard {
            val scratch = temporaryDirectory("ingest-read")
            for (key in zone.keys.filter { it.startsWith(META_PREFIX) }) {
                val file = Path(scratch, "read.db")
                write(file, assertNotNull(zone.data(key)))
                val shard = file.readShard(testDrivers)
                if (shard.info.name == named) return shard
            }
            error("no album named $named in the zone")
        }

        /** Overwrite a shard in the zone, the way another writer would have — an album's, or an addition's. */
        fun writeShard(shard: Shard) {
            val scratch = temporaryDirectory("ingest-write")
            val file = Path(scratch, "write.db")
            shard.writeTo(file, testDrivers)
            zone.put(shard.info.key, file.readBytes(), "etag-${'$'}{Uuid.random()}")
        }

        /**
         * What the phone leaves in the zone when it adds [names] to [target] (§8): an `uploaded`
         * addition, and the full-quality blobs and pack it names.
         */
        fun addTo(target: Shard, vararg names: String, sourcePath: String? = null): Shard {
            val added = Shard(
                AlbumInfo(
                    id = Uuid.random(),
                    name = target.info.name,
                    parent = target.info.parent,
                    sourcePath = sourcePath,
                    thumbsId = blobId(),
                    state = AlbumState.UPLOADED,
                    encodingVersion = 0,
                    addedAt = fixtureAddedAt,
                    addsTo = target.info.id,
                ),
                names.map { library.row(it) },
            )
            for (id in added.objectIds) zone.put(id.blobKey, ByteArray(64) { 0x41 }, "e")
            writeShard(added)
            return added
        }

        fun additionKeys(): List<String> = zone.keys.filter { it.startsWith(ADDITION_PREFIX) }

        fun blobKeys(): List<String> = zone.keys.filter { it.startsWith("blob/") }

        fun metaKeys(): List<String> = zone.keys.filter { it.startsWith(META_PREFIX) }
    }

    private fun cycle(label: String): Cycle = Cycle(label).also {
        for (path in listOf("Rauhöd/a.jpg", "Rauhöd/b.jpg", "Neuseeland/c.jpg")) {
            it.library.file(path)
        }
    }

    // ------------------------------------------------------------------------------- re-encoding

    /**
     * §5's re-derive meeting §3's identity rule.
     *
     * Re-encoding is expressed as "drop every row, upload every file", because that reuses the
     * paths that already delete blobs and derive files. Done naively that mints fresh row ids —
     * and `photo.id` is what `cover_photo_id` points at and what the thumbnail pack keys by, so
     * a cover set on the phone would quietly resolve to nothing the moment the laptop pulled
     * the album.
     *
     * The pull is where this is reachable today: with `ENCODING_VERSION` at 1 an *encoded*
     * album cannot legally sit below the profile — the schema's second CHECK forbids version 0
     * there — so a phone album at `uploaded` is the only thing the re-derive path currently
     * fires for. The carry-over is the same code either way.
     */
    @Test
    fun aPulledAlbumKeepsItsRowIdentityAndItsCover() = runTest {
        val cycle = Cycle("reencode")

        val photos = listOf(cycle.library.row("a.jpg"), cycle.library.row("b.jpg"))
        val phone = Shard(
            info = AlbumInfo(
                id = Uuid.random(),
                name = "FromPhone",
                sourcePath = null,
                thumbsId = null,
                state = AlbumState.UPLOADED,
                encodingVersion = 0,
                coverPhotoId = photos[1].id,
                addedAt = fixtureAddedAt,
            ),
            photos = photos,
        )
        for (row in photos) {
            cycle.zone.put(assertNotNull(row.imageId).blobKey, ByteArray(64) { 0x41 }, "e")
        }
        cycle.writeShard(phone)

        cycle.run()

        val after = cycle.shard("FromPhone")
        assertEquals(AlbumState.ENCODED, after.info.state)
        assertEquals(DerivativeSpec.ENCODING_VERSION, after.info.encodingVersion)
        assertEquals(
            photos.map(PhotoRow::id).toSet(),
            after.photos.map(PhotoRow::id).toSet(),
            "a re-derived photo is the same photo",
        )
        assertEquals(
            photos[1].id,
            after.info.coverPhotoId,
            "the cover points at a row id, so it must survive the re-derive",
        )
        // §2: new UUIDs rather than a rewritten blob, and the phone's originals are gone.
        assertTrue(
            phone.objectIds.toSet().intersect(after.objectIds.toSet()).isEmpty(),
            "re-encoding mints new blobs",
        )
        for (id in phone.objectIds) {
            assertFalse(cycle.zone.contains(id.blobKey), "the phone's blob should be deleted")
        }
    }

    /**
     * A run stopped halfway through a pull (§7): the album claimed, still `uploaded`, some of its
     * files already in the folder. The next run finishes that album into that folder — it does not
     * first upload the files already there as an album of their own, which is how `Transdinarica`
     * became two albums claiming one folder.
     */
    @Test
    fun aPullInterruptedAfterPartOfItsDownloadResumesWithoutADuplicateAlbum() = runTest {
        val cycle = Cycle("pull-resume")
        val photos = listOf(cycle.library.row("a.jpg"), cycle.library.row("b.jpg"))
        val phone = Shard(
            info = AlbumInfo(
                id = Uuid.random(),
                name = "FromPhone",
                sourcePath = "FromPhone",
                thumbsId = null,
                state = AlbumState.UPLOADED,
                encodingVersion = 0,
                addedAt = fixtureAddedAt,
            ),
            photos = photos,
        )
        for (row in photos) {
            cycle.zone.put(assertNotNull(row.imageId).blobKey, ByteArray(64) { 0x41 }, "e")
        }
        cycle.writeShard(phone)
        cycle.library.file("FromPhone/a.jpg")

        val report = cycle.run()

        assertTrue(report.failures.isEmpty(), report.failures.toString())
        assertEquals(1, cycle.metaKeys().size, "one album for the folder, not a second beside it")
        val after = cycle.shard("FromPhone")
        assertEquals(phone.info.id, after.info.id)
        assertEquals(AlbumState.ENCODED, after.info.state)
        assertEquals(photos.map(PhotoRow::id).toSet(), after.photos.map(PhotoRow::id).toSet())

        val again = cycle.run()
        assertTrue(again.doublyClaimed.isEmpty())
        assertEquals(0, again.uploadedFiles)
    }

    // ---------------------------------------------------------------------------------- additions

    /**
     * §7's merge, end to end: photos the phone added to an album land in its folder — the one whose
     * name the folder already had gaining a suffix, since only the laptop names files — and in its
     * shard, as the same photographs the phone described, and the addition and its blobs are gone.
     */
    @Test
    fun anAdditionIsMergedIntoItsAlbumsFolderAndShard() = runTest {
        val cycle = cycle("merge")
        cycle.run()
        val before = cycle.shard("Rauhöd")
        val added = cycle.addTo(before, "a.jpg", "d.jpg")

        val report = cycle.run()

        assertTrue(report.failures.isEmpty(), report.failures.toString())
        assertEquals(listOf(IngestReport.MergedAddition("Rauhöd", 2)), report.mergedAdditions)
        val after = cycle.shard("Rauhöd")
        assertEquals(before.info.id, after.info.id)
        assertEquals(AlbumState.ENCODED, after.info.state)
        assertEquals(
            listOf("a (2).jpg", "a.jpg", "b.jpg", "d.jpg"),
            after.photos.map(PhotoRow::filename).sorted(),
        )
        assertTrue(cycle.library.exists("Rauhöd/a (2).jpg"))
        assertTrue(cycle.library.exists("Rauhöd/d.jpg"))
        assertEquals(
            added.photos.map(PhotoRow::id).toSet(),
            after.photos.map(PhotoRow::id).toSet() - before.photos.map(PhotoRow::id).toSet(),
            "a merged photo is the photo the phone added",
        )
        assertNotEquals(before.info.thumbsId, after.info.thumbsId, "the pack is repacked with the new thumbnails")
        assertTrue(cycle.additionKeys().isEmpty())
        for (id in added.objectIds) {
            assertFalse(cycle.zone.contains(id.blobKey), "the phone's blob should be swept")
        }

        val again = cycle.run()
        assertEquals(0, again.uploadedFiles, "the merged files are the album's rows, not new photos")
        assertEquals(4, cycle.shard("Rauhöd").photos.size)
    }

    /**
     * A run that stopped after the claim and the download left the files in the folder (§7). The walk
     * must not upload them as photos of its own: the merge that resumes brings them in, once.
     */
    @Test
    fun aMergeInterruptedAfterItsDownloadResumesWithoutDuplicates() = runTest {
        val cycle = cycle("merge-resume")
        cycle.run()
        val before = cycle.shard("Rauhöd")
        val added = cycle.addTo(before, "d.jpg", sourcePath = "Rauhöd")
        cycle.library.file("Rauhöd/d.jpg")

        val report = cycle.run()

        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val after = cycle.shard("Rauhöd")
        assertEquals(listOf("a.jpg", "b.jpg", "d.jpg"), after.photos.map(PhotoRow::filename).sorted())
        assertTrue(added.photos.single().id in after.photos.map(PhotoRow::id))
        assertTrue(cycle.additionKeys().isEmpty())
    }

    /** A run that stopped after writing the album but before deleting the addition has one step left. */
    @Test
    fun aMergeInterruptedAfterItsCommitOnlyDeletesTheAddition() = runTest {
        val cycle = cycle("merge-commit")
        cycle.library.file("Rauhöd/d.jpg")
        cycle.run()
        val album = cycle.shard("Rauhöd")
        val merged = album.photos.single { it.filename == "d.jpg" }
        cycle.addTo(album, "d.jpg", sourcePath = "Rauhöd")

        val report = cycle.run()

        assertEquals(1, report.mergedAdditions.size)
        assertEquals(0, report.uploadedFiles)
        assertEquals(album.photos.toSet(), cycle.shard("Rauhöd").photos.toSet())
        assertTrue(merged.id in cycle.shard("Rauhöd").photos.map(PhotoRow::id))
        assertTrue(cycle.additionKeys().isEmpty())
    }

    /**
     * An addition whose album was deleted before the merge becomes the album it records (§8): pulled
     * into a folder of its own and encoded, rather than lost with the folder it was meant for. Not
     * *that* folder, though: this run deleted it, and a pull never puts a deleted folder back.
     */
    @Test
    fun anAdditionWhoseAlbumIsDeletedBecomesAnAlbumOfItsOwn() = runTest {
        val cycle = cycle("merge-adopt")
        cycle.run()
        val neuseeland = cycle.shard("Neuseeland")
        val added = cycle.addTo(neuseeland, "x.jpg", "x.jpg")
        cycle.library.remove("Neuseeland")

        val report = cycle.run()

        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val adopted = cycle.shard("Neuseeland")
        assertEquals(added.info.id, adopted.info.id)
        assertEquals(AlbumState.ENCODED, adopted.info.state)
        assertEquals(listOf("x (2).jpg", "x.jpg"), adopted.photos.map(PhotoRow::filename).sorted())
        val folder = assertNotNull(adopted.info.sourcePath)
        assertTrue(folder.startsWith("Neuseeland (") && folder != "Neuseeland", folder)
        assertTrue(cycle.library.exists("$folder/x (2).jpg"))
        assertFalse(cycle.library.exists("Neuseeland"))
        assertEquals(added.photos.map(PhotoRow::id).toSet(), adopted.photos.map(PhotoRow::id).toSet())
        assertTrue(cycle.additionKeys().isEmpty())
        assertEquals(2, cycle.metaKeys().size)
    }

    /**
     * An adoption a run stopped partway through (§8): the addition already written to `meta/` as the
     * album it records, its `addition/` key gone, the folder claimed and some of its files already
     * there. The next run finishes pulling it into that folder rather than uploading those files as
     * another album.
     */
    @Test
    fun anAdoptionInterruptedAfterPartOfItsDownloadResumesWithoutADuplicateAlbum() = runTest {
        val cycle = cycle("merge-adopt-resume")
        cycle.run()
        val neuseeland = cycle.shard("Neuseeland")
        val added = cycle.addTo(neuseeland, "x.jpg", "y.jpg")
        // What the stopped run had done: deleted the album, adopted the addition and claimed a folder
        // for it, and downloaded one of its two files.
        cycle.library.remove("Neuseeland")
        cycle.zone.remove(neuseeland.info.id.shardKey)
        cycle.zone.remove(added.info.key)
        val folder = "Neuseeland (${added.info.id.toString().take(8)})"
        cycle.writeShard(Shard(added.info.copy(addsTo = null, sourcePath = folder), added.photos))
        cycle.library.file("$folder/x.jpg")

        val report = cycle.run()

        assertTrue(report.failures.isEmpty(), report.failures.toString())
        assertEquals(2, cycle.metaKeys().size, "Rauhöd and the adopted album, nothing beside it")
        val adopted = cycle.shard("Neuseeland")
        assertEquals(added.info.id, adopted.info.id)
        assertEquals(AlbumState.ENCODED, adopted.info.state)
        assertEquals(folder, adopted.info.sourcePath)
        assertEquals(added.photos.map(PhotoRow::id).toSet(), adopted.photos.map(PhotoRow::id).toSet())
        assertTrue(cycle.library.exists("$folder/y.jpg"))

        val again = cycle.run()
        assertTrue(again.doublyClaimed.isEmpty())
        assertEquals(0, again.uploadedFiles)
    }

    /** Still uploading: nothing merges it, and the album it adds to is left exactly as it was. */
    @Test
    fun anAdditionStillUploadingIsLeftAlone() = runTest {
        val cycle = cycle("merge-uploading")
        cycle.run()
        val before = cycle.shard("Rauhöd")
        val added = cycle.addTo(before, "d.jpg")
        // Just now: an upload older than the sweep's floor is abandoned and collected, which is the
        // other thing that can happen to one.
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        cycle.writeShard(added.copy(info = added.info.copy(state = AlbumState.UPLOADING, addedAt = now)))

        val report = cycle.run()

        assertTrue(report.mergedAdditions.isEmpty())
        assertEquals(before, cycle.shard("Rauhöd"))
        assertFalse(cycle.library.exists("Rauhöd/d.jpg"))
        assertEquals(listOf(added.info.key), cycle.additionKeys())
    }

    // -------------------------------------------------------------------------------- first sync

    @Test
    fun aFirstSyncWritesAShardPerAlbumAndABlobPerDerivative() = runTest {
        val cycle = cycle("first")

        val report = cycle.run()

        assertEquals(2, report.albums.size)
        assertEquals(3, report.uploadedFiles)
        assertTrue(report.failures.isEmpty())
        assertFalse(report.hasProblems)

        assertEquals(2, cycle.metaKeys().size)
        // Per photo: one viewing image, and nothing else -- the zone holds no originals.
        // Per album: one thumbnail pack.
        assertEquals(3 + 2, cycle.blobKeys().size)

        // Every shard says which folder it came from, which is the only thing reconnecting a
        // directory to its album next run.
        for (name in listOf("Rauhöd", "Neuseeland")) {
            val shard = cycle.shard(name)
            assertNotNull(shard.info.sourcePath)
            assertNotNull(shard.info.thumbsId)
        }
    }

    /**
     * §7's promise, taken literally: a run that changes nothing costs **two** requests — the LISTs
     * of `meta/` and `addition/`. Not those plus a re-read of 288 shards — that is what the ETag
     * cache beside them is for — and not those plus a 34,000-key sweep of `blob/` either, which is
     * why the sweep stands down when neither the zone nor the library moved.
     */
    @Test
    fun aRunWithNothingToDoIsExactlyTwoLists() = runTest {
        val cycle = cycle("one-list")
        cycle.run()

        val before = cycle.zone.listCount
        cycle.run()

        assertEquals(before + 2, cycle.zone.listCount)
    }

    @Test
    fun aSecondSyncUploadsNothingAndWritesNothing() = runTest {
        val cycle = cycle("noop")
        cycle.run()
        val putsAfterFirst = cycle.zone.putCount
        val keysAfterFirst = cycle.zone.keys

        val report = cycle.run()

        assertEquals(0, report.uploadedFiles)
        assertEquals(0, report.droppedRows)
        assertEquals(putsAfterFirst, cycle.zone.putCount)
        assertEquals(keysAfterFirst, cycle.zone.keys)
    }

    // --------------------------------------------------------------------------------- deletion

    /**
     * The deletion model, end to end: `rm` on one file, and its row and its blobs go with it —
     * automatically, with no confirmation step anywhere.
     */
    @Test
    fun deletingAFileDropsItsRowAndItsBlobs() = runTest {
        val cycle = cycle("drop")
        cycle.run()

        val before = cycle.shard("Rauhöd")
        val doomed = assertNotNull(before.photos.firstOrNull { it.filename == "b.jpg" })
        cycle.library.remove("Rauhöd/b.jpg")

        val report = cycle.run()

        assertEquals(1, report.droppedRows)
        val after = cycle.shard("Rauhöd")
        assertEquals(listOf("a.jpg"), after.photos.map(PhotoRow::filename))
        for (id in doomed.objectIds) assertFalse(cycle.zone.contains(id.blobKey))

        // The pack was rebuilt, and the one it replaced is gone.
        assertNotEquals(before.info.thumbsId, after.info.thumbsId)
        assertFalse(cycle.zone.contains(assertNotNull(before.info.thumbsId).blobKey))
    }

    /**
     * Deleting an album's files with `rm` is not how an album is deleted, and the zone must reflect that: the album is
     * still there, holding nothing.
     */
    @Test
    fun emptyingAnAlbumLeavesItInTheZoneWithNoPhotos() = runTest {
        val cycle = cycle("empty")
        cycle.run()

        cycle.library.remove("Rauhöd/a.jpg")
        cycle.library.remove("Rauhöd/b.jpg")
        cycle.run()

        val after = cycle.shard("Rauhöd")
        assertTrue(after.photos.isEmpty())
        assertNull(after.info.thumbsId)
    }

    /** `rm -rf`, the one gesture that means "this album leaves the system". */
    @Test
    fun removingADirectoryDeletesTheAlbumAndEverythingItOwned() = runTest {
        val cycle = cycle("rmrf")
        cycle.run()

        val doomed = cycle.shard("Rauhöd")
        cycle.library.remove("Rauhöd")

        val report = cycle.run()

        assertEquals(1, report.deletedAlbums.size)
        assertFalse(cycle.zone.contains(doomed.info.id.shardKey))
        for (id in doomed.objectIds) assertFalse(cycle.zone.contains(id.blobKey))
        // The other album is untouched.
        assertEquals(1, cycle.metaKeys().size)
    }

    // ---------------------------------------------------------------------------- the two guards

    /**
     * The one structural guard. An unmounted disk and a mistyped root both look like this, and both
     * would otherwise read as a library whose every album had been deleted.
     */
    @Test
    fun noPhotosignoreMeansNoRunAndNothingIsWritten() = runTest {
        val cycle = cycle("marker")
        cycle.run()

        val before = cycle.zone.keys
        val deletesBefore = cycle.zone.deleteCount
        cycle.library.removeMarker()
        cycle.library.remove("Rauhöd") // would otherwise delete an album

        assertFailsWith<IngestAbort.NotALibraryRoot> { cycle.run() }

        assertEquals(before, cycle.zone.keys)
        assertEquals(deletesBefore, cycle.zone.deleteCount)
    }

    /**
     * §7 asserts images never change on disk, and checks it. A changed file means the library broke
     * its contract, so the run writes nothing at all rather than guessing.
     */
    @Test
    fun aFileThatChangedSizeAbortsTheWholeRun() = runTest {
        val cycle = cycle("changed")
        cycle.run()

        val before = cycle.zone.keys
        cycle.library.file("Rauhöd/a.jpg", bytes = 4096)

        val abort = assertFailsWith<IngestAbort.FileChanged> { cycle.run() }

        assertEquals(1, abort.mismatches.size)
        assertEquals(before, cycle.zone.keys)
    }

    // ------------------------------------------------------------------- content addressing

    /**
     * The correctness cost of content addressing, and the test that says it was paid.
     *
     * Two albums holding the same photograph reference one blob. Deleting one of them used to
     * be "delete the objects this shard lists", which under §2's keys was safe because every
     * blob had exactly one referent. It is not safe any more, and the failure would be silent:
     * the surviving album keeps a row pointing at bytes that are gone.
     */
    @Test
    fun deletingAnAlbumSparesBlobsAnotherAlbumStillReferences() = runTest {
        val cycle = Cycle("shared")
        // Same name, so the fake pipeline derives the same bytes and therefore the same key.
        cycle.library.file("Rauhöd/same.jpg")
        cycle.library.file("Neuseeland/same.jpg")
        cycle.run()

        val shared = assertNotNull(cycle.shard("Rauhöd").photos.single().imageId)
        assertEquals(
            shared,
            cycle.shard("Neuseeland").photos.single().imageId,
            "identical content must land on one key, or this test proves nothing",
        )

        cycle.library.remove("Rauhöd")
        val report = cycle.run()

        assertEquals(1, report.deletedAlbums.size)
        assertTrue(
            cycle.zone.contains(shared.blobKey),
            "the surviving album still points at it",
        )
        assertEquals(shared, cycle.shard("Neuseeland").photos.single().imageId)
    }

    /**
     * §2's idempotence, which is what makes a crashed import resumable: the run re-derives
     * everything and re-uploads only what the zone does not already hold. Forged by deleting a
     * shard, which is what a crash between uploading an album's blobs and writing its shard
     * leaves behind.
     *
     * **The thumbnail pack is the exception, and the reason is worth knowing.** A pack keys its
     * rows by `photo.id`, so its bytes depend on row identity and not only on the thumbnails
     * inside it. An album whose shard is gone is a *new* album — new album id, new row ids — so
     * its pack legitimately differs. That is not a hole in decision 17's promise that a profile
     * bump sends no packs: a re-encode keeps the album and carries every `photo.id` across, so
     * there the pack really is byte-identical.
     */
    @Test
    fun aRerunReUploadsOnlyWhatIdentityForcedItTo() = runTest {
        val cycle = cycle("resume")
        cycle.run()
        val imagesAfterFirst = cycle.shard("Rauhöd").photos.mapNotNull { it.imageId }.toSet() +
            cycle.shard("Neuseeland").photos.mapNotNull { it.imageId }
        val blobsAfterFirst = cycle.blobKeys().toSet()

        val orphanedAlbum = cycle.shard("Rauhöd")
        cycle.zone.remove(orphanedAlbum.info.id.shardKey)

        val report = cycle.run()

        assertTrue(report.skippedUploads > 0, "the images were all already up there")
        // Every image blob survives untouched: identical content, identical key, no PUT.
        for (id in imagesAfterFirst) {
            assertTrue(cycle.zone.contains(id.blobKey), "$id should have been reused")
        }
        // Exactly one key changed hands — the repacked thumbnails — and the old pack is gone.
        val now = cycle.blobKeys().toSet()
        assertEquals(1, (now - blobsAfterFirst).size, "only the pack should be new")
        assertEquals(1, (blobsAfterFirst - now).size, "and the old pack should be swept")
    }

    // -------------------------------------------------------------------------------- the sweep

    /**
     * §2 removed the age floor from garbage collection, and this is the assertion that says so.
     *
     * The floor existed because an unreferenced blob might have belonged to an upload in
     * flight. §8's manifest names every blob before writing any of them, so it cannot: an
     * unreferenced blob is garbage the moment it is unreferenced, however new. Age now decides
     * one thing only, and it is not this — see the abandoned-upload test.
     */
    @Test
    fun theSweepTakesUnreferencedBlobsWhateverTheirAge() = runTest {
        val cycle = cycle("sweep")
        cycle.run()

        val old = blobId("old").blobKey
        val young = blobId("young").blobKey
        cycle.zone.insert(old, ByteArray(100), age = 30.days)
        cycle.zone.insert(young, ByteArray(100), age = 1.minutes)

        // Debris is collected by the next run that has something to do. A run with nothing to do
        // skips the sweep, because it cannot have produced any and the alternative is paying
        // 34,000 keys an hour for the privilege of looking.
        cycle.library.file("Rauhöd/d.jpg")

        val report = cycle.run()

        // Three, not two: the album's superseded thumbnail pack is collected here as well.
        // Nothing deletes blobs eagerly any more — asking "does another album still want this?"
        // per album would mean re-reading every shard per album, and the sweep answers it once
        // for the whole zone (§2).
        assertEquals(3, report.sweptBlobs)
        assertFalse(cycle.zone.contains(old))
        assertFalse(cycle.zone.contains(young), "a young orphan is still an orphan")
    }

    /**
     * The sweep's referenced set comes from the shards it can read. One it cannot read owns blobs
     * it cannot enumerate, so sweeping would delete a readable album's photographs on the strength
     * of a file it never opened.
     */
    @Test
    fun theSweepStandsDownWhenAnyShardIsTooNewToRead() = runTest {
        val cycle = cycle("sweep-blocked")
        cycle.run()

        val forgeDirectory = temporaryDirectory("ingest-forge")
        val forged = futureShard(forgeDirectory)
        cycle.zone.put(
            forged.info.id.shardKey,
            Path(forgeDirectory, "${forged.info.id}.db").readBytes(),
            "future-1",
        )
        val orphan = blobId("orphan").blobKey
        cycle.zone.insert(orphan, ByteArray(100), age = 30.days)

        val report = cycle.run()

        assertNotNull(report.sweepSkipped)
        assertEquals(0, report.sweptBlobs)
        assertTrue(cycle.zone.contains(orphan))
        assertEquals(1, report.blockedByUnreadable.size)
    }

    // -------------------------------------------------------------------------------- the dry run

    @Test
    fun aDryRunReportsTheSamePlanAndChangesNothing() = runTest {
        val cycle = cycle("dry")

        val report = cycle.run(dryRun = true)

        assertTrue(report.dryRun)
        assertEquals(2, report.albums.size)
        assertEquals(3, report.uploadedFiles)
        assertEquals(0, cycle.zone.putCount)
        assertTrue(cycle.zone.keys.isEmpty())
    }

    // ------------------------------------------------------------- Live Photo pairing across runs

    /**
     * The one case where an album's *claimed* files still matter to a later run.
     *
     * A Live Photo is a HEIC and a MOV that pair on `content.identifier`, and only the HEIC gets a
     * row — §3 keeps a Live Photo as one row with two blobs. So a second sync that classified only
     * the unclaimed files would be handed a lone MOV, decide it was an ordinary video, and
     * transcode and upload all 187 of them a second time as separate photos. Nothing else in the
     * design notices: the shard would simply grow rows that look legitimate.
     */
    @Test
    fun aLivePhotosMovIsNotReUploadedAsAVideoOnTheNextRun() = runTest {
        val cycle = Cycle("live")
        val identifier = "B34B6B99-C28F-4E16-A788-79AA0E30BB18"
        cycle.library.file("Wochenende/IMG_0679.HEIC")
        cycle.library.file("Wochenende/IMG_0679.mov")
        cycle.identifiers = mapOf(
            "IMG_0679.HEIC" to identifier,
            "IMG_0679.mov" to identifier,
        )

        val first = cycle.run()
        assertEquals(1, first.uploadedFiles) // one Live Photo, not a photo and a video

        val second = cycle.run()
        assertEquals(0, second.uploadedFiles)
        assertTrue(second.failures.isEmpty())
        assertTrue(second.strays.isEmpty())

        val shard = cycle.shard("Wochenende")
        assertEquals(1, shard.photos.size)
        assertEquals(MediaType.LIVE_PHOTO, shard.photos[0].mediaType)
        assertNotNull(shard.photos[0].liveVideoId)
        assertNull(shard.photos[0].videoId) // never became a video of its own
        assertEquals("IMG_0679.mov", shard.photos[0].liveVideoFilename)
    }

    /**
     * The bug this column exists for, stated as the run a person actually sees.
     *
     * A Live Photo is two files and one row (§3), so before schema 4 the MOV was a file no row
     * was named after — and nothing about deriving the album again could fix that, because the
     * pair produces one row and the row names the still. So the reconciler planned the MOV as an
     * upload, `commit` found the pair already claimed and produced nothing, and the shard was
     * rewritten identically. Every run. For ever, on every album with a Live Photo in it — which
     * on this library is three albums, 187 photographs and 376 MB of "to read" that never was.
     */
    @Test
    fun anAlbumOfLivePhotosIsQuietOnTheSecondRun() = runTest {
        val cycle = Cycle("live-quiet")
        val identifier = "B34B6B99-C28F-4E16-A788-79AA0E30BB18"
        cycle.library.file("Wochenende/IMG_0679.HEIC")
        cycle.library.file("Wochenende/IMG_0679.mov")
        cycle.identifiers = mapOf(
            "IMG_0679.HEIC" to identifier,
            "IMG_0679.mov" to identifier,
        )
        cycle.run()
        val putsAfterFirst = cycle.zone.putCount
        val keysAfterFirst = cycle.zone.keys

        val second = cycle.run()

        assertTrue(second.albums.isEmpty()) // not "~ Wochenende" with nothing to show for it
        assertEquals(putsAfterFirst, cycle.zone.putCount)
        assertEquals(keysAfterFirst, cycle.zone.keys)
        assertEquals("IMG_0679.mov", cycle.shard("Wochenende").photos.single().liveVideoFilename)
    }

    /**
     * The upgrade, which is the whole reason the fix is not merely a new column: every shard in
     * the zone predates it, and a row already ingested is never derived again — so nothing would
     * ever fill the name in unless this run does.
     *
     * It is a metadata repair and has to stay one: the pairing comes from the classifier, which
     * has just read the headers anyway, so no blob moves, nothing is re-encoded, and `photo.id`
     * — which `cover_photo_id` points at and the thumbnail pack keys by — does not change.
     */
    @Test
    fun aShardFromBeforeSchema4LearnsItsMovsNameAndThenGoesQuiet() = runTest {
        val cycle = Cycle("live-heal")
        val identifier = "9C7E3A1F-0B44-4D22-9E61-7F2A55C10D3E"
        cycle.library.file("Wochenende/IMG_0679.HEIC")
        cycle.library.file("Wochenende/IMG_0679.mov")
        cycle.identifiers = mapOf(
            "IMG_0679.HEIC" to identifier,
            "IMG_0679.mov" to identifier,
        )
        cycle.run()

        // Roll the zone back to what a schema-3 writer left behind: the pair correctly ingested,
        // and no record of which file the MOV was.
        val before = cycle.shard("Wochenende")
        cycle.writeShard(
            before.copy(
                info = before.info.copy(schemaVersion = 3),
                photos = before.photos.map { it.copy(liveVideoFilename = null) },
            ),
        )
        val putsBeforeHeal = cycle.zone.putCount
        val blobsBeforeHeal = cycle.blobKeys().toSet()

        val healing = cycle.run()

        assertEquals(0, healing.uploadedFiles)
        assertEquals(0, healing.droppedRows)
        assertTrue(healing.failures.isEmpty())
        assertEquals(blobsBeforeHeal, cycle.blobKeys().toSet()) // nothing re-derived
        assertEquals(putsBeforeHeal + 1, cycle.zone.putCount) // the shard, and only the shard

        val healed = cycle.shard("Wochenende")
        assertEquals("IMG_0679.mov", healed.photos.single().liveVideoFilename)
        assertEquals(SHARD_SCHEMA_VERSION, healed.info.schemaVersion)
        assertEquals(before.photos.single().id, healed.photos.single().id)
        assertEquals(before.info.thumbsId, healed.info.thumbsId)

        val putsAfterHeal = cycle.zone.putCount
        cycle.run()

        assertEquals(putsAfterHeal, cycle.zone.putCount)
    }
}
