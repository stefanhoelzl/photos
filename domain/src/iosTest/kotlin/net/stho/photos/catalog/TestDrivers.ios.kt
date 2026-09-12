package net.stho.photos.catalog

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.NO_VERSION_CHECK
import kotlinx.io.files.Path
import net.stho.photos.ports.Journal
import net.stho.photos.ports.SqlDrivers

/**
 * The same SQLiter configuration the linuxX64 actual uses — over a **different SQLite**.
 *
 * That difference is the point, and it is what §10 left open: the CLI links a pinned library
 * out of the imaging prefix, while iOS takes the platform's own (§3). Running §3's catalog
 * rules here is the only thing that can show the two agree, so this is less a duplicate of the
 * linuxX64 actual than the other half of the comparison.
 */
internal actual val testDrivers: SqlDrivers = SqlDrivers { path, schema, creating, journal ->
    val file = Path(path)
    NativeSqliteDriver(
        schema = schema,
        name = file.name,
        onConfiguration = { configuration ->
            configuration.copy(
                version = if (creating) configuration.version else NO_VERSION_CHECK,
                journalMode = if (journal == Journal.WAL) JournalMode.WAL else JournalMode.DELETE,
                extendedConfig = configuration.extendedConfig.copy(
                    basePath = requireNotNull(file.parent).toString(),
                    pageSize = PAGE_SIZE,
                ),
            )
        },
    )
}
