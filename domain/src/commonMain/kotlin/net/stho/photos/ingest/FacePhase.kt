package net.stho.photos.ingest

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.openDriver
import net.stho.photos.faces.DetectedFace
import net.stho.photos.faces.FaceModels
import net.stho.photos.faces.FacesFile
import net.stho.photos.faces.IndexEntry
import net.stho.photos.faces.IndexedFace
import net.stho.photos.faces.LabelSet
import net.stho.photos.faces.Labels
import net.stho.photos.faces.Matching
import net.stho.photos.faces.PeopleIndex
import net.stho.photos.faces.StoredFace
import net.stho.photos.faces.VerdictKind
import net.stho.photos.faces.people.PeopleDatabase
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.pipeline.MediaItem
import net.stho.photos.ports.Ids
import net.stho.photos.ports.MediaUnreadable
import net.stho.photos.ports.Pipeline
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.storage.Body
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.list
import net.stho.photos.storage.sha256Hex

/**
 * §12's step of `sync`: every photograph's faces, the zone's copy of them and of the labels, and
 * the index the desktop viewer lists people from.
 *
 * ```
 * scan     each album's photos not yet looked at with this model — from the decode the run
 *          already made for a new photo, from a fresh one for the rest (the backfill)
 * keep     faces/<album>.db in the cache, rewritten whole when it changes; uploaded when its
 *          digest differs from what the zone was last sent; removed with its album
 * labels   a snapshot of $LIBRARY_ROOT/.photos/people.db, uploaded as people/people.db
 * index    people_index.db, rebuilt when any of the above changed: verdicts attached to faces,
 *          suggestions, unknown groups
 * ```
 *
 * Runs after everything else a run does, on the shards as they stand, and on a run that otherwise
 * had nothing to do — a backfill, or a labelling session since the last run, is work of its own.
 * The laptop is the only writer of both prefixes (§12), so what is in the cache is the truth and
 * the zone is its copy: a faces file is fetched only when the cache has lost it.
 */
