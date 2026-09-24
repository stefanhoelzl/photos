package net.stho.photos.faces

import app.cash.sqldelight.ColumnAdapter
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.catalog.openDriver
import net.stho.photos.catalog.uuidAdapter
import net.stho.photos.faces.file.Face
import net.stho.photos.faces.file.Faces_info
import net.stho.photos.faces.file.FacesDatabase
import net.stho.photos.faces.file.Scanned
import net.stho.photos.ports.SqlDrivers

/** What a face is to a verdict: the person it is, not them, or nobody worth naming (§12). */
public enum class VerdictKind(public val wire: String) {
    CONFIRMED("confirmed"),
    REJECTED("rejected"),
    IGNORED("ignored"),
    ;

    public companion object {
        public fun of(wire: String): VerdictKind? = entries.firstOrNull { it.wire == wire }
    }
}

internal val verdictKindAdapter: ColumnAdapter<VerdictKind, String> =
    object : ColumnAdapter<VerdictKind, String> {
        override fun decode(databaseValue: String): VerdictKind =
            VerdictKind.of(databaseValue) ?: error("unknown verdict kind '$databaseValue'")

        override fun encode(value: VerdictKind): String = value.wire
    }

/** A face as an album's faces file holds it: the detection, under an id of its own. */
public class StoredFace(
    public val id: Uuid,
    public val photoId: Uuid,
    public val face: DetectedFace,
)

/**
 * One album's faces: `faces/<album-uuid>.db` (§12).
 *
 * Plain data, read and written whole — like a thumbnail pack, the file is rewritten when anything
 * in it changes, and written in a fixed order so the same faces are the same bytes. That is what
 * lets a digest decide whether the zone already holds it.
 */
public class FacesFile(
    public val albumId: Uuid,
    public val modelVersion: String,
    /** Every photo analysed with [modelVersion], faces or not. */
    public val scanned: Set<Uuid>,
    public val faces: List<StoredFace>,
) {
    public fun write(to: Path, drivers: SqlDrivers) {
        SystemFileSystem.delete(to, mustExist = false)
        val driver = to.openDriver(drivers, FacesDatabase.Schema, creating = true)
        try {
            val queries = database(driver).facesQueries
            queries.transaction {
                queries.insertInfo(albumId, modelVersion, SCHEMA_VERSION.toLong())
                for (photo in scanned.sortedBy(Uuid::toString)) queries.insertScanned(photo)
                for (stored in faces.sortedBy { it.id.toString() }) {
                    val face = stored.face
                    queries.insertFace(
                        id = stored.id,
                        photo_id = stored.photoId,
                        x = face.box.x.toDouble(),
                        y = face.box.y.toDouble(),
                        w = face.box.width.toDouble(),
                        h = face.box.height.toDouble(),
                        score = face.score.toDouble(),
                        landmarks = face.landmarks.toLittleEndian(),
                        embedding = face.embedding.toLittleEndian(),
                    )
                }
            }
        } finally {
            driver.close()
        }
    }

    public companion object {
        /** No reader skips anything yet; the number is there for the first one that must. */
        public const val SCHEMA_VERSION: Int = 1

        /**
         * The file at [path], or null when it is not one this build can read — a newer schema, or
         * not a faces file at all. Either way the album is scanned again, which costs time and
         * loses nothing: faces are derived.
         */
        public fun read(path: Path, drivers: SqlDrivers): FacesFile? {
            val driver = runCatching { path.openDriver(drivers, FacesDatabase.Schema, creating = false) }
                .getOrNull() ?: return null
            try {
                val queries = database(driver).facesQueries
                val info = runCatching { queries.selectInfo().executeAsOneOrNull() }.getOrNull() ?: return null
                if (info.schema_version > SCHEMA_VERSION) return null
                val scanned = queries.selectScanned().executeAsList().toSet()
                val faces = queries.selectFaces().executeAsList().map { row ->
                    StoredFace(
                        id = row.id,
                        photoId = row.photo_id,
                        face = DetectedFace(
                            box = FaceBox(row.x.toFloat(), row.y.toFloat(), row.w.toFloat(), row.h.toFloat()),
                            landmarks = row.landmarks.toFloats(),
                            score = row.score.toFloat(),
                            embedding = row.embedding.toFloats(),
                        ),
                    )
                }
                return FacesFile(info.album_id, info.model_version, scanned, faces)
            } finally {
                driver.close()
            }
        }

        private fun database(driver: app.cash.sqldelight.db.SqlDriver) = FacesDatabase(
            driver,
            faceAdapter = Face.Adapter(idAdapter = uuidAdapter, photo_idAdapter = uuidAdapter),
            faces_infoAdapter = Faces_info.Adapter(album_idAdapter = uuidAdapter),
            scannedAdapter = Scanned.Adapter(photo_idAdapter = uuidAdapter),
        )
    }
}

/** Little-endian float32, the one layout both platforms read without asking. */
internal fun FloatArray.toLittleEndian(): ByteArray {
    val bytes = ByteArray(size * 4)
    for (i in indices) {
        val bits = this[i].toRawBits()
        bytes[i * 4] = bits.toByte()
        bytes[i * 4 + 1] = (bits ushr 8).toByte()
        bytes[i * 4 + 2] = (bits ushr 16).toByte()
        bytes[i * 4 + 3] = (bits ushr 24).toByte()
    }
    return bytes
}

internal fun ByteArray.toFloats(): FloatArray {
    val floats = FloatArray(size / 4)
    for (i in floats.indices) {
        val bits = (this[i * 4].toInt() and 0xff) or
            ((this[i * 4 + 1].toInt() and 0xff) shl 8) or
            ((this[i * 4 + 2].toInt() and 0xff) shl 16) or
            ((this[i * 4 + 3].toInt() and 0xff) shl 24)
        floats[i] = Float.fromBits(bits)
    }
    return floats
}
