package net.stho.photos.adapter.ios

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
 * The `SqlDrivers` port over SQLiter, against **the platform's own SQLite** (§3).
 *
 * The configuration is the same one `:adapter:linux` builds, and the library underneath it is
 * not: the CLI links a pinned SQLite out of the imaging prefix because its glibc floor demands
 * it, while iOS 18 ships one far newer than §3's 2018 floor and there is nothing to bundle. So
 * the file has no `linkerOpts` behind it and the module's build script names no library —
 * SQLiter ships no `-lsqlite3` of its own precisely so the consumer can decide, and here the
 * decision is to decide nothing.
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
