package net.stho.photos.app

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import net.stho.photos.S3HttpFailure
import net.stho.photos.StorageUnreachableFailure
import net.stho.photos.catalog.AlbumInfo
import net.stho.photos.catalog.AlbumState
import net.stho.photos.catalog.CatalogSync
import net.stho.photos.catalog.ObjectId
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.ShardWriteResult
import net.stho.photos.catalog.blobKey
import net.stho.photos.catalog.packThumbnails
import net.stho.photos.catalog.readShard
import net.stho.photos.catalog.writeTo
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.ports.SqlDrivers
import net.stho.photos.storage.presignedPut

/**
 * What the album dialog hands over: photos for an album (§8). Every upload is an addition — to an
 * album that exists, or to a new one whose id was minted in the dialog and no shard has yet.
 */
public data class UploadRequest(
    /** The album's name, which a new album is made with. */
    val name: String,
    /** The album's parent: the container it is in, or null at the root. */
    val parent: Uuid?,
    /** The album's path from the library root, `Trips / Italy`, for the sheet and the pill. */
    val path: String,
    val assetIds: List<String>,
    /** The gallery album chosen whole, null for loose photos: deleting takes it too once it is empty. */
    val galleryAlbum: String?,
    val deleteFromGallery: Boolean,
    /** The album the photos go into. */
    val addTo: Uuid,
)

public enum class UploadStage { Waiting, Preparing, Uploading, Finishing, Done, Failed }

/** One upload, as the sheet and the pill draw it. */
public data class UploadStatus(
    /** The upload's own id, which is its addition's. */
    val albumId: Uuid,
    /** The album the photos go into. */
    val target: Uuid,
    /** That album's name. */
    val name: String,
    /** That album's parent. */
    val parent: Uuid?,
    /** That album's path from the library root: what the sheet and the pill say it goes to. */
    val path: String,
    val stage: UploadStage,
    val files: Int = 0,
    val filesDone: Int = 0,
    val bytes: Long = 0,
    val bytesDone: Long = 0,
    /** §1's wording: the status and its cause, never an opaque error. */
    val failure: String? = null,
) {
    val fraction: Float
        get() = when {
            stage == UploadStage.Done -> 1f
            stage != UploadStage.Preparing && bytes > 0 -> (bytesDone.toFloat() / bytes).coerceIn(0f, 1f)
            files > 0 -> filesDone.toFloat() / files
            else -> 0f
        }
}

/**
 * §8's upload, from the chosen assets to an `uploaded` addition: a shard at `addition/<id>.db` that
 * names the album it adds to — one that exists, or a new one no shard is yet.
 *
 * ```
 * prepare    export every asset, name its file, pack the thumbnails     foreground, one album at a time
 * write      the shard at `uploading`, naming every object
 * transfer   the objects, through BackgroundUploader, each read back by HEAD
 * land       the shard again at `uploaded`, If-Match
 * delete     the gallery's copies, when that was asked for — and the gallery album, when it was
 *            chosen whole and holds nothing else
 * ```
 *
 * Each album keeps its state under `uploads/<album-id>/` — the request, the shard as written, and
 * every exported file — so a relaunch resumes exactly where the last run stopped. Nothing about a
 * transfer is remembered: what landed is asked of the zone, which is the one record a kill cannot
 * make stale.
 *
 * The app never deletes from the zone (§7). A cancelled upload leaves its shard at `uploading`,
 * which no reader shows, and the CLI removes it once its pre-signed URLs have expired.
 */
