package net.stho.photos.catalog

import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

/**
 * A reader of the merged database never writes to it (§3: one writer, many readers).
 *
 * On a first run the album list asks for albums while the sync's writer is still creating
 * `merged.db`. A reader opened as `creating` found `user_version` still 0, ran the schema itself,
 * and so needed the very write lock the writer held — "database is locked", measured on iOS as
 * `SQLiteExceptionErrorCode` from SQLiter's migration. JDBC runs the same DDL, so the race is
 * reproducible here, deterministically, by holding the writer's lock by hand.
 *
 * JVM-only because it holds that lock through a raw JDBC connection.
 */
class ReaderLockTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()

    @Test
    fun aReaderOpensWithoutWritingWhileTheWriterHoldsTheDatabase() {
        val path = Path(temporaryDirectory("reader-lock"), "merged.db")
        // The writer's moment before its schema lands: the file exists, is WAL, and is write-locked.
        DriverManager.getConnection("jdbc:sqlite:$path").use { writer ->
            writer.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
            writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }

            // Constructing the reader is the whole assertion: it must not need the lock.
            CatalogReader(path, testDrivers).close()

            writer.createStatement().use { it.execute("ROLLBACK") }
        }

        val version = DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { it.executeQuery("PRAGMA user_version").use { rows -> rows.getInt(1) } }
        }
        assertEquals(0, version, "a reader created no schema of its own")
    }
}
