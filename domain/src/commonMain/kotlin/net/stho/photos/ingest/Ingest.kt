package net.stho.photos.ingest

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.IgnoreRulesUnreadableFailure
import net.stho.photos.IngestAbort
import net.stho.photos.catalog.AlbumInfo
import net.stho.photos.catalog.AlbumState
import net.stho.photos.derivative.DerivativeSpec
import net.stho.photos.catalog.BLOB_PREFIX
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.catalog.SHARD_SCHEMA_VERSION
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.ShardProbe
import net.stho.photos.catalog.ShardWriteResult
import net.stho.photos.catalog.ThumbPack
import net.stho.photos.catalog.asBlobObjectId
import net.stho.photos.catalog.ObjectId
import net.stho.photos.catalog.blobKey
import net.stho.photos.catalog.packThumbnails
import net.stho.photos.library.IgnoreRule
import net.stho.photos.library.IgnoreRules
import net.stho.photos.library.LibraryWalker
import net.stho.photos.library.readIgnoreRules
import net.stho.photos.model.PhotoRow
import net.stho.photos.pipeline.MediaClassifier
import net.stho.photos.pipeline.MediaItem
import net.stho.photos.pipeline.SkippedFile
import net.stho.photos.pipeline.withExtension
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.ports.Ids
import net.stho.photos.ports.Pipeline
import net.stho.photos.storage.Body
import net.stho.photos.storage.ETag
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.byteCount
import net.stho.photos.storage.list

/**
 * One run of `photos-cli sync`.
 *
 * The shape §7 asks for: walk the library, refresh the catalog, reconcile, then act — uploads
 * first, the shard last, always. The shard is the commit point, so a crash costs at most the album
 * in flight and the next run's LIST sees it as absent and redoes it. Nothing here keeps state
 * between runs beyond the shard cache, which is why it is self-correcting.
 *
 * Everything it works through is injected. The catalog, the pipeline and the classifier arrive
 * built rather than being constructed here from an `ImageBackend`, because the imaging stack
 * lives entirely behind ports (§7): the wiring is the CLI's one function, and this class does not
 * know which platform it is on.
 */