public class Uploads(
    private val gallery: Gallery,
    private val uploader: BackgroundUploader,
    private val sync: CatalogSync,
    private val drivers: SqlDrivers,
    cacheRoot: Path,
    private val scope: CoroutineScope,
    /** Called once an album has landed and the catalog holds it, so the list can show it. */
    private val onLanded: () -> Unit,
    private val clock: Clock = Clock.System,
) {
    private val root = Path(cacheRoot, "uploads")

    /** Preparing is foreground work on the phone, so two albums never prepare at once. */
    private val preparing = Mutex()
    private val lock = Mutex()
    private val jobs = mutableMapOf<Uuid, Job>()

    private val _statuses = MutableStateFlow<List<UploadStatus>>(emptyList())
    public val statuses: StateFlow<List<UploadStatus>> = _statuses.asStateFlow()

    init {
        SystemFileSystem.createDirectories(root)
    }

    /** Picks up every upload a previous run left unfinished, oldest first. Silently, no prompt (§8). */
    public fun resume() {
        scope.launch {
            val pending = SystemFileSystem.list(root)
                .mapNotNull { directory ->
                    val id = runCatching { Uuid.parse(directory.name) }.getOrNull() ?: return@mapNotNull null
                    Manifest.read(directory)?.let { id to it }
                }
                .sortedBy { (_, manifest) -> manifest.addedAt }
            for ((id, manifest) in pending) launchRun(id, manifest)
        }
    }

    public fun start(request: UploadRequest): Uuid {
        val id = Uuid.random()
        val manifest = Manifest(
            name = request.name,
            parent = request.parent,
            path = request.path,
            addedAt = Instant.fromEpochSeconds(clock.now().epochSeconds),
            deleteFromGallery = request.deleteFromGallery,
            galleryAlbum = request.galleryAlbum,
            assetIds = request.assetIds,
            stage = Manifest.Stage.REQUESTED,
            addTo = request.addTo,
        )
        // Written before anything runs, so an app killed during preparation starts it again.
        val directory = directory(id)
        SystemFileSystem.createDirectories(directory)
        manifest.write(directory)
        publish(manifest.status(id))
        scope.launch { launchRun(id, manifest) }
        return id
    }

    public fun retry(albumId: Uuid) {
        scope.launch { Manifest.read(directory(albumId))?.let { launchRun(albumId, it) } }
    }

    public fun cancel(albumId: Uuid) {
        scope.launch {
            lock.withLock { jobs.remove(albumId) }?.cancelAndJoin()
            val directory = directory(albumId)
            val keys = runCatching { shardFile(directory).readShard(drivers).objectIds.map { it.blobKey }.toSet() }
                .getOrDefault(emptySet())
            uploader.cancel(keys)
            directory.deleteRecursively()
            _statuses.update { list -> list.filterNot { it.albumId == albumId } }
        }
    }

    private suspend fun launchRun(id: Uuid, manifest: Manifest) = lock.withLock {
        if (jobs[id]?.isActive == true) return@withLock
        publish(manifest.status(id))
        jobs[id] = scope.launch {
            try {
                run(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update(id) { it.copy(stage = UploadStage.Failed, failure = failure.describe()) }
            }
        }
    }

    private suspend fun run(id: Uuid) {
        val directory = directory(id)
        var manifest = Manifest.read(directory) ?: return

        if (manifest.stage == Manifest.Stage.REQUESTED) {
            preparing.withLock { prepare(id, directory, manifest) }
            manifest = manifest.copy(stage = Manifest.Stage.PREPARED).also { it.write(directory) }
        }

        val shard = shardFile(directory).readShard(drivers)
        val objects = shard.objectIds.distinct().map { UploadObject(it, Path(directory, FILES, it.toString())) }
        update(id) {
            it.copy(
                stage = UploadStage.Uploading, files = objects.size, filesDone = 0,
                bytes = objects.sumOf(UploadObject::bytes), bytesDone = 0, failure = null,
            )
        }

        if (manifest.stage == Manifest.Stage.PREPARED) {
            // The shard first, naming every object before any of them exists: the referenced set is
            // then authoritative at every instant, and the sweep needs no age floor (§8).
            when (sync.writeShard(shard, ifMatch = null)) {
                is ShardWriteResult.Written -> Unit
                ShardWriteResult.StaleETag -> error("the zone refused the upload's shard")
            }
            manifest = manifest.copy(stage = Manifest.Stage.WRITTEN).also { it.write(directory) }
        }

        if (manifest.stage == Manifest.Stage.WRITTEN) {
            transfer(id, objects)
            update(id) { it.copy(stage = UploadStage.Finishing) }
            val landed = shard.copy(info = shard.info.copy(state = AlbumState.UPLOADED))
            when (sync.writeShard(landed, ifMatch = sync.etag(of = id))) {
                is ShardWriteResult.Written -> Unit
                ShardWriteResult.StaleETag -> error("the album's shard changed in the zone while it uploaded")
            }
            manifest = manifest.copy(stage = Manifest.Stage.LANDED).also { it.write(directory) }
            // The write recorded its own ETag, so the next sync sees nothing new to fetch — the
            // catalog is rebuilt from the shards on disk instead, which is where the album now is.
            sync.rebuildFromDisk()
            onLanded()
        }

        if (manifest.stage == Manifest.Stage.LANDED) {
            if (manifest.deleteFromGallery) gallery.delete(manifest.assetIds, manifest.galleryAlbum)
            directory.deleteRecursively()
            update(id) { it.copy(stage = UploadStage.Done, filesDone = it.files, bytesDone = it.bytes) }
        }
    }

    // --------------------------------------------------------------------------------- prepare

    private suspend fun prepare(id: Uuid, directory: Path, manifest: Manifest) {
        val files = Path(directory, FILES)
        val export = Path(directory, EXPORT)
        // A preparation cut short starts again from nothing: none of it has reached the zone.
        files.deleteRecursively()
        export.deleteRecursively()
        SystemFileSystem.createDirectories(files)
        SystemFileSystem.createDirectories(export)

        // Only the laptop names files (§7): it is the one place that can see what the album's folder
        // already holds, so the phone sends the camera's names as they are.
        val names = UploadNames()
        val rows = mutableListOf<PhotoRow>()
        val thumbnails = mutableMapOf<Uuid, ByteArray>()
        update(id) { it.copy(stage = UploadStage.Preparing, files = manifest.assetIds.size, filesDone = 0) }
        for ((index, assetId) in manifest.assetIds.withIndex()) {
            // An asset that left the library between choosing and preparing is skipped, not fatal.
            val asset = gallery.asset(assetId)
            if (asset != null) {
                val exported = gallery.export(asset, export)
                val row = row(asset, exported, files, names)
                rows += row
                thumbnails[row.id] = exported.thumbnail
            }
            update(id) { it.copy(filesDone = index + 1) }
        }
        check(rows.isNotEmpty()) { "none of the chosen photos is in the library any more" }

        val pack = Path(directory, "thumbs.db")
        thumbnails.packThumbnails(pack, drivers)
        val thumbsId = ObjectId.temporary()
        SystemFileSystem.atomicMove(pack, Path(files, thumbsId.toString()))

        Shard(
            AlbumInfo(
                id = id,
                name = manifest.name,
                parent = manifest.parent,
                thumbsId = thumbsId,
                state = AlbumState.UPLOADING,
                // "As uploaded, never encoded here" until the laptop pulls it (§5).
                encodingVersion = 0,
                addedAt = manifest.addedAt,
                addsTo = manifest.addTo,
            ),
            rows,
        ).writeTo(shardFile(directory), drivers)
        export.deleteRecursively()
    }

    /**
     * One asset as a row. Every object is named by a UUID: the phone never hashes (§2).
     *
     * The shapes are the ones §7's pull reads back — it archives `live_still_id`, else `image_id`,
     * else `video_id`, under `filename`, and a Live Photo's MOV under `live_video_filename`.
     */
    private fun row(asset: GalleryAsset, exported: ExportedAsset, files: Path, names: UploadNames): PhotoRow {
        fun keep(path: Path): ObjectId =
            ObjectId.temporary().also { SystemFileSystem.atomicMove(path, Path(files, it.toString())) }

        val pair = exported.pairedVideo.takeIf { exported.mediaType == MediaType.LIVE_PHOTO }
        val type = if (exported.mediaType == MediaType.LIVE_PHOTO && pair == null) MediaType.PHOTO else exported.mediaType
        val (filename, pairedName) = names.claim(
            stem = asset.filename.substringBeforeLast('.').ifEmpty { "IMG" },
            extension = exported.file.name.substringAfterLast('.', ""),
            pairedExtension = pair?.name?.substringAfterLast('.', "")?.ifEmpty { "MOV" },
        )
        val base = PhotoRow(
            id = Uuid.random(),
            filename = filename,
            takenAt = exported.takenAt?.let { Instant.fromEpochSeconds(it.epochSeconds) },
            latitude = exported.latitude,
            longitude = exported.longitude,
            width = exported.width,
            height = exported.height,
            bytes = SystemFileSystem.metadataOrNull(exported.file)?.size,
            mediaType = type,
        )
        val primary = keep(exported.file)
        return when (type) {
            MediaType.PHOTO -> base.copy(imageId = primary)
            // No poster: the pull archives `image_id` before `video_id`, so a poster here would be
            // archived in place of the video. The viewer shows the thumbnail until it is encoded.
            MediaType.VIDEO -> base.copy(videoId = primary)
            // One object for both ids: the still *is* the viewing image until the laptop encodes it.
            MediaType.LIVE_PHOTO -> base.copy(
                imageId = primary,
                liveStillId = primary,
                liveVideoId = keep(requireNotNull(pair)),
                liveVideoFilename = pairedName,
            )
        }
    }

    // -------------------------------------------------------------------------------- transfer

    private inner class UploadObject(val id: ObjectId, val file: Path) {
        val key: String = id.blobKey
        val bytes: Long = SystemFileSystem.metadataOrNull(file)?.size ?: 0

        /** Read back before it counts (§8): the size the zone holds against the file that was sent. */
        suspend fun landed(): Boolean = sync.s3.head(key)?.size == bytes

        /** Signed now, while the password is in memory; valid for §1's maximum of seven days. */
        fun transfer(): Transfer = Transfer(key, sync.s3.presignedPut(key, 7.days), file)
    }

    private suspend fun transfer(id: Uuid, objects: List<UploadObject>): Unit = coroutineScope {
        val guard = Mutex()
        val remaining = objects.associateBy(UploadObject::key).toMutableMap()
        val attempts = mutableMapOf<String, Int>()
        val finished = CompletableDeferred<Unit>()

        suspend fun progress() {
            val left = guard.withLock { remaining.keys.toSet() }
            val done = objects.filter { it.key !in left }
            update(id) { it.copy(filesDone = done.size, bytesDone = done.sumOf(UploadObject::bytes)) }
            if (left.isEmpty()) finished.complete(Unit)
        }

        suspend fun again(item: UploadObject, why: String) {
            val attempt = guard.withLock { (attempts[item.key] ?: 0).plus(1).also { attempts[item.key] = it } }
            if (attempt > ATTEMPTS) {
                finished.completeExceptionally(IllegalStateException("${item.key} would not upload: $why"))
            } else {
                uploader.enqueue(listOf(item.transfer()))
            }
        }

        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            uploader.events.collect { event ->
                val item = guard.withLock { remaining[event.key] } ?: return@collect
                when (event) {
                    is TransferEvent.Sent -> Unit
                    is TransferEvent.Finished ->
                        if (item.landed()) {
                            guard.withLock { remaining.remove(item.key) }
                            progress()
                        } else {
                            again(item, "the zone holds a different size than was sent")
                        }
                    is TransferEvent.Failed -> when (val status = event.status) {
                        null -> again(item, event.reason)
                        else -> finished.completeExceptionally(
                            S3HttpFailure(status, detail = event.reason.ifBlank { null }, key = item.key),
                        )
                    }
                }
            }
        }

        // What already landed is asked of the zone rather than remembered: a relaunch cannot know
        // which events it missed while it was not running.
        val flying = uploader.inFlight()
        val send = mutableListOf<UploadObject>()
        for (item in objects) {
            when {
                item.key in flying -> Unit
                item.landed() -> guard.withLock { remaining.remove(item.key) }
                else -> send += item
            }
        }
        uploader.enqueue(send.map { it.transfer() })
        progress()
        try {
            finished.await()
        } finally {
            listener.cancel()
        }
    }

    // ---------------------------------------------------------------------------------- state

    private fun directory(id: Uuid): Path = Path(root, id.toString())

    private fun shardFile(directory: Path): Path = Path(directory, "shard.db")

    private fun publish(status: UploadStatus) = _statuses.update { list ->
        if (list.any { it.albumId == status.albumId }) {
            list.map { if (it.albumId == status.albumId) status else it }
        } else {
            list + status
        }
    }

    private fun update(id: Uuid, change: (UploadStatus) -> UploadStatus) = _statuses.update { list ->
        list.map { if (it.albumId == id) change(it) else it }
    }

    private fun Throwable.describe(): String = when (this) {
        is S3HttpFailure -> "Upload failed: $userMessage"
        is StorageUnreachableFailure -> "No network — retry once the storage zone can be reached"
        else -> "Upload failed: ${message ?: this::class.simpleName ?: "unknown error"}"
    }

    private companion object {
        const val FILES = "files"
        const val EXPORT = "export"

        /** Per object, for a transfer that errors without an HTTP status or lands short. */
        const val ATTEMPTS = 3
    }
}

