package net.stho.photos.faces

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.IngestAbort
import net.stho.photos.storage.reachingZone
import net.stho.photos.storage.sha256Hex

/**
 * The two ONNX files faces are found and embedded with, and where they come from (§12).
 *
 * Fetched on first use rather than linked into the binary: 38 MB of weights would more than double
 * a 27 MB CLI, and a model is data the run can check for itself. Pinned by commit and by digest,
 * exactly like `:native`'s tarballs, so what a run analyses with is decided by this file and by
 * nothing a server says later.
 *
 * [VERSION] names the pair. Every scanned photo is recorded against it (see [FacesFile]), so
 * changing either model — the evaluation §12 defers the final choice to — is a new version, and
 * the next run scans the library again with it.
 */
public object FaceModels {
    public const val VERSION: String = "yunet-2023mar+sface-2021dec"

    /** opencv_zoo at this commit, fetched through GitHub's LFS media host. */
    private const val ZOO =
        "https://media.githubusercontent.com/media/opencv/opencv_zoo/47534e27c9851bb1128ccc0102f1145e27f23f98/models"

    public val DETECTOR: Model = Model(
        name = "face_detection_yunet_2023mar.onnx",
        url = "$ZOO/face_detection_yunet/face_detection_yunet_2023mar.onnx",
        sha256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
    )

    public val EMBEDDER: Model = Model(
        name = "face_recognition_sface_2021dec.onnx",
        url = "$ZOO/face_recognition_sface/face_recognition_sface_2021dec.onnx",
        sha256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
    )

    /**
     * The long edge detection runs at. Small faces in a group photograph survive it, and the
     * detector's cost grows with its input — the embedding is taken from the full decode anyway.
     */
    public const val DETECT_LONG_EDGE: Int = 1280

    /**
     * Below this the detector is guessing. No size filter sits beside it (§12): a stranger in the
     * background is the ignore verdict's business, not the detector's.
     */
    public const val MIN_SCORE: Float = 0.6f

    public class Model(public val name: String, public val url: String, public val sha256: String)
}

/** The models, on disk and verified. */
public class FaceModelFiles(public val detector: Path, public val embedder: Path)

/**
 * `<cache>/models/`, filled on first use.
 *
 * A model already there is trusted by name: it was verified on the way in and landed by
 * `atomicMove`, so a file with the right name is the right file. Anything else is fetched, checked
 * against its digest, and only then moved into place — a download cut short never looks like a
 * model.
 */
public class FaceModelStore(cacheRoot: Path, private val http: HttpClient) {

    public val directory: Path = Path(cacheRoot, DIRECTORY)

    /** Whether a run would have to fetch anything — what a dry run reports instead of fetching. */
    public fun missing(): List<FaceModels.Model> =
        listOf(FaceModels.DETECTOR, FaceModels.EMBEDDER).filterNot { SystemFileSystem.exists(path(it)) }

    /**
     * Both models, fetching whichever is missing.
     *
     * Throws [IngestAbort.FaceModelUnavailable] for an answer that is wrong — an HTTP error, a
     * digest that does not match — and `StorageUnreachableFailure` when nothing answered at all,
     * which is the same "not now" as an unreachable zone and defers the run the same way.
     */
    public suspend fun ensure(): FaceModelFiles {
        SystemFileSystem.createDirectories(directory)
        for (model in missing()) fetch(model)
        return FaceModelFiles(path(FaceModels.DETECTOR), path(FaceModels.EMBEDDER))
    }

    private fun path(model: FaceModels.Model): Path = Path(directory, model.name)

    private suspend fun fetch(model: FaceModels.Model) {
        val partial = Path(directory, "${model.name}.${Uuid.random()}.part")
        try {
            val status = reachingZone {
                http.prepareGet(model.url).execute { response ->
                    if (response.status.value == 200) {
                        SystemFileSystem.sink(partial).buffered().use { sink ->
                            val channel = response.bodyAsChannel()
                            val chunk = ByteArray(1 shl 16)
                            while (true) {
                                val read = channel.readAvailable(chunk, 0, chunk.size)
                                if (read <= 0) break
                                sink.write(chunk, 0, read)
                            }
                        }
                    }
                    response.status.value
                }
            }
            if (status != 200) throw IngestAbort.FaceModelUnavailable(model.name, "HTTP $status from ${model.url}")
            val digest = partial.sha256Hex()
            if (digest != model.sha256) {
                throw IngestAbort.FaceModelUnavailable(model.name, "sha256 $digest, expected ${model.sha256}")
            }
            SystemFileSystem.atomicMove(partial, path(model))
        } catch (cancelled: CancellationException) {
            partial.deleteQuietly()
            throw cancelled
        } catch (failure: Exception) {
            partial.deleteQuietly()
            throw failure
        }
    }

    public companion object {
        public const val DIRECTORY: String = "models"
    }
}

internal fun Path.deleteQuietly() {
    runCatching { SystemFileSystem.delete(this, mustExist = false) }
}
