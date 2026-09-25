package net.stho.photos.faces

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.testDrivers
import net.stho.photos.ports.Journal
import net.stho.photos.scratchPath

/** A faces file an older build wrote is read as none — its album is scanned again — not a crash. */
class FacesFileTest {

    private val root = scratchPath("faces-file")
    private val file = Path(root, "old.db")

    @AfterTest
    fun clean() {
        runCatching { SystemFileSystem.delete(file) }
        runCatching { SystemFileSystem.delete(root) }
    }

    /** The first schema, before `face.sharpness`, as the first build wrote it. */
    private object FirstSchema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE faces_info (id INTEGER PRIMARY KEY CHECK (id = 1), album_id TEXT NOT NULL, model_version TEXT NOT NULL, schema_version INTEGER NOT NULL)", 0)
            driver.execute(null, "CREATE TABLE scanned (photo_id TEXT PRIMARY KEY)", 0)
            driver.execute(null, "CREATE TABLE face (id TEXT PRIMARY KEY, photo_id TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, w REAL NOT NULL, h REAL NOT NULL, score REAL NOT NULL, landmarks BLOB NOT NULL, embedding BLOB NOT NULL)", 0)
            driver.execute(null, "INSERT INTO faces_info VALUES (1, '${Uuid.random()}', 'yunet-2023mar+sface-2021dec', 1)", 0)
            driver.execute(null, "INSERT INTO face VALUES ('${Uuid.random()}', '${Uuid.random()}', 0.1, 0.1, 0.2, 0.2, 0.9, x'00', x'00')", 0)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Unit
    }

    @Test
    fun aFileFromBeforeTheSharpnessColumnReadsAsNone() {
        SystemFileSystem.createDirectories(root)
        testDrivers.open(file.toString(), FirstSchema, creating = true, journal = Journal.DELETE).close()
        assertNull(FacesFile.read(file, testDrivers))
    }
}
