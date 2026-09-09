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
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import net.stho.photos.IngestAbort
import net.stho.photos.catalog.testDrivers
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.catalog.FakeZone
import net.stho.photos.catalog.META_PREFIX
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.blobKey
import net.stho.photos.catalog.deleteTemporaryDirectories
import net.stho.photos.catalog.futureShard
import net.stho.photos.catalog.readBytes
import net.stho.photos.catalog.readShard
import net.stho.photos.catalog.shardKey
import net.stho.photos.catalog.temporaryDirectory
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

        fun blobKeys(): List<String> = zone.keys.filter { it.startsWith("blob/") }

        fun metaKeys(): List<String> = zone.keys.filter { it.startsWith(META_PREFIX) }
    }

    private fun cycle(label: String): Cycle = Cycle(label).also {
        for (path in listOf("Rauhöd/a.jpg", "Rauhöd/b.jpg", "Neuseeland/c.jpg")) {
            it.library.file(path)
        }
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
     * §7's promise, taken literally: a run that changes nothing costs **one** request. Not one
     * LIST plus a re-read of 288 shards — that is what the ETag cache beside them is for — and not
     * one LIST plus a 34,000-key sweep of `blob/` either, which is why the sweep stands down when
     * neither the zone nor the library moved.
     */
    @Test
    fun aRunWithNothingToDoIsExactlyOneList() = runTest {
        val cycle = cycle("one-list")
        cycle.run()

        val before = cycle.zone.listCount
        cycle.run()

        assertEquals(before + 1, cycle.zone.listCount)
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

    // -------------------------------------------------------------------------------- the sweep

    /**
     * Decision 15: unreferenced is not the same as abandoned. Below the age floor a blob is
     * indistinguishable from one the phone is uploading right now, so it is left alone.
     */
    @Test
    fun theSweepTakesOldCrashDebrisAndSparesYoungBlobs() = runTest {
        val cycle = cycle("sweep")
        cycle.run()

        val old = Uuid.random().blobKey
        val young = Uuid.random().blobKey
        cycle.zone.insert(old, ByteArray(100), age = 30.days)
        cycle.zone.insert(young, ByteArray(100), age = 1.minutes)

        // Debris is collected by the next run that has something to do. A run with nothing to do
        // skips the sweep, because it cannot have produced any and the alternative is paying
        // 34,000 keys an hour for the privilege of looking.
        cycle.library.file("Rauhöd/d.jpg")

        val report = cycle.run()

        assertEquals(1, report.sweptBlobs)
        assertEquals(1, report.youngUnreferencedBlobs)
        assertFalse(cycle.zone.contains(old))
        assertTrue(cycle.zone.contains(young))
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
        val orphan = Uuid.random().blobKey
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
    }
}
