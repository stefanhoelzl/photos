package net.stho.photos.ingest

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.catalog.FakeZone
import net.stho.photos.catalog.deleteTemporaryDirectories
import net.stho.photos.catalog.temporaryDirectory
import net.stho.photos.catalog.testDrivers
import net.stho.photos.catalog.zoneClient
import net.stho.photos.pipeline.Derivatives
import net.stho.photos.pipeline.MediaClassifier
import net.stho.photos.pipeline.MediaItem
import net.stho.photos.pipeline.PipelineEvent
import net.stho.photos.ports.Pipeline

/**
 * Where a run stages derivatives, and what happens to what it staged.
 *
 * The `finally` in [Ingest.run] was never the whole story: `SIGKILL` skips it, and a run staging
 * into `/tmp` — tmpfs on a normal desktop — was staging into memory, so the kill that stranded a
 * work directory was often the out-of-memory kill that staging there had caused. These tests are
 * about the half a `finally` cannot do.
 */
class WorkRootTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()

    /**
     * One library, one zone, one cache — the same shape [IngestCycleTest] uses, with the pieces a
     * test about staging needs to reach: the config, and the run as a job it can cancel.
     */
    private class Fixture(label: String) {
        val library = LibraryFixture(label)
        val zone = FakeZone()
        val cacheRoot: Path = temporaryDirectory("workroot-$label-cache")

        val config = IngestConfig(
            libraryRoot = library.root,
            cacheRoot = cacheRoot,
            jobs = 2,
        )

        val events: MutableList<IngestEvent> = mutableListOf()

        /** Whatever a killed run would have left in the work directory. */
        fun strand(name: String, bytes: Int) {
            SystemFileSystem.createDirectories(config.workRoot)
            write(Path(config.workRoot, name), ByteArray(bytes) { 0x41 })
        }

        val workRootExists: Boolean
            get() = SystemFileSystem.metadataOrNull(config.workRoot) != null

        suspend fun run(dryRun: Boolean = false): IngestReport = using(dryRun) { it.run() }

        /**
         * Runs [body] against a wired [Ingest], collecting its events.
         *
         * The collector is started and awaited before [body] because the flow has no replay worth
         * relying on: the reclaim is the *first* thing a run emits, so a collector subscribing
         * afterwards is a collector that misses the only event these tests are about.
         */
        suspend fun <T> using(dryRun: Boolean = false, body: suspend (Ingest) -> T): T {
            val config = if (dryRun) copyForDryRun() else config
            return CatalogSync(zoneClient(zone.engine), cacheRoot, testDrivers).use { catalog ->
                Ingest(
                    config = config,
                    s3 = zoneClient(zone.engine),
                    catalog = catalog,
                    pipeline = StagingPipeline(FakePipeline(randomIds, config.workRoot), config.workRoot),
                    classifier = MediaClassifier(FakeProbe(emptyMap()), FakeBackend(emptyMap())),
                    ids = randomIds,
                    drivers = testDrivers,
                ).use { ingest ->
                    coroutineScope {
                        val watcher = launch { ingest.events.collect(events::add) }
                        try {
                            body(ingest)
                        } finally {
                            watcher.cancel()
                        }
                    }
                }
            }
        }

        private fun copyForDryRun() = IngestConfig(
            libraryRoot = library.root,
            cacheRoot = cacheRoot,
            jobs = 2,
            dryRun = true,
        )

        inline fun <reified T : IngestEvent> event(): T? = events.filterIsInstance<T>().firstOrNull()
    }

    private fun fixture(label: String): Fixture = Fixture(label).also {
        it.library.file("Rauhöd/a.jpg")
        it.library.file("Neuseeland/c.jpg")
    }

    // ------------------------------------------------------------------------------ the reclaim

    /**
     * The bug, stated as a test: what a killed run left behind is gone by the time the next one
     * has finished, without anyone having reasoned about how old it is or who wrote it.
     */
    @Test
    fun stagingLeftByAKilledRunIsRemovedByTheNextRun() = runTest {
        val fixture = fixture("stranded")
        fixture.strand("orphan.mp4", bytes = 2048)

        fixture.run()

        assertFalse(fixture.workRootExists, "the work directory should not outlive a run")
    }

    /**
     * The reclaim is the one thing in the journal that says a previous run did not finish, so it
     * has to be said rather than done quietly.
     */
    @Test
    fun reclaimingSaysWhatItFound() = runTest {
        val fixture = fixture("reclaim-reported")
        fixture.strand("orphan.mp4", bytes = 2048)
        fixture.strand("pack-1.db", bytes = 512)

        fixture.run()

        val reclaimed = fixture.event<IngestEvent.Reclaimed>()
        assertEquals(2, reclaimed?.files)
        assertEquals(2048L + 512L, reclaimed?.bytes)
    }

    /** A run that stopped cleanly emptied its own work directory, so there is nothing to report. */
    @Test
    fun aRunThatFindsNothingSaysNothing() = runTest {
        val fixture = fixture("reclaim-silent")

        fixture.run()

        assertNull(fixture.event<IngestEvent.Reclaimed>())
    }

    /**
     * `--dry-run` reclaims too. "Changes nothing" was always a promise about the zone and the
     * library: the catalog refresh a dry run performs already writes shards into this same cache
     * directory.
     */
    @Test
    fun aDryRunReclaimsAsWell() = runTest {
        val fixture = fixture("reclaim-dry")
        fixture.strand("orphan.mp4", bytes = 4096)

        val report = fixture.run(dryRun = true)

        assertTrue(report.dryRun)
        assertFalse(fixture.workRootExists)
        assertEquals(4096L, fixture.event<IngestEvent.Reclaimed>()?.bytes)
    }

    /** Reclaiming happens before the marker guard, so even a run that refuses to start does it. */
    @Test
    fun aRunThatRefusesToStartStillReclaims() = runTest {
        val fixture = fixture("reclaim-refused")
        fixture.strand("orphan.mp4", bytes = 128)
        SystemFileSystem.delete(Path(fixture.library.root, ".photosignore"))

        runCatching { fixture.run() }

        assertFalse(fixture.workRootExists)
    }

    // ------------------------------------------------------------------------- being interrupted

    /**
     * What `^C` and `systemctl stop` come down to once the CLI has turned them into a
     * cancellation: the `finally` still runs, so staging does not wait for the next run.
     *
     * Nothing in the domain knows what a signal is — cancelling the scope is the whole contract
     * between the two, which is why this can be tested without a port or a fake for it.
     */
    @Test
    fun aCancelledRunLeavesNoStaging() = runTest {
        val fixture = fixture("cancelled")

        fixture.using { ingest ->
            coroutineScope {
                val running = launch { runCatching { ingest.run() } }
                // The first album has committed, so there is a work directory with staging in it.
                ingest.events.first { it is IngestEvent.Line }
                running.cancelAndJoin()
            }
        }

        assertFalse(fixture.workRootExists, "cancelling must still empty the work directory")
    }
}

/**
 * [FakePipeline], plus a file it leaves in the work directory and never removes.
 *
 * Ingest deletes each transcode the moment its upload returns, so a fake that only produced those
 * would leave an empty directory to assert on and prove nothing about staged *bytes* surviving a
 * cancellation.
 */
private class StagingPipeline(
    private val delegate: Pipeline,
    private val workRoot: Path,
) : Pipeline {

    override val events: kotlinx.coroutines.flow.SharedFlow<PipelineEvent> = delegate.events

    override fun derive(item: MediaItem): Derivatives {
        SystemFileSystem.createDirectories(workRoot)
        write(Path(workRoot, "staged-${Uuid.random()}.tmp"), ByteArray(1024))
        return delegate.derive(item)
    }
}
