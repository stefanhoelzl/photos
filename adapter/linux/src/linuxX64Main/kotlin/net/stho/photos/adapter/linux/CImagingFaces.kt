@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import net.stho.photos.faces.DetectedFace
import net.stho.photos.faces.FaceBox
import net.stho.photos.faces.FaceModelFiles
import net.stho.photos.faces.FaceModels
import photosimaging.pi_face_result
import photosimaging.pi_face_result_free
import photosimaging.pi_face_result_init
import cnames.structs.pi_faces
import photosimaging.pi_faces_close
import photosimaging.pi_faces_find
import photosimaging.pi_faces_open

/**
 * §12's detector and embedder, over OpenCV's dnn in the C shim.
 *
 * A network keeps per-inference state, so an opened pair serves one thread at a time. Rather than
 * tie one to each of ingest's worker threads, which this class cannot see, pairs are lent out and
 * handed back: the pool grows to however many workers ask at once — `--jobs` of them — and no
 * further. ~40 MB of weights each.
 */
public class CImagingFaces(private val models: FaceModelFiles) : AutoCloseable {

    private val idle = AtomicReference<List<CPointer<pi_faces>>>(emptyList())
    private val all = AtomicReference<List<CPointer<pi_faces>>>(emptyList())

    /** The faces in [image], boxes as fractions of its width and height. */
    public fun find(image: PixelImage): List<DetectedFace> {
        val engine = borrow()
        try {
            return memScoped {
                val result = alloc<pi_face_result>()
                pi_face_result_init(result.ptr)
                try {
                    imagingCall { err ->
                        pi_faces_find(engine, image.raw.ptr, FaceModels.DETECT_LONG_EDGE, FaceModels.MIN_SCORE, result.ptr, err)
                    }
                    val width = image.width.toFloat()
                    val height = image.height.toFloat()
                    val faces = result.faces
                    val embeddings = result.embeddings
                    val dims = result.dims
                    List(result.count) { i ->
                        val face = faces!![i]
                        DetectedFace(
                            box = FaceBox(face.x / width, face.y / height, face.w / width, face.h / height),
                            landmarks = FloatArray(10) { k ->
                                face.landmarks[k] / if (k % 2 == 0) width else height
                            },
                            score = face.score,
                            embedding = FloatArray(dims) { k -> embeddings!![i * dims + k] },
                        )
                    }
                } finally {
                    pi_face_result_free(result.ptr)
                }
            }
        } finally {
            giveBack(engine)
        }
    }

    private fun borrow(): CPointer<pi_faces> {
        while (true) {
            val free = idle.value
            if (free.isEmpty()) return open()
            if (idle.compareAndSet(free, free.drop(1))) return free.first()
        }
    }

    private fun giveBack(engine: CPointer<pi_faces>) {
        while (true) {
            val free = idle.value
            if (idle.compareAndSet(free, free + engine)) return
        }
    }

    private fun open(): CPointer<pi_faces> {
        val engine = memScoped {
            val out = alloc<CPointerVar<pi_faces>>()
            imagingCall { err -> pi_faces_open(models.detector.toString(), models.embedder.toString(), out.ptr, err) }
            out.value!!
        }
        while (true) {
            val known = all.value
            if (all.compareAndSet(known, known + engine)) return engine
        }
    }

    override fun close() {
        for (engine in all.getAndSet(emptyList())) pi_faces_close(engine)
        idle.value = emptyList()
    }
}
