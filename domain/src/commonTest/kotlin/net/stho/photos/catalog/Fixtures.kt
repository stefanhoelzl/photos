package net.stho.photos.catalog

import app.cash.sqldelight.db.QueryResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.decodeURLPart
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.ports.Journal
import net.stho.photos.scratchRoot
import kotlinx.io.readByteArray
import net.stho.photos.catalog.merged.MergedDatabase
import net.stho.photos.catalog.shard.ShardDatabase
import net.stho.photos.derivative.DerivativeSpec
import net.stho.photos.model.MediaType
import net.stho.photos.model.PhotoRow
import net.stho.photos.storage.S3Client
import net.stho.photos.storage.TEST_SECRET
import net.stho.photos.storage.testStorage

/**
 * Synthetic catalogs.
 *
 * §10 verifies B with generated fixtures rather than committed `.db` files: nothing goes stale,
 * and a diff of a test stays readable. The one thing a writer-and-reader round trip structurally
 * cannot see — a shard written by a *newer* schema — is [futureShard], which forges the version
 * rather than shipping a binary.
 */

/** The instant every dated fixture photo hangs off: 2013-07-04T18:12:11Z. */
internal val fixtureEpoch: Instant = Instant.fromEpochSeconds(1_372_961_531)

/** A photo with everything filled in, so a round trip proves every column. */
internal fun photo(
    name: String,
    takenAt: Instant? = fixtureEpoch,
    latitude: Double? = 47.994563,
    longitude: Double? = 12.268569,
    mediaType: MediaType = MediaType.PHOTO,
): PhotoRow = PhotoRow(
    id = Uuid.random(),
    filename = name,
    takenAt = takenAt,
    latitude = latitude,
    longitude = longitude,
    width = 4000,
    height = 3000,
    bytes = 3_145_728,
    mediaType = mediaType,
    imageId = blobId(),
    liveStillId = if (mediaType == MediaType.LIVE_PHOTO) blobId() else null,
    liveVideoId = if (mediaType == MediaType.LIVE_PHOTO) blobId() else null,
    liveVideoFilename = if (mediaType == MediaType.LIVE_PHOTO) {
        name.substringBeforeLast('.') + ".mov"
    } else {
        null
    },
    videoId = if (mediaType == MediaType.VIDEO) blobId() else null,
)

internal fun album(
    name: String,
    parent: Uuid? = null,
    photos: List<PhotoRow> = emptyList(),
    coverPhotoId: Uuid? = null,
    thumbsId: ObjectId? = blobId(),
    schemaVersion: Int = SHARD_SCHEMA_VERSION,
    state: AlbumState = AlbumState.ENCODED,
): Shard = Shard(
    info = AlbumInfo(
        id = Uuid.random(),
        name = name,
        parent = parent,
        sourcePath = name,
        coverPhotoId = coverPhotoId,
        thumbsId = thumbsId,
        state = state,
        // The schema's CHECK: only an encoded album has a profile.
        encodingVersion = if (state == AlbumState.ENCODED) DerivativeSpec.ENCODING_VERSION else 0,
        addedAt = fixtureEpoch,
        schemaVersion = schemaVersion,
    ),
    photos = photos,
)

/**
 * Photos the phone is adding to [target] (§8): a shard of their own naming the album, with the
 * album's name and parent recorded and nothing encoded.
 */
internal fun addition(
    target: Shard,
    photos: List<PhotoRow>,
    state: AlbumState = AlbumState.UPLOADED,
    thumbsId: ObjectId? = blobId(),
): Shard = Shard(
    info = AlbumInfo(
        id = Uuid.random(),
        name = target.info.name,
        parent = target.info.parent,
        thumbsId = thumbsId,
        state = state,
        encodingVersion = 0,
        addedAt = fixtureEpoch,
        addsTo = target.info.id,
    ),
    photos = photos,
)

/**
 * A container and its children, matching INGEST.md's shape: containers hold no photos of their
 * own, and the XOR rule means a mixed album never exists.
 */
internal fun containerTree(
    name: String,
    children: List<String>,
    photosEach: Int = 3,
): List<Shard> {
    val container = album(name, photos = emptyList(), thumbsId = null)
    return listOf(container) + children.map { child ->
        album(
            child,
            parent = container.info.id,
            photos = (0 until photosEach).map { index ->
                photo(
                    "${child}_${index.toString().padStart(4, '0')}.jpg",
                    takenAt = fixtureEpoch + (index * 3600).seconds,
                )
            },
        )
    }
}

/**
 * A shard claiming a schema version this build does not support.
 *
 * Expressible directly, because the version travels in [AlbumInfo] rather than being stamped by
 * the writer — so the fixture is a value, not a hand-built database file.
 */
internal fun futureShard(
    directory: Path,
    version: Int = SHARD_SCHEMA_VERSION + 1,
    sourcePath: String? = "From The Future",
): Shard = Shard(
    AlbumInfo(
        id = Uuid.random(),
        name = "From The Future",
        sourcePath = sourcePath,
        thumbsId = null,
        addedAt = Instant.fromEpochSeconds(0),
        schemaVersion = version,
    ),
).also { it.writeTo(Path(directory, "${it.info.id}.db"), testDrivers) }

