package net.stho.photos.catalog

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.stho.photos.ports.SqlDrivers

internal actual val testDrivers: SqlDrivers = SqlDrivers { path, schema, creating, journal ->
    JdbcSqliteDriver("jdbc:sqlite:$path").apply {
        execute(null, "PRAGMA page_size = $PAGE_SIZE", 0)
        execute(null, "PRAGMA journal_mode = ${journal.name}", 0)
        if (creating && userVersion() == 0L) {
            schema.create(this).value
            execute(null, "PRAGMA user_version = ${schema.version}", 0)
        }
    }
}

private fun SqlDriver.userVersion(): Long =
    executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        parameters = 0,
    ).value ?: 0L
