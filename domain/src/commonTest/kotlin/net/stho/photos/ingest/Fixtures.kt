package net.stho.photos.ingest

import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import net.stho.photos.catalog.AlbumInfo
import net.stho.photos.catalog.SHARD_SCHEMA_VERSION
import net.stho.photos.catalog.Shard
import net.stho.photos.catalog.ShardProbe
import net.stho.photos.catalog.temporaryDirectory
import net.stho.photos.exif.ExifTags
import net.stho.photos.exif.ExifValue
import net.stho.photos.library.IgnoreRules
import net.stho.photos.library.LibraryContents
import net.stho.photos.library.LibraryWalker
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.pipeline.Derivatives
import net.stho.photos.pipeline.MediaFormat
import net.stho.photos.pipeline.MediaItem
import net.stho.photos.pipeline.OriginalSource
import net.stho.photos.pipeline.PipelineEvent
import net.stho.photos.pipeline.VideoInfo
import net.stho.photos.pipeline.withExtension
import net.stho.photos.ports.Ids
import net.stho.photos.ports.ImageBackend
import net.stho.photos.ports.Keyring
import net.stho.photos.ports.KeyringRead
import net.stho.photos.ports.MediaProbe
import net.stho.photos.ports.Pipeline

/** The instant every fixture album was added: 2013-07-04T18:12:11Z. */
internal val fixtureAddedAt: Instant = Instant.fromEpochSeconds(1_372_961_531)

/** Freshly minted identity, for a test that does not care which uuids it gets. */
internal val randomIds: Ids = Ids { Uuid.random() }

/**
 * A throwaway library on disk, plus the shards a previous run would have written for it.
 *
 * Everything the rules can get wrong is a question about a directory and a shard disagreeing, so
 * the fixtures are exactly that: real directories with real files, and hand-built shards. No
 * network and no imaging — those are the two things the rules under test never consult.
 */
internal class LibraryFixture(label: String = "library") {

    val root: Path = temporaryDirectory("ingest-$label")

    init {
        marker()
    }

    /**
     * The root marker. Present by default because almost every test wants a library that *is* a
     * library; [removeMarker] is what the guard's own test uses.
     */
    fun marker(contents: String = "# nothing excluded\n") {
        write(Path(root, IgnoreRules.FILENAME), contents.encodeToByteArray())
    }

    fun removeMarker() {
        SystemFileSystem.delete(Path(root, IgnoreRules.FILENAME))
    }

    fun file(path: String, bytes: Int = 64): Path = file(path, ByteArray(bytes) { 0x41 })

    fun file(path: String, contents: ByteArray): Path {
        val file = pathOf(path)
        file.parent?.let(SystemFileSystem::createDirectories)
        write(file, contents)
        return file
    }

    fun directory(path: String) {
        SystemFileSystem.createDirectories(pathOf(path))
    }

    fun remove(path: String) {
        pathOf(path).deleteRecursively()
    }

    fun pathOf(path: String): Path = Path(root, *path.split('/').toTypedArray())

    fun walk(): LibraryContents = LibraryWalker(root).walk()

    fun plan(
        shards: List<Shard> = emptyList(),
        unreadable: List<ShardProbe> = emptyList(),
        filter: String? = null,
    ): IngestPlan = Reconciler(root, randomIds, filter).plan(walk(), shards, unreadable)

    // ------------------------------------------------------- shards a previous run would have left

    /** A shard claiming [path], with one row per name given. */
    fun shard(
        path: String,
        photos: List<String>,
        id: Uuid = Uuid.random(),
        parent: Uuid? = null,
        thumbsId: Uuid? = Uuid.random(),
        bytes: Long = 64,
    ): Shard = Shard(
        info = AlbumInfo(
            id = id,
            name = path.substringAfterLast('/'),
            parent = parent,
            sourcePath = path,
            thumbsId = thumbsId,
            addedAt = fixtureAddedAt,
            schemaVersion = SHARD_SCHEMA_VERSION,
        ),
        photos = photos.map { row(it, bytes) },
    )

    /** A still: the blob in the zone *is* the file on disk, so its size is checkable. */
    fun row(name: String, bytes: Long = 64): PhotoRow = PhotoRow(
        id = Uuid.random(),
        filename = name,
        bytes = bytes,
        mediaType = MediaType.PHOTO,
        originalId = Uuid.random(),
        previewId = Uuid.random(),
    )

    /**
     * A video row: named after the transcode, sized after the transcode, with the camera's
     * filename kept as the source. There is no original in the zone, so nothing on disk should
     * equal `bytes` and the assertion must leave it alone.
     */
    fun videoRow(source: String, bytes: Long = 999_999): PhotoRow = PhotoRow(
        id = Uuid.random(),
        filename = source.withExtension("mp4"),
        sourceFilename = source,
        bytes = bytes,
        mediaType = MediaType.VIDEO,
        previewId = Uuid.random(),
        videoId = Uuid.random(),
    )

    /** A carved CR2: `.jpg` in the zone, `.CR2` on disk. */
    fun rawRow(source: String, bytes: Long = 1_600_000): PhotoRow = PhotoRow(
        id = Uuid.random(),
        filename = source.withExtension("jpg"),
        sourceFilename = source,
        bytes = bytes,
        mediaType = MediaType.PHOTO,
        originalId = Uuid.random(),
        previewId = Uuid.random(),
    )
}

