package net.stho.photos.faces

import app.cash.sqldelight.db.SqlDriver
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.instantAdapter
import net.stho.photos.catalog.openDriver
import net.stho.photos.catalog.uuidAdapter
import net.stho.photos.faces.index.Index_info
import net.stho.photos.faces.index.Indexed_face
import net.stho.photos.faces.index.PeopleIndexDatabase
import net.stho.photos.faces.index.PeopleIndexQueries
import net.stho.photos.ports.Journal
import net.stho.photos.ports.SqlDrivers

/** One face as the index states it — what the viewer lists, before its own verdicts are laid on. */
public data class IndexEntry(
    public val faceId: Uuid,
    public val albumId: Uuid,
    public val photoId: Uuid,
    public val box: FaceBox,
    public val score: Float,
    /** The verdict it carried when the index was built. */
    public val verdict: VerdictKind?,
    /** Confirmed as, or else suggested as. */
    public val personId: Uuid?,
    public val suggested: Boolean,
    public val similarity: Float?,
    /** Its unknown group, or null. */
    public val group: Int?,
)

/**
 * `people_index.db` in the CLI's cache (§12): every face in the library, what the verdicts say
 * about it, and what `sync` suggests.
 *
 * Written only by `sync`, under its lock; read by the viewer without one, which is why it is WAL
 * and rebuilt inside a single transaction — the viewer sees the old index or the new one, never a
 * half of each.
 */
public class PeopleIndex(public val path: Path, private val drivers: SqlDrivers) {

    public val exists: Boolean get() = SystemFileSystem.exists(path)

    /** The model the index on disk was built with, or null when there is none. */
    public fun modelVersion(): String? = if (!exists) null else using(creating = true) { queries ->
        queries.selectInfo().executeAsOneOrNull()?.model_version
    }

    /** The inputs digest of the index on disk, or null when there is none worth keeping. */
    public fun inputs(): String? = if (!exists) null else using(creating = true) { queries ->
        queries.selectInfo().executeAsOneOrNull()
            ?.takeIf { it.model_version == FaceModels.VERSION }
            ?.inputs
    }

    public fun rebuild(entries: List<IndexEntry>, inputs: String, builtAt: Instant): Unit =
        using(creating = true) { queries ->
            queries.transaction {
                queries.clearFaces()
                for (entry in entries.sortedBy { it.faceId.toString() }) {
                    queries.insertFace(
                        face_id = entry.faceId,
                        album_id = entry.albumId,
                        photo_id = entry.photoId,
                        x = entry.box.x.toDouble(),
                        y = entry.box.y.toDouble(),
                        w = entry.box.width.toDouble(),
                        h = entry.box.height.toDouble(),
                        score = entry.score.toDouble(),
                        verdict = entry.verdict,
                        person_id = entry.personId,
                        suggested = entry.suggested,
                        similarity = entry.similarity?.toDouble(),
                        cluster_id = entry.group?.toLong(),
                    )
                }
                queries.replaceInfo(FaceModels.VERSION, inputs, Instant.fromEpochSeconds(builtAt.epochSeconds))
            }
        }

    /** Every face, for the viewer. Empty when `sync` has not built an index yet. */
    public fun read(): List<IndexEntry> {
        if (!exists) return emptyList()
        return using(creating = false) { queries ->
            queries.selectFaces().executeAsList().map { row ->
                IndexEntry(
                    faceId = row.face_id,
                    albumId = row.album_id,
                    photoId = row.photo_id,
                    box = FaceBox(row.x.toFloat(), row.y.toFloat(), row.w.toFloat(), row.h.toFloat()),
                    score = row.score.toFloat(),
                    verdict = row.verdict,
                    personId = row.person_id,
                    suggested = row.suggested,
                    similarity = row.similarity?.toFloat(),
                    group = row.cluster_id?.toInt(),
                )
            }
        }
    }

    /** Key → digest, for everything `sync` has put under `faces/` and `people/`. */
    public fun uploaded(): Map<String, String> = using(creating = true) { queries ->
        queries.selectUploaded().executeAsList().associate { it.key to it.sha256 }
    }

    public fun recordUploaded(key: String, sha256: String): Unit =
        using(creating = true) { it.recordUploaded(key, sha256) }

    public fun forgetUploaded(key: String): Unit = using(creating = true) { it.forgetUploaded(key) }

    private fun <T> using(creating: Boolean, block: (PeopleIndexQueries) -> T): T {
        val driver = path.openDriver(drivers, PeopleIndexDatabase.Schema, creating = creating, journal = Journal.WAL)
        try {
            return block(database(driver).peopleIndexQueries)
        } finally {
            driver.close()
        }
    }

    public companion object {
        public const val FILENAME: String = "people_index.db"

        private fun database(driver: SqlDriver) = PeopleIndexDatabase(
            driver,
            index_infoAdapter = Index_info.Adapter(built_atAdapter = instantAdapter),
            indexed_faceAdapter = Indexed_face.Adapter(
                face_idAdapter = uuidAdapter,
                album_idAdapter = uuidAdapter,
                photo_idAdapter = uuidAdapter,
                verdictAdapter = verdictKindAdapter,
                person_idAdapter = uuidAdapter,
            ),
        )
    }
}
