package net.stho.photos.adapter.linux

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.stho.photos.catalog.PAGE_SIZE
import net.stho.photos.ports.Journal
import net.stho.photos.ports.SqlDrivers

/**
 * The `SqlDrivers` port over JDBC, for the desktop app (§6).
 *
 * Nothing here resembles the SQLiter adapter, which is the point of the seam. The JVM driver
 * has no configuration object: everything §3 asks for is a PRAGMA on the open connection, and
 * `page_size` only takes effect before the first page is written — so it is issued first, ahead
 * of the journal mode and ahead of any schema.
 *
 * **The version rule is ours to implement here.** SQLiter's driver compares `user_version`
 * against the schema and creates or migrates by itself; the JDBC one does nothing unless asked.
 * So [creating] means *create the schema only if this file is empty, and migrate it if it is
 * older* — re-opening an existing database must not run the DDL a second time — and reading means never touching the version at
 * all, because a shard may carry a `schema_version` this build has never seen (§3).
 */
public class JdbcSqlDrivers : SqlDrivers {
    override fun open(
        path: String,
        schema: SqlSchema<QueryResult.Value<Unit>>,
        creating: Boolean,
        journal: Journal,
    ): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        driver.execute(null, "PRAGMA page_size = $PAGE_SIZE", 0)
        driver.execute(null, "PRAGMA journal_mode = ${journal.name}", 0)
        if (creating) {
            val version = driver.userVersion()
            if (version == 0L) {
                schema.create(driver).value
                driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
            } else if (version < schema.version) {
                // What SQLiter does by itself: a database of ours from an older build is brought
                // up to this one's schema, rather than read with columns it does not have.
                schema.migrate(driver, version, schema.version).value
                driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
            }
        }
        return driver
    }

    private fun SqlDriver.userVersion(): Long =
        executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            parameters = 0,
        ).value ?: 0L
}
