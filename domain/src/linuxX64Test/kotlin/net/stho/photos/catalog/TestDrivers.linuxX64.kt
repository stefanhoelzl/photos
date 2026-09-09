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

internal actual val testDrivers: SqlDrivers = SqlDrivers { path, schema, creating, journal ->
    nativeDriver(path, schema, creating, journal)
}

private fun nativeDriver(
    path: String,
    schema: SqlSchema<QueryResult.Value<Unit>>,
    creating: Boolean,
    journal: Journal,
): SqlDriver {
    val file = Path(path)
    return NativeSqliteDriver(
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
