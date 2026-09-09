package net.stho.photos.adapter.linux

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.NO_VERSION_CHECK
import kotlinx.io.files.Path
import net.stho.photos.catalog.PAGE_SIZE
import net.stho.photos.ports.Journal
import net.stho.photos.ports.SqlDrivers

/**
 * The `SqlDrivers` port over SQLiter, which is SQLDelight's Kotlin/Native driver.
 *
 * SQLiter takes a directory plus a name rather than a path, and expresses §3's PRAGMAs through
 * a configuration object — so the shape of this adapter is nothing like the JDBC one, which is
 * exactly why the seam exists.
 *
 * `NO_VERSION_CHECK` is what lets a shard be *read* without touching it: SQLDelight would
 * otherwise compare `user_version` against the schema it was compiled with and migrate, and a
 * shard may carry a `schema_version` this build has never seen (§3).
 */
public class NativeSqlDrivers : SqlDrivers {
    override fun open(
        path: String,
        schema: SqlSchema<QueryResult.Value<Unit>>,
        creating: Boolean,
        journal: Journal,
    ): SqlDriver {
        val file = Path(path)
        val directory = requireNotNull(file.parent) { "a database path needs a directory: $path" }
        return NativeSqliteDriver(
            schema = schema,
            name = file.name,
            onConfiguration = { configuration ->
                configuration.copy(
                    version = if (creating) configuration.version else NO_VERSION_CHECK,
                    journalMode = when (journal) {
                        Journal.DELETE -> JournalMode.DELETE
                        Journal.WAL -> JournalMode.WAL
                    },
                    extendedConfig = configuration.extendedConfig.copy(
                        basePath = directory.toString(),
                        pageSize = PAGE_SIZE,
                    ),
                )
            },
        )
    }
}