/**
 * The names an upload's rows carry: the camera's stem with the extension of the bytes sent, a Live
 * Photo's MOV named beside its still. Clashes and all — only the laptop can see what the album's
 * folder holds, so only the laptop makes a name unique (§7).
 */
internal class UploadNames {

    fun claim(stem: String, extension: String, pairedExtension: String? = null): Pair<String, String?> =
        stem.withExtension(extension) to pairedExtension?.let { stem.withExtension(it) }

    private fun String.withExtension(extension: String) = if (extension.isEmpty()) this else "$this.$extension"
}

/** What `uploads/<upload-id>/manifest` holds: the request, and how far it got. */
private data class Manifest(
    val name: String,
    val parent: Uuid?,
    val path: String,
    val addedAt: Instant,
    val deleteFromGallery: Boolean,
    /** Absent from a manifest written before albums were deleted too: that upload deletes its assets only. */
    val galleryAlbum: String?,
    val assetIds: List<String>,
    val stage: Stage,
    /** The album these photos are added to. */
    val addTo: Uuid,
) {
    enum class Stage { REQUESTED, PREPARED, WRITTEN, LANDED }

    fun status(id: Uuid): UploadStatus =
        UploadStatus(id, addTo, name, parent, path, UploadStage.Waiting, files = assetIds.size)

    fun write(directory: Path) {
        val text = buildString {
            appendLine("stage=${stage.name}")
            appendLine("name=${name.replace('\n', ' ')}")
            appendLine("parent=${parent ?: ""}")
            appendLine("path=${path.replace('\n', ' ')}")
            appendLine("added=${addedAt.epochSeconds}")
            appendLine("delete=$deleteFromGallery")
            galleryAlbum?.let { appendLine("album=$it") }
            appendLine("adds=$addTo")
            for (asset in assetIds) appendLine("asset=$asset")
        }
        val scratch = Path(directory, "manifest.part")
        SystemFileSystem.sink(scratch).buffered().use { it.writeString(text) }
        SystemFileSystem.atomicMove(scratch, Path(directory, "manifest"))
    }

    companion object {
        fun read(directory: Path): Manifest? {
            val path = Path(directory, "manifest")
            if (!SystemFileSystem.exists(path)) return null
            val lines = SystemFileSystem.source(path).buffered().use { it.readString() }.lines()
            fun value(key: String) = lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
            return runCatching {
                Manifest(
                    name = requireNotNull(value("name")),
                    parent = value("parent")?.ifEmpty { null }?.let(Uuid::parse),
                    path = value("path") ?: requireNotNull(value("name")),
                    addedAt = Instant.fromEpochSeconds(requireNotNull(value("added")).toLong()),
                    deleteFromGallery = value("delete") == "true",
                    galleryAlbum = value("album")?.ifEmpty { null },
                    assetIds = lines.filter { it.startsWith("asset=") }.map { it.substringAfter('=') },
                    stage = Stage.valueOf(requireNotNull(value("stage"))),
                    // A manifest from before every upload was an addition has none, and is not resumed:
                    // the laptop no longer pulls a phone album written under `meta/` (§8).
                    addTo = Uuid.parse(requireNotNull(value("adds"))),
                )
            }.getOrNull()
        }
    }
}

private fun Path.deleteRecursively() {
    val metadata = SystemFileSystem.metadataOrNull(this) ?: return
    if (metadata.isDirectory) SystemFileSystem.list(this).forEach { it.deleteRecursively() }
    SystemFileSystem.delete(this, mustExist = false)
}
