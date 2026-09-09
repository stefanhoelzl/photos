@file:OptIn(ExperimentalUuidApi::class)

package net.stho.photos.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import net.stho.photos.adapter.linux.CImagingBackend
import net.stho.photos.adapter.linux.CImagingPipeline
import net.stho.photos.adapter.linux.CImagingProbe
import net.stho.photos.adapter.linux.DbusKeyring
import net.stho.photos.adapter.linux.FlockRunLock
import net.stho.photos.adapter.linux.XdgPaths
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.ingest.Credentials
import net.stho.photos.ingest.ExitCode
import net.stho.photos.ingest.Ingest
import net.stho.photos.ingest.IngestConfig
import net.stho.photos.pipeline.MediaClassifier
import net.stho.photos.ports.Ids
import net.stho.photos.ports.LockAttempt
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.StorageUrl
import net.stho.photos.storage.retryStorageFailures

/**
 * The one verb (§7), and the only place anything is constructed.
 *
 * Wiring is by hand, in one function, with no container: with seven ports the graph is small enough
 * to read in one place, and a missing edge should fail at compile time rather than at start. Which
 * adapter satisfies which port is decided here and nowhere else — nothing under `:domain` knows it
 * is on Linux.
 */
internal class SyncCommand(private val console: Console = Console()) : CoreCliktCommand("sync") {

    override fun help(context: Context): String = "Reconcile the zone with the library."

    private val libraryPath by option(
        "--library-path",
        help = "The library root. Defaults to the working directory.",
    )

    private val endpoint by option(
        "--endpoint",
        help = "Storage URL, e.g. https://de-s3.storage.bunnycdn.com/my-photos. " +
            "Defaults to the keyring.",
    )

    private val cacheDir by option(
        "--cache-dir",
        help = "Where shards and ETags are cached. Defaults to \$XDG_CACHE_HOME/photos-cli.",
    )

    private val jobs by option("--jobs", help = "Encoder workers. Budget ~400 MB each.")
        .int()
        .default(availableProcessors())

    private val uploadJobs by option(
        "--upload-jobs",
        help = "Upload connections. One is fastest on a domestic link; more measured slower.",
    ).int().default(1)

    private val albumFilter by option(
        "--album",
        help = "Only albums whose path contains this. Scopes deletions and pulls too.",
    )

    private val dryRun by option("--dry-run", help = "Print the plan and change nothing.").flag()

    override fun run(): Unit = console.translatingFailures {
        val code = runBlocking { sync() }
        if (code != ExitCode.CLEAN) throw ProgramResult(code)
    }

    /**
     * Resolve, lock, then run — in that order, because each step is cheaper than the next and the
     * lock must be held before anything is read or written.
     */
    private suspend fun sync(): Int {
        val environment = processEnvironment()
        val paths = XdgPaths()
        val credentials = Credentials(environment, DbusKeyring())

        val libraryRoot = credentials.libraryRoot(workingDirectory(), libraryPath)
        val storage = credentials.storage(endpoint)
        val password = credentials.password()

        // Only ever true under `secrets-env` or a shell that exported them. Said out loud so a
        // stale variable outranking the keyring is visible rather than an hour of wondering why
        // the wrong zone is being written (§1).
        val fromEnvironment = Credentials.Source.ENVIRONMENT
        val injected = listOfNotNull(
            Credentials.ENDPOINT_VARIABLE.takeIf { storage.source == fromEnvironment },
            Credentials.PASSWORD_VARIABLE.takeIf { password.source == fromEnvironment },
        )
        if (injected.isNotEmpty()) {
            console.error(
                "using ${injected.joinToString(" and ")} from the environment (development)",
            )
        }

        val ids = Ids { Uuid.random() }
        val config = IngestConfig(
            libraryRoot = libraryRoot,
            cacheRoot = cacheDir?.let(::Path) ?: Path(paths.cacheRoot),
            workRoot = IngestConfig.newWorkRoot(temporaryDirectory(), ids),
            jobs = jobs,
            uploadJobs = uploadJobs,
            albumFilter = albumFilter,
            dryRun = dryRun,
        )

        // Taken before anything is read or written, and held by the process until it exits however
        // it exits — `flock` is advisory and process-scoped, so `kill -9` releases it too.
        val handle = when (val attempt = FlockRunLock(config.cacheRoot.toString()).acquire()) {
            is LockAttempt.Acquired -> attempt.handle
            // Not a failure: the sync is happening, just not this one. Exit 75 so the hourly unit
            // overlapping a long manual run stays out of `OnFailure=`.
            is LockAttempt.HeldBy -> {
                console.line("deferred: ${attempt.message()}")
                return ExitCode.DEFERRED
            }
        }

        return handle.use { ingesting(config, storage.value, password.value, ids) }
    }

    /**
     * The graph itself: seven ports, one adapter each, and nothing else in the process that knows
     * both halves.
     *
     * [CImagingProbe] is shared between the classifier and the backend deliberately — they ask the
     * same C shim different questions, and two instances would be two of the same thing.
     */
    private suspend fun ingesting(
        config: IngestConfig,
        storage: StorageUrl,
        password: String,
        ids: Ids,
    ): Int = coroutineScope {
        val reporter = ConsoleReporter(console)
        val probe = CImagingProbe()
        val backend = CImagingBackend(probe)

        // Ktor is the transport abstraction, not a port (§7); the retry policy is installed here so
        // the whole of it is visible where the client is built.
        val http = HttpClient(Curl) { retryStorageFailures() }
        try {
            val s3 = S3Client(storage = storage, secretAccessKey = password, http = http)
            CatalogSync(s3, config.cacheRoot).use { catalog ->
                Ingest(
                    config = config,
                    s3 = s3,
                    catalog = catalog,
                    pipeline = CImagingPipeline(config.workRoot.toString(), backend, ids = ids),
                    classifier = MediaClassifier(probe, backend),
                    ids = ids,
                ).use { ingest ->
                    // Written as it happens, never buffered to the end: a 39-hour run has to be
                    // watchable, and legible in the journal while it is still going. The flow has
                    // no replay, so the run must not start until the collector is on it —
                    // otherwise the plan, which is the first thing said, is the one thing lost.
                    val subscribed = CompletableDeferred<Unit>()
                    val watcher = launch {
                        ingest.events
                            .onSubscription { subscribed.complete(Unit) }
                            .collect(reporter::show)
                    }
                    subscribed.await()

                    try {
                        val report = ingest.run()
                        console.clearProgress()
                        reporter.render(report)
                        if (report.hasProblems) ExitCode.COMPLETED_WITH_FAILURES else ExitCode.CLEAN
                    } finally {
                        watcher.cancel()
                    }
                }
            }
        } finally {
            http.close()
        }
    }
}
