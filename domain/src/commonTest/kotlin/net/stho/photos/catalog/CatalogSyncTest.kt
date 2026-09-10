package net.stho.photos.catalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.model.MediaType
import net.stho.photos.storage.ETag
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.TEST_SECRET
import net.stho.photos.storage.testStorage

/**
 * §4's loop, offline. LIST is the entire sync mechanism, so what the diff does with each kind of
 * LIST entry is the whole correctness story — and a missing key means "album deleted", which
 * makes a misparse expensive.
 */
class CatalogSyncTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()


    private class Fixture(val zone: FakeZone, val cacheRoot: Path) {
        val sync: CatalogSync = CatalogSync(zoneClient(zone.engine), cacheRoot, testDrivers)

        fun upload(shard: Shard, etag: String) {
            val scratch = Path(cacheRoot, "upload-${shard.info.id}.db")
            shard.writeTo(scratch, testDrivers)
            zone.put(shard.info.id.shardKey, scratch.readBytes(), etag)
            SystemFileSystem.delete(scratch)
        }

        fun reader(): CatalogReader = CatalogReader(Path(cacheRoot, "merged.db"), testDrivers)
    }

    private fun fixture(label: String = "sync") = Fixture(FakeZone(), temporaryDirectory(label))

    // ------------------------------------------------------------------------------ first run

    @Test
    fun aFirstSyncFetchesEveryShardAndBuildsTheCatalog() = runTest {
        val fixture = fixture()
        val shards = containerTree("Kalifornien", listOf("Yosemite", "Big Sur"))
        shards.forEachIndexed { index, shard -> fixture.upload(shard, "etag-$index") }

        val report = fixture.sync.sync()

        assertEquals(3, report.fetchedShards.size)
        assertTrue(report.rebuilt)
        assertEquals(3, report.albums)
        assertEquals(6, report.photos)
        assertFalse(report.hasAnomalies)
        assertEquals(3, fixture.reader().allAlbums().size)
    }

    /**
     * §4 calls the single LIST "the sync plan". A run that changes nothing must cost that one
     * request and no more — the hourly systemd unit depends on it.
     */
    @Test
    fun anUnchangedSecondSyncIsOneListAndNoFetches() = runTest {
        val fixture = fixture("noop")
        fixture.upload(album("Sommer", photos = listOf(photo("a.jpg"))), "e1")

        fixture.sync.sync()
        val before = fixture.zone.requestedKeys.size

        val report = fixture.sync.sync()

        assertTrue(report.fetchedShards.isEmpty())
        assertFalse(report.rebuilt)
        assertEquals(before, fixture.zone.requestedKeys.size) // no object GETs at all
        assertEquals(2, fixture.zone.listCount)
    }

    @Test
    fun onlyTheShardWhoseETagMovedIsRefetched() = runTest {
        val fixture = fixture("changed")
        val a = album("A", photos = listOf(photo("a.jpg")))
        fixture.upload(a, "a1")
        fixture.upload(album("B", photos = listOf(photo("b.jpg"))), "b1")

        fixture.sync.sync()
        fixture.upload(a.copy(photos = a.photos + photo("a2.jpg")), "a2")

        val report = fixture.sync.sync()

        assertEquals(listOf(a.info.id), report.fetchedShards)
        assertEquals(3, report.photos)
    }

    // ------------------------------------------------------------------------------- deletion

    /** §4: there is no manifest, so absence *is* the signal. */
    @Test
    fun aKeyThatVanishedMeansTheAlbumWasDeleted() = runTest {
        val fixture = fixture("deleted")
        fixture.upload(album("Keep", photos = listOf(photo("k.jpg"))), "k1")
        val drop = album("Drop", photos = listOf(photo("d.jpg")))
        fixture.upload(drop, "d1")

        fixture.sync.sync()
        fixture.zone.remove(drop.info.id.shardKey)
        val report = fixture.sync.sync()

        assertEquals(listOf(drop.info.id), report.deletedAlbums)
        assertEquals(listOf("Keep"), fixture.reader().allAlbums().map(Album::name))
    }

    // ------------------------------------------------------- what LIST returns that is not a shard

    /**
     * §2: keys no longer nest, but bunny.net still materialises the prefix's own marker, and it
     * comes back with Size 0 and no ETag. Treating it as a shard would break the diff.
     */
    @Test
    fun theMetaDirectoryMarkerIsIgnoredNotTreatedAsAnAlbum() = runTest {
        val fixture = fixture("marker")
        fixture.zone.put("meta/", ByteArray(0), "")
        fixture.upload(album("Real", photos = listOf(photo("a.jpg"))), "r1")

        val report = fixture.sync.sync()

        assertEquals(1, report.albums)
        assertEquals(listOf("meta/"), report.ignoredKeys)
        assertTrue(report.deletedAlbums.isEmpty())
    }

    @Test
    fun aKeyWhoseNameIsNotAUuidIsIgnoredRatherThanGuessedAt() = runTest {
        val fixture = fixture("junk")
        fixture.zone.put("meta/leftover.db", "junk".encodeToByteArray(), "x1")
        fixture.upload(album("Real"), "r1")

        val report = fixture.sync.sync()

        assertEquals(1, report.albums)
        assertEquals(listOf("meta/leftover.db"), report.ignoredKeys)
    }

    // ------------------------------------------ anomalies the design reports rather than resolves

    /**
     * §3: a shard from a newer writer is skipped and named. The CLI must be able to tell
     * "unreadable" from "absent" — an absent album gets re-uploaded, and with UUID keys nothing
     * collides to stop the duplicate.
     */
    @Test
    fun aShardFromANewerSchemaIsReportedUnreadableAndTheRestStillSync() = runTest {
        val fixture = fixture("future")
        val scratch = temporaryDirectory("future-source")
        val future = futureShard(scratch)
        fixture.zone.put(
            future.info.id.shardKey,
            Path(scratch, "${future.info.id}.db").readBytes(),
            "f1",
        )
        fixture.upload(album("Readable", photos = listOf(photo("a.jpg"))), "r1")

        val report = fixture.sync.sync()

        // Probed, not merely skipped: §3's two permanently stable columns say which folder the
        // album claims, which is what lets the CLI leave that folder alone instead of uploading
        // it a second time.
        assertEquals(listOf(future.info.id), report.unreadableShards.map(ShardProbe::albumId))
        assertEquals("From The Future", report.unreadableShards.first().sourcePath)
        assertEquals(SHARD_SCHEMA_VERSION + 1, report.unreadableShards.first().schemaVersion)
        assertEquals(1, report.albums) // the readable one still landed
        assertFalse(future.info.id in report.deletedAlbums)
        assertTrue(report.hasAnomalies)
    }

    @Test
    fun anOrphanedAlbumIsReportedAndStillReachable() = runTest {
        val fixture = fixture("orphan")
        val shards = containerTree("Immling", listOf("2003"))
        fixture.upload(shards[1], "c1") // the child only

        val report = fixture.sync.sync()

        assertEquals(listOf(shards[1].info.id), report.orphanedAlbums)
        assertEquals(listOf("2003"), fixture.reader().albums(under = null).map(Album::name))
    }

    @Test
    fun duplicateAlbumNamesAreReported() = runTest {
        val fixture = fixture("duplicates")
        fixture.upload(album("Sommer"), "s1")
        fixture.upload(album("Sommer"), "s2")

        val report = fixture.sync.sync()

        assertEquals(listOf("Sommer"), report.duplicateNames)
        assertEquals(2, report.albums)
    }

    // ---------------------------------------------------------------------------- local state

    /** §4: the merged DB is derived, so losing it must cost nothing but a replay. */
    @Test
    fun theMergedDbCanBeDeletedAndRebuiltWithNoNetwork() = runTest {
        val fixture = fixture("rebuild")
        containerTree("Rauhöd", listOf("2019", "2020"))
            .forEachIndexed { index, shard -> fixture.upload(shard, "e$index") }

        fixture.sync.sync()
        val listsAfterSync = fixture.zone.listCount

        fixture.sync.close()
        SystemFileSystem.delete(Path(fixture.cacheRoot, "merged.db"))
        val reopened = CatalogSync(zoneClient(fixture.zone.engine), fixture.cacheRoot, testDrivers)
        val report = reopened.rebuildFromDisk()

        assertEquals(3, report.albums)
        assertEquals(6, report.photos)
        assertEquals(listsAfterSync, fixture.zone.listCount) // nothing went over the wire
    }

    /** Deleting `sync_state.db` alone is the free repair path §4 describes. */
    @Test
    fun losingSyncStateForcesAFullRefetch() = runTest {
        val fixture = fixture("lost-state")
        containerTree("A", listOf("x", "y"))
            .forEachIndexed { index, shard -> fixture.upload(shard, "e$index") }

        fixture.sync.sync()
        fixture.sync.close()
        SystemFileSystem.delete(Path(fixture.cacheRoot, "sync_state.db"))

        val second = CatalogSync(zoneClient(fixture.zone.engine), fixture.cacheRoot, testDrivers)
        val report = second.sync()

        assertEquals(3, report.fetchedShards.size)
        assertEquals(3, report.albums)
    }


    // ------------------------------------------------------------------------------ writing back

    /**
     * §2's guarded single-owner rule: 412 means someone else wrote it. It is returned, not
     * retried — a blind retry would overwrite the other device's work.
     */
    @Test
    fun aStaleETagComesBackAsStaleETagRatherThanAnOverwrite() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.PreconditionFailed) }
        val sync = CatalogSync(
            S3Client(storage = testStorage, secretAccessKey = TEST_SECRET, http = HttpClient(engine)),
            temporaryDirectory("stale"),
            testDrivers,
        )

        val result = sync.writeShard(album("Sommer"), ifMatch = ETag("old"))

        assertEquals(ShardWriteResult.StaleETag, result)
    }

    /** A rejected write must leave the local copy as it was — the scratch file is not the shard. */
    @Test
    fun aRejectedWriteLeavesNoShardBehind() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.PreconditionFailed) }
        val cacheRoot = temporaryDirectory("stale-clean")
        val sync = CatalogSync(
            S3Client(storage = testStorage, secretAccessKey = TEST_SECRET, http = HttpClient(engine)),
            cacheRoot,
            testDrivers,
        )
        val shard = album("Sommer")

        sync.writeShard(shard, ifMatch = ETag("old"))

        assertFalse(SystemFileSystem.exists(sync.shardPath(shard.info.id)))
        assertTrue(SystemFileSystem.list(Path(cacheRoot, "shards")).isEmpty())
    }

    /** The zone's copy is what the local cache and the ETag then agree with. */
    @Test
    fun anAcceptedWriteRecordsTheETagAndKeepsTheShard() = runTest {
        val fixture = fixture("write")
        val shard = album("Sommer", photos = listOf(photo("a.jpg")))

        val result = fixture.sync.writeShard(shard, ifMatch = null)

        assertTrue(result is ShardWriteResult.Written)
        assertTrue(SystemFileSystem.exists(fixture.sync.shardPath(shard.info.id)))
        assertEquals(shard.info.name, fixture.sync.shardPath(shard.info.id).readShard(testDrivers).info.name)
    }

    @Test
    fun deletingAShardRemovesItFromTheZoneAndTheCache() = runTest {
        val fixture = fixture("delete")
        val shard = album("Sommer", photos = listOf(photo("a.jpg")))
        fixture.upload(shard, "s1")
        fixture.sync.sync()

        fixture.sync.deleteShard(shard.info.id)

        assertFalse(SystemFileSystem.exists(fixture.sync.shardPath(shard.info.id)))
        assertEquals(null, fixture.sync.etag(of = shard.info.id))
        assertTrue(fixture.sync.plan().changed.isEmpty())
    }

    @Test
    fun theRecordedETagIsWhatAnIfMatchWouldCarry() = runTest {
        val fixture = fixture("etag")
        val shard = album("Sommer")
        fixture.upload(shard, "s1")

        fixture.sync.sync()

        assertEquals(ETag("s1"), fixture.sync.etag(of = shard.info.id))
        assertEquals(null, fixture.sync.etag(of = Uuid.random()))
    }
}