internal fun write(path: Path, bytes: ByteArray) {
    SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
}

internal fun Path.deleteRecursively() {
    if (SystemFileSystem.metadataOrNull(this)?.isDirectory == true) {
        for (child in SystemFileSystem.list(this)) child.deleteRecursively()
    }
    SystemFileSystem.delete(this, mustExist = false)
}

// ------------------------------------------------------------------------------------- the ports

/**
 * A pipeline that produces the *shape* of a derivative without an encoder.
 *
 * The pipeline is a port (§7) and the imaging stack lives in the Linux adapter, so the domain's
 * own tests cannot run a real encoder over synthetic JPEGs. What they can pin down is the state
 * machine around it, which is what these tests are about anyway.
 */
internal class FakePipeline(private val ids: Ids, private val workRoot: Path) : Pipeline {

    override val events: SharedFlow<PipelineEvent> = MutableSharedFlow()

    override fun derive(item: MediaItem): Derivatives {
        val size = SystemFileSystem.metadataOrNull(Path(item.path))?.size ?: 0
        val thumbnail = "thumbnail:${item.filename}".encodeToByteArray()
        val preview = "preview:${item.filename}".encodeToByteArray()
        val id = ids.next()

        return when (val kind = item.kind) {
            MediaItem.Kind.Still -> Derivatives(
                row = row(id, item.filename, bytes = size, mediaType = MediaType.PHOTO),
                tags = ExifTags(),
                thumbnail = thumbnail,
                preview = preview,
                original = OriginalSource.File(item.path),
            )

            MediaItem.Kind.Raw -> {
                val carved = "carved:${item.filename}".encodeToByteArray()
                Derivatives(
                    row = row(
                        id, item.filename.withExtension("jpg"),
                        sourceFilename = item.filename,
                        bytes = carved.size.toLong(),
                        mediaType = MediaType.PHOTO,
                    ),
                    tags = ExifTags(),
                    thumbnail = thumbnail,
                    preview = preview,
                    original = OriginalSource.Bytes(carved),
                )
            }

            is MediaItem.Kind.LivePhoto -> Derivatives(
                row = row(id, item.filename, bytes = size, mediaType = MediaType.LIVE_PHOTO),
                tags = ExifTags(),
                thumbnail = thumbnail,
                preview = preview,
                original = OriginalSource.File(item.path),
                liveVideo = kind.video,
            )

            MediaItem.Kind.Video -> {
                val transcoded = "transcode:${item.filename}".encodeToByteArray()
                val destination = Path(workRoot, "$id.mp4")
                write(destination, transcoded)
                Derivatives(
                    row = row(
                        id, item.filename.withExtension("mp4"),
                        sourceFilename = item.filename,
                        bytes = transcoded.size.toLong(),
                        mediaType = MediaType.VIDEO,
                    ),
                    tags = ExifTags(),
                    thumbnail = thumbnail,
                    preview = preview,
                    original = OriginalSource.None,
                    video = destination.toString(),
                )
            }
        }
    }

    private fun row(
        id: Uuid,
        filename: String,
        sourceFilename: String? = null,
        bytes: Long,
        mediaType: MediaType,
    ): PhotoRow = PhotoRow(
        id = id,
        filename = filename,
        sourceFilename = sourceFilename,
        takenAt = fixtureAddedAt,
        width = 320,
        height = 240,
        bytes = bytes,
        mediaType = mediaType,
    )
}

/**
 * Sniffing by extension, which the real probe deliberately does not do.
 *
 * Legitimate here and nowhere else: these fixtures have no real containers to sniff, and what is
 * under test is the classifier's pairing and ingest's state machine rather than the probe.
 */
internal class FakeProbe(
    /** Filename → `content.identifier`, for the Live Photo pairing. */
    private val identifiers: Map<String, String> = emptyMap(),
) : MediaProbe {

    override fun sniff(path: String): MediaFormat =
        when (path.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> MediaFormat.JPEG
            "heic" -> MediaFormat.HEIF
            "png" -> MediaFormat.PNG
            "cr2" -> MediaFormat.CR2
            "mov", "mp4" -> MediaFormat.VIDEO
            else -> MediaFormat.UNKNOWN
        }

    override fun videoInfo(path: String): VideoInfo = VideoInfo(
        width = 64,
        height = 48,
        duration = 1.0,
        rotation = 0,
        hasAudio = false,
        isInterlaced = false,
        contentIdentifier = identifiers[path.substringAfterLast('/')],
    )
}

/** Tags for the one thing the classifier reads from a still: the Live Photo identifier. */
internal class FakeBackend(private val identifiers: Map<String, String> = emptyMap()) : ImageBackend {
    override fun rawTags(path: String): ExifTags {
        val identifier = identifiers[path.substringAfterLast('/')] ?: return ExifTags()
        return ExifTags(mapOf("AppleContentIdentifier" to ExifValue.Text(identifier)))
    }
}

/** A keyring whose three outcomes a test can state exactly. */
internal class FakeKeyring(private val answers: Map<String, KeyringRead> = emptyMap()) : Keyring {

    val asked: MutableList<String> = mutableListOf()

    override fun read(field: String): KeyringRead {
        asked += field
        return answers[field] ?: KeyringRead.Absent
    }

    override fun write(field: String, secret: String): Unit = error("not used by resolution")

    override fun remove(field: String): Unit = error("not used by resolution")
}