public class FacePhase(
    private val config: IngestConfig,
    private val s3: S3Client,
    private val pipeline: Pipeline,
    private val drivers: SqlDrivers,
    private val ids: Ids,
    private val clock: Clock = Clock.System,
) {
    private val directory = Path(config.cacheRoot, DIRECTORY)
    private val index = PeopleIndex(Path(config.cacheRoot, PeopleIndex.FILENAME), drivers)
    private val labels = Labels.at(config.libraryRoot, drivers)

    public class Outcome(
        public val summary: IngestReport.FacesOutcome,
        public val failures: List<IngestReport.Failure>,
    )

    /**
     * [derived] is the faces this run's derive already found, by row id. [workers] is where the
     * decoding happens — ingest's encoder threads, since a decode blocks — asked for only once a
     * photo actually needs decoding, so a run with nothing to scan starts no threads (§7's no-op
     * run). [status] carries progress to whoever is watching.
     */
    public suspend fun run(
        shards: List<Shard>,
        unreadable: Int,
        derived: Map<Uuid, List<DetectedFace>>,
        workers: () -> CoroutineContext,
        status: (String) -> Unit,
    ): Outcome {
        val failures = mutableListOf<IngestReport.Failure>()
        SystemFileSystem.createDirectories(directory)
        val work = Path(config.workRoot, "faces")
        SystemFileSystem.createDirectories(work)

        val zone = mutableMapOf<String, Long>()
        for (prefix in listOf(FACES_PREFIX, PEOPLE_PREFIX)) {
            s3.list(prefix = prefix).collect { if (!it.isDirectoryMarker) zone[it.key] = it.size }
        }

        val albums = albumsOf(shards)
        // A faces file the cache lost comes back from the zone rather than being scanned again.
        for (album in albums) {
            val id = album.info.id
            if (!SystemFileSystem.exists(path(id)) && facesKey(id) in zone) fetch(id)
        }

        val files = mutableMapOf<Uuid, FacesFile>()
        var scanned = 0
        var found = 0
        status("faces  reading ${albums.size} albums")
        val total = albums.sumOf { album -> pending(album, localFaces(album.info.id)).size }
        var done = 0
        for (album in albums) {
            val existing = localFaces(album.info.id)
            val todo = pending(album, existing)
            val photos = album.photos.filter(::analysed).mapTo(mutableSetOf(), PhotoRow::id)
            val kept = existing?.faces.orEmpty().filter { it.photoId in photos }
            val keptScanned = existing?.scanned.orEmpty().intersect(photos)

            val results = scan(album, todo, derived, workers, failures) {
                done++
                status("faces $done/$total  ${album.info.sourcePath}")
            }
            val newFaces = results.flatMap { (photo, faces) ->
                faces.map { StoredFace(ids.next(), photo, it) }
            }
            scanned += results.size
            found += newFaces.size

            val file = FacesFile(
                albumId = album.info.id,
                modelVersion = FaceModels.VERSION,
                scanned = keptScanned + results.map { it.first },
                faces = kept + newFaces,
            )
            val changed = existing == null || results.isNotEmpty() ||
                kept.size != existing.faces.size || keptScanned.size != existing.scanned.size
            if (changed) {
                val staged = Path(work, "${album.info.id}.db")
                file.write(staged, drivers)
                SystemFileSystem.atomicMove(staged, path(album.info.id))
            }
            files[album.info.id] = file
        }

        // An album the shards no longer name has lost its faces too. Not with an unreadable shard
        // on disk, for the sweep's reason: what that album owns cannot be known.
        var deleted = 0
        if (unreadable == 0 && config.albumFilter == null) {
            val live = shards.mapTo(mutableSetOf()) { it.info.id }
            for (file in runCatching { SystemFileSystem.list(directory) }.getOrDefault(emptyList())) {
                val id = file.name.removeSuffix(".db").let { runCatching { Uuid.parse(it) }.getOrNull() } ?: continue
                if (id in live) continue
                file.deleteQuietly()
                if (facesKey(id) in zone) {
                    s3.delete(facesKey(id))
                    index.forgetUploaded(facesKey(id))
                    deleted++
                }
            }
        }

        val uploaded = index.uploaded()
        val digests = albums.associateTo(mutableMapOf()) { it.info.id to path(it.info.id).sha256Hex() }
        val unsent = albums.map { it.info.id }.filter { id ->
            val key = facesKey(id)
            uploaded[key] != digests.getValue(id) || key !in zone
        }
        // Counted, like the scan: a first run sends one file per album, and a line naming the last
        // album scanned read as a run that had stopped.
        for ((index, id) in unsent.withIndex()) {
            status("faces  uploading ${index + 1}/${unsent.size}")
            s3.put(facesKey(id), Body.File(path(id)))
            this.index.recordUploaded(facesKey(id), digests.getValue(id))
        }
        val sent = unsent.size

        val snapshot = Path(work, "people.db")
        status("faces  reading labels")
        val labelSet = snapshotLabels(snapshot)
        var labelsSent = false
        val labelsDigest = if (labelSet != null) snapshot.sha256Hex() else null
        if (labelsDigest != null && (uploaded[PEOPLE_KEY] != labelsDigest || PEOPLE_KEY !in zone)) {
            status("faces  uploading labels")
            s3.put(PEOPLE_KEY, Body.File(snapshot))
            index.recordUploaded(PEOPLE_KEY, labelsDigest)
            labelsSent = true
        }

        // The index is over the whole library, not just the albums an `--album` run scanned.
        for (file in runCatching { SystemFileSystem.list(directory) }.getOrDefault(emptyList())) {
            val id = file.name.removeSuffix(".db").let { runCatching { Uuid.parse(it) }.getOrNull() } ?: continue
            if (id in files) continue
            FacesFile.read(file, drivers)?.takeIf { it.modelVersion == FaceModels.VERSION }?.let {
                files[id] = it
                digests[id] = file.sha256Hex()
            }
        }
        val inputs = (digests.entries.sortedBy { it.key.toString() }.map { "${it.key}:${it.value}" } +
            "labels:${labelsDigest ?: "-"}" + "model:${FaceModels.VERSION}").joinToString("\n").encodeToByteArray().sha256Hex()
        val rebuild = index.inputs() != inputs
        val summary = if (rebuild) {
            // The last grouping, kept rather than redone (see Matching.group) — unless it was made
            // with another model, whose faces are not these.
            val previous = if (index.modelVersion() == FaceModels.VERSION) {
                index.read().mapNotNull { entry -> entry.group?.let { entry.faceId to it } }.toMap()
            } else {
                emptyMap()
            }
            val entries = conclude(files.values.toList(), labelSet ?: LabelSet(emptyList(), emptyList()), previous, status)
            status("faces  writing the index")
            index.rebuild(entries, inputs, clock.now())
            summarise(entries, labelSet)
        } else {
            summarise(index.read(), labelSet)
        }

        work.deleteRecursivelyQuietly()
        return Outcome(
            summary.copy(
                scannedPhotos = scanned,
                foundFaces = found,
                uploadedFiles = sent,
                deletedFiles = deleted,
                labelsUploaded = labelsSent,
                indexRebuilt = rebuild,
            ),
            failures,
        )
    }

    /** What a run would scan, without scanning — the dry run's line. */
    public fun pendingCount(shards: List<Shard>): Int =
        albumsOf(shards).sumOf { album -> pending(album, localFaces(album.info.id)).size }

    // ------------------------------------------------------------------------------ scanning

    private fun albumsOf(shards: List<Shard>): List<Shard> = shards
        .filter { !it.info.isAddition && it.info.sourcePath != null }
        .filter { album -> config.albumFilter?.let { album.info.sourcePath!!.contains(it) } ?: true }
        .sortedBy { it.info.sourcePath }

    /** Video is left out of the first version (§12); a Live Photo is its still. */
    private fun analysed(row: PhotoRow): Boolean = row.mediaType != MediaType.VIDEO

    private fun pending(album: Shard, existing: FacesFile?): List<PhotoRow> {
        val scanned = existing?.scanned.orEmpty()
        return album.photos.filter { analysed(it) && it.id !in scanned }
    }

    /** The album's faces file as the cache has it, or null when there is none of this model's. */
    private fun localFaces(album: Uuid): FacesFile? =
        FacesFile.read(path(album), drivers)?.takeIf { it.modelVersion == FaceModels.VERSION }

    private suspend fun scan(
        album: Shard,
        todo: List<PhotoRow>,
        derived: Map<Uuid, List<DetectedFace>>,
        workers: () -> CoroutineContext,
        failures: MutableList<IngestReport.Failure>,
        tick: () -> Unit,
    ): List<Pair<Uuid, List<DetectedFace>>> {
        if (todo.isEmpty()) return emptyList()
        val folder = Path(config.libraryRoot, *album.info.sourcePath!!.split('/').toTypedArray())
        val permits = Semaphore(config.jobs)
        val results = coroutineScope {
            todo.map { row ->
                async {
                    permits.withPermit {
                        val result = derived[row.id]?.let { row.id to it } ?: analyse(row, folder, workers, failures)
                        tick()
                        result
                    }
                }
            }.awaitAll()
        }
        return results.filterNotNull()
    }

    /**
     * One photograph's faces, from the file on disk. Null for a file that is not there — a photo
     * not pulled down yet — which stays unscanned and is tried again next run. A file that will
     * not decode is reported, and recorded as scanned with no faces rather than failing every run.
     */
    private suspend fun analyse(
        row: PhotoRow,
        folder: Path,
        workers: () -> CoroutineContext,
        failures: MutableList<IngestReport.Failure>,
    ): Pair<Uuid, List<DetectedFace>>? {
        val name = row.sourceFilename ?: row.filename
        val file = Path(folder, name)
        if (!SystemFileSystem.exists(file)) return null
        val kind = if (name.substringAfterLast('.').equals("CR2", ignoreCase = true)) MediaItem.Kind.Raw else MediaItem.Kind.Still
        return try {
            val faces = withContext(workers()) { pipeline.findFaces(MediaItem(file.toString(), kind, 0)) } ?: return null
            row.id to faces
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: MediaUnreadable) {
            failures += IngestReport.Failure(relative(file), "faces: ${unreadable.message}")
            row.id to emptyList()
        }
    }

    private suspend fun fetch(album: Uuid) {
        val partial = Path(directory, "$album.${Uuid.random()}.part")
        try {
            s3.download(facesKey(album), partial)
            SystemFileSystem.atomicMove(partial, path(album))
        } catch (cancelled: CancellationException) {
            partial.deleteQuietly()
            throw cancelled
        } catch (_: Exception) {
            // Scanned again instead: faces are derived, and a download that failed costs time only.
            partial.deleteQuietly()
        }
    }

    // ------------------------------------------------------------------------------ labels

    /**
     * A consistent copy of the labels file, taken with `VACUUM INTO` so a viewer writing at the
     * same moment is either wholly in it or wholly not — and the copy is compact, so the same
     * labels make the same bytes. Null when there is no labels file yet.
     */
    private fun snapshotLabels(to: Path): LabelSet? {
        if (!labels.exists) return null
        to.deleteQuietly()
        val driver = labels.path.openDriver(drivers, PeopleDatabase.Schema, creating = false)
        try {
            val quoted = to.toString().replace("'", "''")
            driver.execute(null, "VACUUM INTO '$quoted'", 0)
        } finally {
            driver.close()
        }
        return Labels(to, drivers).read()
    }

    // ------------------------------------------------------------------------------ the index

    private fun conclude(
        files: List<FacesFile>,
        labelSet: LabelSet,
        previous: Map<Uuid, Int>,
        status: (String) -> Unit,
    ): List<IndexEntry> {
        val faces = files.flatMap { file ->
            file.faces.map { IndexedFace(it.id, file.albumId, it.photoId, it.face) }
        }
        status("faces  suggesting among ${faces.size} faces")
        val resolved = Matching.resolve(faces, labelSet.verdicts)
        val suggestions = Matching.suggest(faces, resolved)
        val unknown = faces.filter {
            it.id !in resolved.confirmed && it.id !in resolved.ignored && it.id !in suggestions
        }
        val groups = Matching.group(unknown, previous) { done, total -> status("faces  grouping $done/$total") }
        return faces.map { face ->
            val confirmed = resolved.confirmed[face.id]
            val suggestion = suggestions[face.id]
            IndexEntry(
                faceId = face.id,
                albumId = face.albumId,
                photoId = face.photoId,
                box = face.face.box,
                score = face.face.score,
                verdict = when {
                    confirmed != null -> VerdictKind.CONFIRMED
                    face.id in resolved.ignored -> VerdictKind.IGNORED
                    face.id in resolved.rejected -> VerdictKind.REJECTED
                    else -> null
                },
                personId = confirmed ?: suggestion?.person,
                suggested = confirmed == null && suggestion != null,
                similarity = suggestion?.similarity,
                group = groups[face.id],
            )
        }
    }

    private fun summarise(entries: List<IndexEntry>, labelSet: LabelSet?): IngestReport.FacesOutcome =
        IngestReport.FacesOutcome(
            people = labelSet?.people?.size ?: 0,
            confirmed = entries.count { it.verdict == VerdictKind.CONFIRMED },
            suggested = entries.count { it.suggested },
            groups = entries.mapNotNullTo(mutableSetOf()) { it.group }.size,
            totalFaces = entries.size,
        )

    private fun path(album: Uuid): Path = Path(directory, "$album.db")

    private fun relative(path: Path): String {
        val root = config.libraryRoot.toString().trimEnd('/')
        val text = path.toString()
        return if (text.startsWith(root)) text.drop(root.length).trimStart('/') else text
    }

    public companion object {
        /** `faces/` in the cache, beside `shards/` and `packs/`. */
        public const val DIRECTORY: String = "faces"
        public const val FACES_PREFIX: String = "faces/"
        public const val PEOPLE_PREFIX: String = "people/"
        public const val PEOPLE_KEY: String = "people/people.db"

        public fun facesKey(album: Uuid): String = "$FACES_PREFIX$album.db"
    }
}

private fun Path.deleteQuietly() {
    runCatching { SystemFileSystem.delete(this, mustExist = false) }
}

private fun Path.deleteRecursivelyQuietly() {
    if (SystemFileSystem.metadataOrNull(this)?.isDirectory == true) {
        for (child in runCatching { SystemFileSystem.list(this) }.getOrDefault(emptyList())) {
            child.deleteRecursivelyQuietly()
        }
    }
    deleteQuietly()
}