/**
 * A shard file with the `photo` table as it stood before schema 4: no `live_video_filename`.
 *
 * Hand-built with SQL, and it has to be. Every other fixture here is a value the writer turns
 * into a file, and the writer only knows the current shape — so the one thing that cannot be
 * expressed as a value is the very thing the read path has to cope with, since SQLite answers a
 * `SELECT` naming an absent column with an error rather than a null. The columns below are
 * schema 3's, verbatim.
 */
internal fun shardFromBeforeSchema4(
    directory: Path,
    name: String,
    photoName: String,
    id: Uuid = Uuid.random(),
    photoId: Uuid = Uuid.random(),
): Path {
    val path = Path(directory, "$id.db")
    val driver = path.openDriver(testDrivers, ShardDatabase.Schema, creating = true)
    try {
        driver.execute(null, "DROP TABLE photo", 0)
        driver.execute(
            null,
            """
            CREATE TABLE photo (
              id TEXT PRIMARY KEY, filename TEXT NOT NULL, source_filename TEXT,
              taken_at INTEGER, lat REAL, lon REAL, width INTEGER, height INTEGER,
              bytes INTEGER, source_bytes INTEGER, original_hash TEXT,
              media_type INTEGER NOT NULL, image_id TEXT, live_still_id TEXT,
              live_video_id TEXT, video_id TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO album_info (
              id, album_id, name, parent, source_path, cover_photo_id, thumbs_id,
              state, encoding_version, added_at, schema_version
            ) VALUES (
              1, '$id', '$name', NULL, '$name', NULL, '${blobId()}',
              'encoded', ${DerivativeSpec.ENCODING_VERSION}, ${fixtureEpoch.epochSeconds}, 3
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            INSERT INTO photo (
              id, filename, source_filename, taken_at, lat, lon, width, height, bytes,
              source_bytes, original_hash, media_type, image_id, live_still_id,
              live_video_id, video_id
            ) VALUES (
              '$photoId', '$photoName', NULL, ${fixtureEpoch.epochSeconds}, NULL, NULL,
              4000, 3000, 3145728, 64, NULL, ${MediaType.LIVE_PHOTO.code}, '${blobId()}',
              '${blobId()}', '${blobId()}', NULL
            )
            """.trimIndent(),
            0,
        )
    } finally {
        driver.close()
    }
    return path
}

private val temporaryDirectories = mutableListOf<Path>()

/** A scratch directory, unique per test and removed by [deleteTemporaryDirectories]. */
internal fun temporaryDirectory(label: String = "catalog"): Path =
    Path(scratchRoot, "photos-tests-$label-${Uuid.random()}")
        .also(SystemFileSystem::createDirectories)
        .also(temporaryDirectories::add)

/**
 * Removes every scratch directory made so far — what each suite's `@AfterTest` calls.
 *
 * Worth doing rather than trusting the operating system: `SystemTemporaryDirectory` is not
 * absolute on Kotlin/Native here, so a leaked directory lands in the working tree instead of in
 * `/tmp`, and a full run makes a hundred of them.
 */
internal fun deleteTemporaryDirectories() {
    for (directory in temporaryDirectories) directory.deleteRecursively()
    temporaryDirectories.clear()
}

private fun Path.deleteRecursively() {
    if (SystemFileSystem.metadataOrNull(this)?.isDirectory == true) {
        for (child in SystemFileSystem.list(this)) child.deleteRecursively()
    }
    SystemFileSystem.delete(this, mustExist = false)
}

internal fun Path.readBytes(): ByteArray =
    SystemFileSystem.source(this).buffered().use { it.readByteArray() }

// ------------------------------------------------------------------------------- the fake zone

/**
 * A zone that answers by *what was asked for*, not by call order.
 *
 * The storage suite's engine is a scripted queue, which suits testing one request's headers. The
 * sync loop issues a LIST and then a GET per changed shard, in an order that is the loop's
 * business rather than a test's — so this one is keyed by path instead, and these tests never
 * encode an ordering they do not care about.
 */
internal class FakeZone(private val clock: Clock = Clock.System) {

    private class Entry(val bytes: ByteArray, val etag: String, val lastModified: Instant)

    private val objects = mutableMapOf<String, Entry>()
    private var counter = 0

    /** Keys asked for by anything other than a LIST — what proves a no-op run fetched nothing. */
    val requestedKeys: MutableList<String> = mutableListOf()
    var listCount: Int = 0
        private set

    /** Writes and deletes the zone actually saw, so a no-op run can be shown to have made none. */
    var putCount: Int = 0
        private set
    var deleteCount: Int = 0
        private set

    val keys: List<String> get() = objects.keys.sorted()

    fun contains(key: String): Boolean = key in objects

    fun data(key: String): ByteArray? = objects[key]?.bytes

