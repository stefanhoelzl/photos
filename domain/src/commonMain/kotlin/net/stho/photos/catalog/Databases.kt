package net.stho.photos.catalog

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.JournalMode
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.ShardFailure
import net.stho.photos.model.MediaType
import net.stho.photos.storage.ETag

/**
 * How this module's four databases (§3) are opened, and how their columns become domain types.
 *
 * There is no query layer here and no wrapper around SQLite: SQLDelight's generated API and its
 * native driver *are* that layer. What is left is the handful of decisions the design makes
 * about the files themselves — journal mode, page size, and whether opening one may write to it.
 */

/** Every key in the zone is a UUID (§2), so every id column is one. */
internal val uuidAdapter: ColumnAdapter<Uuid, String> = object : ColumnAdapter<Uuid, String> {
    override fun decode(databaseValue: String): Uuid =
        runCatching { Uuid.parse(databaseValue) }
            .getOrElse { throw ShardFailure.Malformed("`$databaseValue` is not a uuid") }

    /** Lower-case hex-and-dash, which is what [Uuid.toString] gives and what the keys use. */
    override fun encode(value: Uuid): String = value.toString()
}

/**
 * Epoch seconds, which is the only resolution the catalog stores.
 *
 * EXIF dates have one-second resolution to begin with, and storing whole seconds is what makes a
 * round trip the identity — an in-memory value and the same value read back would otherwise
 * differ by a fraction, and "did this album change?" would depend on whether the value had been
 * through SQLite yet.
 */
internal val instantAdapter: ColumnAdapter<Instant, Long> = object : ColumnAdapter<Instant, Long> {
    override fun decode(databaseValue: Long): Instant = Instant.fromEpochSeconds(databaseValue)
    override fun encode(value: Instant): Long = value.epochSeconds
}

/** An unknown code is a malformed shard, not a silent default. */
internal val mediaTypeAdapter: ColumnAdapter<MediaType, Long> =
    object : ColumnAdapter<MediaType, Long> {
        override fun decode(databaseValue: Long): MediaType =
            MediaType.of(databaseValue.toInt())
                ?: throw ShardFailure.Malformed("unknown media_type $databaseValue")

        override fun encode(value: MediaType): Long = value.code.toLong()
    }

/** Stored unquoted, exactly as [ETag] holds it. */
internal val etagAdapter: ColumnAdapter<ETag, String> = object : ColumnAdapter<ETag, String> {
    override fun decode(databaseValue: String): ETag = ETag(databaseValue)
    override fun encode(value: ETag): String = value.value
}

/** §3's measured sizes are all at SQLite's own default page size; it is stated, not assumed. */
private const val PAGE_SIZE = 4096

/**
 * Opens the database at this path.
 *
 * [creating] is the whole distinction between the two kinds of file this module handles. A
 * merged database or a sync state is *ours*: we create it if it is not there. A shard or a
 * thumbnail pack arrives from the zone, and opening one must not write to it at all — not the
 * schema, not even a `user_version` — because the file may have been written by a schema this
 * build has never seen. `NO_VERSION_CHECK` is what suppresses both.
 *
 * [journalMode] defaults to `DELETE` because a shard and a thumbnail pack are *objects*: they
 * get uploaded, and WAL would leave half of one in a `-wal` sidecar that no PUT carries. Only
 * the two on-device databases ask for WAL, and §4 requires it of them — without it the 1–3 s
 * rebuild transaction blocks the album list that is on screen while it runs.
 */
internal fun Path.openDriver(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    creating: Boolean,
    journalMode: JournalMode = JournalMode.DELETE,
): SqlDriver {
    val directory = requireNotNull(parent) { "a database path needs a directory: $this" }
    if (creating) {
        SystemFileSystem.createDirectories(directory)
    } else if (!SystemFileSystem.exists(this)) {
        // SQLite would happily create an empty database here, and an empty one reads as a shard
        // with no `album_info` — which is §3's word for "this file is not a shard", not for
        // "there is no file". The two must not be confused.
        throw FileNotFoundException("no database at $this")
    }
    return NativeSqliteDriver(
        schema = schema,
        name = name,
        onConfiguration = { configuration ->
            configuration.copy(
                version = if (creating) configuration.version else NO_VERSION_CHECK,
                journalMode = journalMode,
                extendedConfig = configuration.extendedConfig.copy(
                    basePath = directory.toString(),
                    pageSize = PAGE_SIZE,
                ),
            )
        },
    )
}

/**
 * Whether this connection's file actually holds [table].
 *
 * Asked of `sqlite_master` through the raw driver rather than as a generated query, because
 * SQLDelight compiles queries against the schema it was given and `sqlite_master` is not in it.
 * The question only has to be asked at all because "this file is not a shard" must be
 * distinguishable from "SQLite is unwell" (§3): a truncated download, a wrong key or an
 * unrelated database is [ShardFailure.MissingAlbumInfo], never a raw SQLite error.
 */
internal fun SqlDriver.hasTable(table: String): Boolean =
    executeQuery(
        identifier = null,
        sql = "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
        },
        parameters = 1,
        binders = { bindString(0, table) },
    ).value