public class Ingest(
    private val config: IngestConfig,
    private val s3: S3Client,
    private val catalog: CatalogSync,
    private val pipeline: Pipeline,
    private val classifier: MediaClassifier,
    private val ids: Ids,
    private val drivers: SqlDrivers,
    private val clock: Clock = Clock.System,
) : AutoCloseable {

    /**
     * Real threads for the encoders, and the one piece of `DerivePool` that had to survive.
     *
     * `Pipeline.derive` blocks for 2.7 s per photo (§7) — a synchronous C shim, not a suspending
     * call — and §9's measurements say that encode parallelism is the whole point while upload
     * parallelism buys nothing. Blocking `Dispatchers.Default` would take one of its
     * one-thread-per-core slots for each of those seconds, and every continuation in the run,
     * the uploads included, queues behind them.
     *
     * Kotlin/Native has no public `Dispatchers.IO`, so the pool sized for blocking work is one we
     * ask for: `jobs` threads, which is also the number of encodes the semaphore lets through, so
     * neither side is the binding constraint by accident.
     *
     * Lazy because §7 promises a no-op run is one LIST, and spawning sixteen threads an hour to
     * discover there is nothing to encode would be a poor way to keep that promise.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private val encoders = lazy { newFixedThreadPoolContext(config.jobs, "photos-derive") }

    private val uploadPermits = Semaphore(config.uploadJobs)
    private val meter = ProgressMeter(clock)

    // replay = 1 because the *plan* is the first thing emitted: a collector that subscribes
    // after `run()` has started would otherwise miss "to do: …" and never know it, which is a
    // trap laid for every future caller rather than a bug in any one of them.
    private val mutableEvents = MutableSharedFlow<IngestEvent>(replay = 1, extraBufferCapacity = 64)

    /**
     * Progress, for a caller that wants to show it.
     *
     * A `SharedFlow` rather than an `emit` callback the caller passes in, matching
     * [Pipeline.events]: the flow never completes, a collector ends by cancelling its own scope,
     * and a run nobody is watching costs nothing because the values are simply dropped.
     */
    public val events: SharedFlow<IngestEvent> = mutableEvents.asSharedFlow()

    /**
     * Every blob key the zone holds: listed once at the start of the run, and added to as this
     * run writes (§2).
     *
     * One listing serves two jobs. Before a PUT it answers "is this content already there?",
     * which under content addressing is decidable from the key alone — so a crashed import
     * resumes without re-sending what it finished. At the end the sweep reuses it rather than
     * taking a second listing.
     */
    private val blobsInZone = mutableMapOf<String, Long>()

    // ------------------------------------------------------------------------------- the run

    public suspend fun run(): IngestReport {
        val report = ReportBuilder()
        report.dryRun = config.dryRun

        // Before anything else, including the marker guard: whatever is in the work directory
        // belongs to a run that is over, and reclaiming it is the one cleanup a `finally` cannot
        // do. A dry run reclaims too — `catalog.refresh()` below already writes shards into the
        // cache, so "changes nothing" was always a promise about the zone and the library.
        reclaim()

        // The one structural guard. `.photosignore` is the marker that says this directory is a
        // library root, so an unmounted disk and a mistyped root both fail here rather than
        // looking like a library whose every album was deleted (§7).
        val rules = loadRules()

        val contents = LibraryWalker(config.libraryRoot, rules).walk()
        report.unusedRules = contents.unusedRules
        report.ignoredFiles = contents.ignoredFiles.size

        val refreshed = catalog.refresh()
        report.listedShards = refreshed.shards.size + refreshed.report.unreadableShards.size
        report.fetchedShards = refreshed.report.fetchedShards.size
        report.duplicateNames = refreshed.report.duplicateNames
        report.orphanedAlbums = refreshed.report.orphanedAlbums

        val plan = Reconciler(config.libraryRoot, ids, config.albumFilter)
            .plan(contents, refreshed.shards, refreshed.report.unreadableShards)

        // §7 asserts images never change on disk, and checks the assertion. A mismatch means the
        // library broke its contract, so nothing at all is written this run.
        if (plan.mismatches.isNotEmpty()) throw IngestAbort.FileChanged(plan.mismatches)

        report.blockedByUnreadable = plan.blockedByUnreadable
        report.looseRootFiles = plan.looseRootFiles.size
        for (album in plan.albums) {
            if (album.mixedFileCount > 0) {
                report.mixedFolders += IngestReport.MixedFolder(album.sourcePath, album.mixedFileCount)
            }
        }

        if (config.dryRun) {
            fillDryRun(report, plan)
            sweep(report, refreshed.shards, refreshed.report.unreadableShards, dryRun = true)
            return report.build()
        }

        // §7 promises a no-op run is one LIST. It stays true only if a run with nothing to do also
        // skips the sweep — and it can: debris appears when something writes, so a run where
        // neither the zone nor the library moved cannot have produced any. The phone's crash
        // debris waits for the next run that does something, which costs nothing but a week of
        // $0.01/GB.
        if (!refreshed.changed && !plan.hasWork) return report.build()

        // Said before anything is uploaded, because the first album's line cannot appear until
        // that album is derived *and* uploaded — minutes, on a link that manages 0.85 MB/s. A
        // 39-hour run that says nothing for its first ten minutes is indistinguishable from one
        // that has wedged.
        val work = plan.albums.filter(AlbumPlan::needsWrite)
        val files = work.sumOf { it.uploads.size }
        // What will be *sent*, not what will be read. Since §5 the two differ by roughly 7×:
        // a 3 MB photograph becomes a ~424 KB viewing image. Promising the disk figure would
        // over-state the upload, and — because the meter's numerator counts bytes actually
        // produced — would leave the bar stuck near a seventh of the way across and the estimate
        // seven times too long. The derived size is unknowable before deriving, so this is an
        // estimate and every line that shows it says `~`.
        val sourceBytes = work.sumOf { album -> album.uploads.sumOf { Body.File(it).byteCount ?: 0L } }
        val bytes = (sourceBytes * DerivativeSpec.ESTIMATED_UPLOAD_RATIO).toLong()
        emit(
            IngestEvent.Planned(
                albums = work.size, files = files, bytes = bytes,
                deletions = plan.deletions.size, pulls = plan.pulls.size,
            ),
        )
        meter.start(files, bytes)

        // Listed here rather than at the top of the run: §7 promises a run that changes nothing
        // costs exactly one request, and the early return above is what keeps that true. A run
        // with no work has nothing to skip-upload and nothing to sweep, so it needs no listing.
        s3.list(prefix = BLOB_PREFIX).collect { listed ->
            if (!listed.isDirectoryMarker) blobsInZone[listed.key] = listed.size
        }
        report.blobsInZone = blobsInZone.size

        SystemFileSystem.createDirectories(config.workRoot)
        try {
            for (album in work) commit(album, refreshed.etags, report)
            for (deletion in plan.deletions) delete(deletion, report)
            for (pull in plan.pulls) archive(pull, refreshed.etags, report)

            // The sweep reads every shard now on disk, so it must run after the writes above.
            val after = catalog.refresh()
            sweep(report, after.shards, after.report.unreadableShards, dryRun = false)
        } finally {
            config.workRoot.deleteRecursively()
        }

        return report.build()
    }

    /**
     * Reads `$LIBRARY_ROOT/.photosignore`, and refuses the run without it.
     *
     * §7 used to say a missing file means no exclusions, silently. It cannot: the file is also
     * what proves the directory is the library, and this run deletes albums whose directory is
     * gone. A library that genuinely wants no exclusions writes an empty file.
     *
     * `readIgnoreRules` itself deliberately reads an absent file as *no exclusions* — that is
     * right for a reader that does not delete. The stricter rule belongs here, at the one caller
     * that does.
     */
    private fun loadRules(): IgnoreRules {
        val marker = Path(config.libraryRoot, IgnoreRules.FILENAME)
        if (SystemFileSystem.metadataOrNull(marker) == null) {
            throw IngestAbort.NotALibraryRoot(
                config.libraryRoot.toString(),
                "no ${IgnoreRules.FILENAME}",
            )
        }
        return try {
            config.libraryRoot.readIgnoreRules()
        } catch (unreadable: IgnoreRulesUnreadableFailure) {
            throw IngestAbort.NotALibraryRoot(config.libraryRoot.toString(), unreadable.message)
        }
    }

    private fun fillDryRun(report: ReportBuilder, plan: IngestPlan) {
        for (album in plan.albums) {
            if (!album.needsWrite) continue
            report.albums += IngestReport.AlbumOutcome(
                path = album.sourcePath,
                uploaded = album.uploads.size,
                dropped = album.drop.size,
                bytes = album.uploads.sumOf { Body.File(it).byteCount ?: 0L },
                created = album.isNew,
                duration = Duration.ZERO,
            )
        }
        for (deletion in plan.deletions) {
            report.deletedAlbums +=
                IngestReport.DeletedAlbum(deletion.sourcePath, deletion.shard.photos.size)
        }
        for (pull in plan.pulls) {
            report.pulledAlbums +=
                IngestReport.PulledAlbum(pull.sourcePath, pull.shard.photos.size, 0)
        }
    }

    // -------------------------------------------------------------------------------- one album

    private suspend fun commit(
        album: AlbumPlan,
        etags: Map<Uuid, ETag>,
        report: ReportBuilder,
    ) {
        val started = clock.now()

        // The whole album, not just the new files: pairing is a property of the set. A Live
        // Photo's MOV carries no row, so classifying the unclaimed files alone would present it
        // as an ordinary video and transcode it all over again.
        val claimed = album.files.mapTo(mutableSetOf(), Path::name) -
            album.uploads.mapTo(mutableSetOf(), Path::name)
        val classified = classifier.classify(album.files.map(Path::toString))
        for (skipped in classified.skipped) {
            if (skipped.reason != SkippedFile.Reason.UnrecognisedFormat) continue
            if (skipped.path.substringAfterLast('/') in claimed) continue
            report.strays += IngestReport.Failure(relative(skipped.path), "unrecognised format")
        }

        // §3: two rows in one album may not claim the same name. It can only happen when a
        // derivative renames its source onto a sibling — an `a.CR2` beside an `a.jpg`.
        val planned = album.keep.mapTo(mutableSetOf(), PhotoRow::filename)
        val items = mutableListOf<MediaItem>()
        for (item in classified.items) {
            if (item.filename in claimed) continue
            val name = predictedName(item)
            if (!planned.add(name)) {
                report.failures += IngestReport.Failure(
                    relative(item.path),
                    "would claim the name $name, which another photo in this album already has",
                )
                continue
            }
            items += item
        }

        val produced = try {
            derive(items, album, report)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report.failures += IngestReport.Failure(album.sourcePath, failure.describe())
            return
        }

        // §3: `photo.id` is row identity and must survive re-encoding — it is what
        // `cover_photo_id` points at and what the thumbnail pack keys by. Re-deriving an album
        // produces fresh rows, so the identity has to be carried across explicitly, matched by
        // the file each row came from. Without this a profile bump would silently clear every
        // custom cover in the library.
        val carried = if (!album.reencoding) produced else {
            val previous = album.drop.associateBy(PhotoRow::diskFilename)
            produced.map { made ->
                val before = previous[made.row.diskFilename] ?: return@map made
                Produced(made.row.copy(id = before.id), made.thumbnail, made.uploadedBytes)
            }
        }

        try {
            val rows = album.keep + carried.map(Produced::row)
            val thumbsId = packThumbnails(album, carried, rows, report)
            val shard = Shard(albumInfo(album, thumbsId, rows), rows)

            val etag = if (album.isNew) null else etags[album.id]
            when (catalog.writeShard(shard, ifMatch = etag)) {
                is ShardWriteResult.Written -> Unit
                // §2: someone else wrote it. Re-read, re-decide against what actually landed, and
                // try once more — a blind retry would overwrite their work.
                ShardWriteResult.StaleETag -> if (!rewriteAfterConflict(album, carried)) {
                    report.contendedAlbums += album.sourcePath
                    return
                }
            }

            // Nothing is deleted here. Under content addressing a blob can have more than one
            // referent, so "this album stopped pointing at it" is not "nobody points at it" —
            // and answering that per album would mean re-reading every shard per album. The
            // sweep at the end of this run already computes the referenced set once, across
            // every shard, and no longer waits on an age floor to act (§2), so it collects
            // these in the same run at a fraction of the cost.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report.failures += IngestReport.Failure(album.sourcePath, failure.describe())
            return
        }

        val outcome = IngestReport.AlbumOutcome(
            path = album.sourcePath,
            uploaded = produced.size,
            dropped = album.drop.size,
            bytes = produced.sumOf(Produced::uploadedBytes),
            created = album.isNew,
            duration = clock.now() - started,
        )
        report.albums += outcome
        emit(IngestEvent.Line(outcome.asLine()))
    }

    /**
     * What a derived row will be called, so a name collision is caught before the CPU is spent
     * rather than after.
     */
    private fun predictedName(item: MediaItem): String = when (item.kind) {
        MediaItem.Kind.Raw -> item.filename.withExtension("jpg")
        MediaItem.Kind.Video -> item.filename.withExtension("mp4")
        MediaItem.Kind.Still, is MediaItem.Kind.LivePhoto -> item.filename
    }

    private class Produced(
        val row: PhotoRow,
        val thumbnail: ByteArray,
        val uploadedBytes: Long,
    )

    /**
     * Derives and uploads every item, [IngestConfig.jobs] in flight.
     *
     * The window *is* the backpressure: a worker holds its permit across both the encode and the
     * upload, so it cannot start a new encode until its own upload has finished and at most `jobs`
     * derivatives are ever staged — a few megabytes, rather than the 100 GB an unbounded producer
     * would put on disk (§7).
     */
    private suspend fun derive(
        items: List<MediaItem>,
        album: AlbumPlan,
        report: ReportBuilder,
    ): List<Produced> {
        if (items.isEmpty()) return emptyList()
        val permits = Semaphore(config.jobs)

        val outcomes = coroutineScope {
            items.map { item ->
                async {
                    permits.withPermit {
                        val outcome = try {
                            Result.success(process(item, report))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                        // Transient, and only where a person is watching: the journal gets the
                        // per-album lines and nothing else.
                        val uploaded = outcome.getOrNull()?.uploadedBytes ?: 0L
                        meter.finished(uploaded, album.sourcePath)
                            ?.let { emit(IngestEvent.Status(it)) }
                        item to outcome
                    }
                }
            }.awaitAll()
        }

        val produced = mutableListOf<Produced>()
        for ((item, outcome) in outcomes) {
            outcome.fold(
                onSuccess = { produced += it },
                onFailure = {
                    report.failures += IngestReport.Failure(relative(item.path), it.describe())
                },
            )
        }
        // Deterministic, so two runs over the same album write the same shard.
        produced.sortBy { it.row.filename }
        return produced
    }

    /**
     * Derive one item, then upload every blob it owns. Blobs before the shard, always.
     *
     * Each id is the SHA-256 of the bytes it names (§2), so an upload of content the zone
     * already holds is skipped rather than repeated under a fresh name — which is what makes a
     * crashed run resumable and a profile bump send no thumbnail packs.
     */
    private suspend fun process(item: MediaItem, report: ReportBuilder): Produced {
        val derived = withContext(encoders.value) { pipeline.derive(item) }
        var row = derived.row
        var bytes = 0L

        derived.video?.let { video ->
            val path = Path(video)
            val id = ObjectId.ofContent(path)
            bytes += upload(id, Body.File(path), report)
            row = row.copy(videoId = id)
            path.deleteQuietly()
        }
        derived.liveVideo?.let { liveVideo ->
            val path = Path(liveVideo)
            val id = ObjectId.ofContent(path)
            bytes += upload(id, Body.File(path), report)
            row = row.copy(liveVideoId = id)
        }
        // The one untouched original left in the zone: a Live Photo's still, whose
        // `content.identifier` has to survive to pair with the MOV above (§5).
        derived.liveStill?.let { liveStill ->
            val path = Path(liveStill)
            val id = ObjectId.ofContent(path)
            bytes += upload(id, Body.File(path), report)
            row = row.copy(liveStillId = id)
        }

        val imageId = ObjectId.ofContent(derived.image)
        bytes += upload(imageId, Body.Bytes(derived.image), report)
        row = row.copy(imageId = imageId)

        return Produced(row, derived.thumbnail, bytes)
    }

    /**
     * Write this blob unless the zone already holds it.
     *
     * Under content addressing "already holds it" is decidable from the key alone: the same key
     * means the same bytes. The listing is taken once at the start of the run (§2), so this
     * costs nothing per object — and a run that died halfway through an import re-derives
     * everything but re-uploads only what never landed.
     */
    private suspend fun upload(id: ObjectId, body: Body, report: ReportBuilder): Long {
        val key = id.blobKey
        val size = body.byteCount ?: 0L
        if (key in blobsInZone) {
            report.skippedUploads++
            report.skippedBytes += size
            // Returned as work done even though nothing was sent. The meter measures progress
            // through the run, and deriving a photograph the zone already holds is progress —
            // a resumed import would otherwise sit at zero while finishing correctly.
            return size
        }
        uploadPermits.withPermit { s3.put(key, body) }
        blobsInZone[key] = size
        return size
    }

    // ------------------------------------------------------------------------------ thumbnails

    /**
     * The album's pack, rebuilt; the id it now has, or null when the album holds nothing.
     *
     * §3 stores one blob per album, so adding a photo means repacking. The thumbnails already up
     * there come back down rather than being re-derived: 20 MB at 7.5 MB/s for the largest album
     * here, against about twelve minutes of CPU to re-encode 1,754 thumbnails that were already
     * correct.
     */
    private suspend fun packThumbnails(
        album: AlbumPlan,
        produced: List<Produced>,
        rows: List<PhotoRow>,
        report: ReportBuilder,
    ): ObjectId? {
        if (rows.isEmpty()) return null
        val existingId = album.existing?.info?.thumbsId
        if (produced.isEmpty() && album.drop.isEmpty()) return existingId

        val thumbnails = mutableMapOf<Uuid, ByteArray>()
        if (existingId != null && album.keep.isNotEmpty()) {
            val downloaded = Path(config.workRoot, "thumbs-$existingId.db")
            s3.download(existingId.blobKey, downloaded)
            val kept = album.keep.mapTo(mutableSetOf(), PhotoRow::id)
            for ((id, jpeg) in ThumbPack(downloaded, drivers).unpack()) {
                if (id in kept) thumbnails[id] = jpeg
            }
            downloaded.deleteQuietly()
        }
        for (item in produced) thumbnails[item.row.id] = item.thumbnail
        if (thumbnails.isEmpty()) return null

        // Packed first, then named after what it contains. SQLite's output is byte-deterministic
        // for an identical sequence of inserts, and libjpeg-turbo's is too — both measured — so
        // repacking an album whose thumbnails did not change produces the same key and uploads
        // nothing. That is what makes a profile bump send no thumbnail packs at all (§5).
        val packed = Path(config.workRoot, "pack-${Uuid.random()}.db")
        thumbnails.packThumbnails(into = packed, drivers = drivers)
        val id = ObjectId.ofContent(packed)
        upload(id, Body.File(packed), report)
        packed.deleteQuietly()
        return id
    }

    private fun albumInfo(album: AlbumPlan, thumbsId: ObjectId?, rows: List<PhotoRow>): AlbumInfo {
        val existing = album.existing?.info
        return AlbumInfo(
            id = album.id,
            name = album.name,
            parent = album.parent,
            sourcePath = album.sourcePath,
            // A cover pointing at a photo that is gone would resolve to nothing; §3's default
            // (the album's earliest photo) is correct again once it is cleared.
            // Checked against the rows actually being written, not against the kept ones: a
            // re-encoded album keeps nothing and carries every identity forward instead.
            coverPhotoId = existing?.coverPhotoId
                ?.takeIf { cover -> rows.any { it.id == cover } },
            thumbsId = thumbsId,
            // §3 stores whole seconds, so a value that has been through a shard and one that has
            // not compare equal.
            addedAt = existing?.addedAt ?: Instant.fromEpochSeconds(clock.now().epochSeconds),
            schemaVersion = SHARD_SCHEMA_VERSION,
        )
    }

    /** The second attempt after a 412, against the shard that actually landed. */
    private suspend fun rewriteAfterConflict(album: AlbumPlan, produced: List<Produced>): Boolean {
        val fresh = catalog.reload(album.id)
        val mine = produced.map(Produced::row)
        val taken = mine.mapTo(mutableSetOf(), PhotoRow::filename)
        // Their rows survive; ours are added. Merge is concatenation (§2) — the only rows dropped
        // are ones whose file we just proved is gone.
        val gone = album.drop.mapTo(mutableSetOf(), PhotoRow::filename)
        val theirs = fresh.photos.filter { it.filename !in taken && it.filename !in gone }

        val info = fresh.info.copy(
            sourcePath = album.sourcePath,
            name = album.name,
            parent = album.parent,
            // What we just wrote is at the current profile, whatever the shard we collided with
            // said. Inheriting their version would leave the album permanently below it, and it
            // would be re-derived on every run from here on.
            state = AlbumState.ENCODED,
            encodingVersion = DerivativeSpec.ENCODING_VERSION,
        )
        val shard = Shard(info, theirs + mine)
        val result = catalog.writeShard(shard, ifMatch = catalog.etag(album.id))
        return result is ShardWriteResult.Written
    }

    // ------------------------------------------------------------------------ deleting an album

    private suspend fun delete(deletion: AlbumDeletion, report: ReportBuilder) {
        try {
            // Shard first: the album stops existing before its objects do, so the catalog never
            // names a blob that is gone (§2).
            catalog.deleteShard(deletion.shard.info.id)
            // The shard is gone, so its blobs are unreferenced unless another album shares
            // them — which the sweep decides, once, at the end of this run (§2).
            val photos = deletion.shard.photos.size
            report.deletedAlbums += IngestReport.DeletedAlbum(deletion.sourcePath, photos)
            emit(IngestEvent.Line("- ${deletion.sourcePath}  $photos photos deleted"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report.failures += IngestReport.Failure(deletion.sourcePath, failure.describe())
        }
    }

    /**
     * A failed delete is swallowed on purpose: the shard no longer references the object, so it is
     * now debris, and the sweep collects debris on a later run. Failing the album over an
     * object nothing points at would be worse than leaving it.
     */
    private suspend fun deleteBlobs(objectIds: List<ObjectId>) {
        for (id in objectIds) {
            try {
                s3.delete(id.blobKey)
                // Keep the set honest: it is what the zone holds, and the sweep reads it at the
                // end of the run. Leaving a deleted key in would have the sweep count it a
                // second time and delete what is already gone.
                blobsInZone -= id.blobKey
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Debris, and the sweep's business.
            }
        }
    }

    // ------------------------------------------------------------------ pulling a phone album down

    /**
     * §7's pull: claim the album, copy it into `$LIBRARY_ROOT`, and leave it for the next run
     * to encode.
     *
     * **Claim first, download second.** The claim writes `source_path` and leaves the state at
     * [AlbumState.UPLOADED], which is what makes a retry safe: the path is recorded before any
     * file exists, so a run interrupted mid-download resumes into the same directory instead of
     * choosing a fresh name beside it — and because the deletion rule is gated on
     * [AlbumState.ENCODED], the half-filled directory it leaves behind can never be read as
     * photos someone deleted.
     *
     * The album stays [AlbumState.UPLOADED] until it has been encoded. That is deliberate: the
     * phone's full-quality blobs are the only copy until the library copy is durable, so
     * nothing deletes them here. The next run sees an ordinary album below the current profile
     * and re-encodes it through the path that already exists for that.
     *
     * `.photosignore` is not consulted: the rules govern what goes up (§7).
     */
    private suspend fun archive(pull: PullPlan, etags: Map<Uuid, ETag>, report: ReportBuilder) {
        val directory = Path(config.libraryRoot, *pull.sourcePath.split('/').toTypedArray())
        val downloaded = mutableListOf<Path>()
        var bytes = 0L
        try {
            var etag = etags[pull.shard.info.id]
            if (!pull.claimed) {
                val claimed = pull.shard.info.copy(sourcePath = pull.sourcePath)
                when (val write = catalog.writeShard(Shard(claimed, pull.shard.photos), ifMatch = etag)) {
                    is ShardWriteResult.Written -> etag = write.etag
                    ShardWriteResult.StaleETag -> {
                        report.contendedAlbums += pull.sourcePath
                        return
                    }
                }
            }

            SystemFileSystem.createDirectories(directory)
            for (row in pull.shard.photos) {
                // A Live Photo keeps an untouched still precisely so its identifier survives, so
                // that is the file to archive when there is one (§5).
                val primary = row.liveStillId ?: row.imageId ?: row.videoId ?: continue
                val destination = Path(directory, row.filename)
                if (!SystemFileSystem.exists(destination)) s3.download(primary.blobKey, destination)
                downloaded += destination
                bytes += row.bytes ?: 0
                // The paired MOV has no name of its own in the catalog. `<stem>.MOV` is the
                // convention every pair in this library follows, and pairing is by content
                // identifier rather than by name, so the walker re-pairs it either way.
                row.liveVideoId?.let { liveVideoId ->
                    val path = Path(directory, row.filename.withExtension("MOV"))
                    if (!SystemFileSystem.exists(path)) s3.download(liveVideoId.blobKey, path)
                    downloaded += path
                }
            }
            report.pulledAlbums += IngestReport.PulledAlbum(pull.sourcePath, downloaded.size, bytes)
            emit(IngestEvent.Line("v ${pull.sourcePath}  ${downloaded.size} files pulled"))

            commit(
                AlbumPlan(
                    id = pull.shard.info.id,
                    name = pull.shard.info.name,
                    sourcePath = pull.sourcePath,
                    parent = pull.shard.info.parent,
                    directory = directory,
                    existing = pull.shard,
                    uploads = downloaded,
                    files = downloaded,
                    keep = emptyList(),
                    // The rows the phone wrote, and the full-quality blobs they own. Deleting
                    // them is the ordinary drop path, and it runs only now that the library
                    // holds the files they were the only copy of.
                    drop = pull.shard.photos,
                    mixedFileCount = 0,
                    reencoding = true,
                ),
                etags = etag?.let { mapOf(pull.shard.info.id to it) } ?: emptyMap(),
                report = report,
            )
            // Every file is now on disk, so the album can be derived like any other — which is
            // what finally moves it to `encoded` and deletes the phone's full-quality blobs. It
            // happens here rather than on a later run because only this code knows the download
            // completed: a run that stopped halfway leaves the state at `uploaded`, and the next
            // one resumes into the same directory and skips what is already there.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report.failures += IngestReport.Failure(pull.sourcePath, failure.describe())
        }
    }

    // ------------------------------------------------------------------------- the orphan sweep

    /**
     * Deletes blobs no shard references and that are older than the age floor, and collects
     * phone uploads that were abandoned before they finished.
     *
     * Skipped entirely when any shard is unreadable: the referenced set would then be missing
     * whatever that album owns, and the sweep would delete a readable album's photographs on the
     * strength of a shard it could not open.
     *
     * **There is no age floor on garbage.** §8's manifest names every blob an upload will write
     * before it writes any of them, so an unreferenced blob is unambiguously garbage the moment
     * it is unreferenced — there is no window in which it might belong to something in flight.
     * The floor survives for one job only: deciding when an album still [AlbumState.UPLOADING]
     * has been abandoned, where §8's presigned PUTs have provably expired. Garbage and liveness
     * stop sharing a knob.
     */
    private suspend fun sweep(
        report: ReportBuilder,
        shards: List<Shard>,
        unreadable: List<ShardProbe>,
        dryRun: Boolean,
    ) {
        if (unreadable.isNotEmpty()) {
            report.sweepSkipped = "${unreadable.size} shard(s) too new to read"
            return
        }
        if (config.albumFilter != null) {
            report.sweepSkipped = "--album restricts this run"
            return
        }

        val floor = clock.now() - config.sweepAge

        // Abandoned uploads first, so the blobs they were protecting become sweepable in the
        // same pass rather than waiting for the next run.
        val abandoned = shards.filter {
            it.info.state == AlbumState.UPLOADING && it.info.addedAt < floor
        }
        for (shard in abandoned) {
            report.abandonedUploads++
            if (dryRun) continue
            try {
                catalog.deleteShard(shard.info.id)
                deleteBlobs(shard.objectIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report.failures += IngestReport.Failure(shard.info.name, failure.describe())
            }
        }

        val live = if (dryRun) shards else shards - abandoned.toSet()
        val referenced = live.flatMapTo(mutableSetOf(), Shard::objectIds)

        // The listing taken at the start of the run, plus what this run wrote — not a second
        // LIST. A fresh one could lag behind a shard this very run committed and read its blobs
        // as unreferenced; the in-memory set cannot. Blobs a concurrent phone upload wrote are
        // named by its `uploading` shard, so they are referenced regardless of when they landed.
        for ((key, size) in blobsInZone.entries.sortedBy { it.key }) {
            val id = key.asBlobObjectId() ?: continue
            if (id in referenced) continue
            report.sweptBlobs++
            report.sweptBytes += size
            if (!dryRun) s3.delete(key)
        }
    }

    // -------------------------------------------------------------------------------- helpers

    private fun relative(path: String): String {
        val root = config.libraryRoot.toString().trimEnd('/')
        if (!path.startsWith(root)) return path
        return path.drop(root.length).dropWhile { it == '/' }
    }

    /**
     * Empties the work directory, and says so if there was anything in it.
     *
     * Safe because of the run lock, not because of anything checked here: the lock lives in the
     * cache directory this staging sits under, so a run that got it is the only run that can be
     * using it (see [IngestConfig.workRoot]). Nothing else distinguishes debris from work in
     * progress — there is no pid to read and no age that would not eventually be wrong.
     */
    private fun reclaim() {
        if (SystemFileSystem.metadataOrNull(config.workRoot) == null) return
        val debris = config.workRoot.measure()
        config.workRoot.deleteRecursively()
        if (debris.files > 0) emit(IngestEvent.Reclaimed(debris.files, debris.bytes))
    }

    private fun emit(event: IngestEvent) {
        mutableEvents.tryEmit(event)
    }

    /** Releases the encoder threads. A run that never encoded anything never made any. */
    override fun close() {
        if (encoders.isInitialized()) encoders.value.close()
    }
}

/**
 * The report, under construction.
 *
 * Returning a fresh [IngestReport] from each of a dozen private methods would make the run read
 * as plumbing. So the public type stays an immutable record, and this is the mutable accumulator
 * behind it that the steps write into.
 */
private class ReportBuilder {
    val albums = mutableListOf<IngestReport.AlbumOutcome>()
    val deletedAlbums = mutableListOf<IngestReport.DeletedAlbum>()
    val pulledAlbums = mutableListOf<IngestReport.PulledAlbum>()
    val failures = mutableListOf<IngestReport.Failure>()
    val strays = mutableListOf<IngestReport.Failure>()
    val mixedFolders = mutableListOf<IngestReport.MixedFolder>()
    val contendedAlbums = mutableListOf<String>()

    var looseRootFiles = 0
    var blockedByUnreadable: List<ShardProbe> = emptyList()
    var duplicateNames: List<String> = emptyList()
    var orphanedAlbums: List<Uuid> = emptyList()
    var blobsInZone = 0
    var skippedUploads = 0
    var skippedBytes = 0L
    var abandonedUploads = 0
    var sweptBlobs = 0
    var sweptBytes = 0L
    var sweepSkipped: String? = null
    var unusedRules: List<IgnoreRule> = emptyList()
    var ignoredFiles = 0
    var listedShards = 0
    var fetchedShards = 0
    var dryRun = false

    fun build(): IngestReport = IngestReport(
        albums = albums.toList(),
        deletedAlbums = deletedAlbums.toList(),
        pulledAlbums = pulledAlbums.toList(),
        failures = failures.toList(),
        strays = strays.toList(),
        mixedFolders = mixedFolders.toList(),
        looseRootFiles = looseRootFiles,
        blockedByUnreadable = blockedByUnreadable,
        contendedAlbums = contendedAlbums.toList(),
        duplicateNames = duplicateNames,
        orphanedAlbums = orphanedAlbums,
        blobsInZone = blobsInZone,
        skippedUploads = skippedUploads,
        skippedBytes = skippedBytes,
        abandonedUploads = abandonedUploads,
        sweptBlobs = sweptBlobs,
        sweptBytes = sweptBytes,
        sweepSkipped = sweepSkipped,
        unusedRules = unusedRules,
        ignoredFiles = ignoredFiles,
        listedShards = listedShards,
        fetchedShards = fetchedShards,
        dryRun = dryRun,
    )
}

/**
 * What a per-item failure is called in the report.
 *
 * [net.stho.photos.PhotosFailure] guarantees a non-opaque sentence (§1); anything else falls back
 * to what it can say about itself.
 */
private fun Throwable.describe(): String = message ?: toString()

private fun Path.deleteQuietly() {
    runCatching { SystemFileSystem.delete(this, mustExist = false) }
}

/** What a directory holds, counted before it is removed so the run can report it. */
private class Debris(val files: Int, val bytes: Long)

private fun Path.measure(): Debris {
    val metadata = SystemFileSystem.metadataOrNull(this) ?: return Debris(0, 0)
    if (!metadata.isDirectory) return Debris(1, metadata.size)
    var files = 0
    var bytes = 0L
    for (child in runCatching { SystemFileSystem.list(this) }.getOrDefault(emptyList())) {
        val found = child.measure()
        files += found.files
        bytes += found.bytes
    }
    return Debris(files, bytes)
}

private fun Path.deleteRecursively() {
    if (SystemFileSystem.metadataOrNull(this)?.isDirectory == true) {
        for (child in runCatching { SystemFileSystem.list(this) }.getOrDefault(emptyList())) {
            child.deleteRecursively()
        }
    }
    deleteQuietly()
}