    fun put(key: String, bytes: ByteArray, etag: String) {
        objects[key] = Entry(bytes, etag, clock.now())
    }

    /**
     * An object with an age, so the orphan sweep's seven-day floor can be tested without waiting a
     * week (§7).
     */
    fun insert(key: String, bytes: ByteArray, age: Duration = Duration.ZERO) {
        counter++
        objects[key] = Entry(bytes, "e$counter", clock.now() - age)
    }

    fun remove(key: String) {
        objects.remove(key)
    }

    /**
     * One request at a time. Ingest deletes up to `DELETE_JOBS` keys at once, each on whatever
     * thread the engine picked, and [objects] is a plain map: unguarded, two removes could collide
     * and leave it holding a null key. The tests read [keys] between runs, never during one.
     */
    private val serving = Mutex()

    val engine: MockEngine = MockEngine { request -> serving.withLock { serve(request) } }

    private suspend fun MockRequestHandleScope.serve(request: HttpRequestData): HttpResponseData =
        if (request.url.parameters.contains("list-type")) {
            listCount++
            val prefix = request.url.parameters["prefix"].orEmpty()
            respond(
                content = listXml(objects.filterKeys { it.startsWith(prefix) }.entries.sortedBy { it.key }),
                status = HttpStatusCode.OK,
            )
        } else {
            // Path is /<zone>/<key>; the zone is the first segment.
            val key = request.url.encodedPath
                .removePrefix("/${testStorage.zone}/")
                .decodeURLPart()
            when (request.method) {
                HttpMethod.Put -> {
                    putCount++
                    // §2's single-owner rule is enforced here rather than assumed: a stale
                    // `If-Match` has to come back as a 412 for the conflict path to exist at all.
                    val ifMatch = request.headers[HttpHeaders.IfMatch]?.trim('"')
                    if (ifMatch != null && objects[key]?.etag != ifMatch) {
                        respond(content = "", status = HttpStatusCode.PreconditionFailed)
                    } else {
                        counter++
                        val etag = "put-$counter"
                        objects[key] = Entry(request.body.toByteArray(), etag, clock.now())
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = Headers.build { append("ETag", "\"$etag\"") },
                        )
                    }
                }

                HttpMethod.Delete -> {
                    deleteCount++
                    objects.remove(key)
                    respond(content = "", status = HttpStatusCode.NoContent)
                }

                else -> {
                    requestedKeys += key
                    val found = objects[key]
                    if (found == null) {
                        respond(
                            content = "<Error><Code>NoSuchKey</Code><Message>not here</Message></Error>",
                            status = HttpStatusCode.NotFound,
                        )
                    } else {
                        respond(
                            content = found.bytes,
                            status = HttpStatusCode.OK,
                            headers = Headers.build { append("ETag", "\"${found.etag}\"") },
                        )
                    }
                }
            }
        }

    private fun listXml(objects: List<Map.Entry<String, Entry>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""")
        append("<Name>my-photos</Name><EncodingType>url</EncodingType>")
        append("<IsTruncated>false</IsTruncated>")
        for ((key, value) in objects) {
            append("<Contents><Key>${key.replace("/", "%2F")}</Key>")
            append("<LastModified>${value.lastModified}</LastModified>")
            // bunny.net's directory markers come back with no ETag and Size 0 (§2).
            if (!key.endsWith("/")) append("<ETag>&quot;${value.etag}&quot;</ETag>")
            append("<Size>${value.bytes.size}</Size></Contents>")
        }
        append("</ListBucketResult>")
    }
}


internal fun zoneClient(engine: MockEngine): S3Client =
    S3Client(storage = testStorage, secretAccessKey = TEST_SECRET, http = HttpClient(engine))

/**
 * `EXPLAIN QUERY PLAN` for one statement, as its `detail` column.
 *
 * Through the raw driver rather than a generated query, because SQLDelight compiles queries
 * against the schema and `EXPLAIN` is not part of it.
 */
internal fun explainQueryPlan(path: Path, sql: String): List<String> {
    val driver = path.openDriver(
        testDrivers, MergedDatabase.Schema, creating = false, journal = Journal.WAL,
    )
    try {
        return driver.executeQuery(
            identifier = null,
            sql = "EXPLAIN QUERY PLAN $sql",
            mapper = { cursor ->
                val rows = mutableListOf<String>()
                while (cursor.next().value) rows += cursor.getString(3).orEmpty()
                QueryResult.Value(rows.toList())
            },
            parameters = 0,
        ).value
    } finally {
        driver.close()
    }
}

/**
 * A blob id for a fixture, shaped like the real thing: content-addressed, from a unique seed.
 *
 * `ObjectId.temporary()` would also type-check, but temp ids are §8's placeholders and no
 * `encoded` shard ever holds one — a fixture using them would be a state the system cannot
 * reach (§2).
 */
internal fun blobId(seed: String = Uuid.random().toString()): ObjectId =
    ObjectId.ofContent(seed.encodeToByteArray())
