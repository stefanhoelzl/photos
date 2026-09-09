package net.stho.photos.storage

import io.ktor.client.content.ProgressListener
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlin.math.max
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.MalformedResponseFailure
import net.stho.photos.S3HttpFailure

/** S3 requires every part except the last to be at least 5 MB. */
public const val MINIMUM_PART_SIZE: Long = 5L * 1024 * 1024

/** bunny.net caps an upload at 10 000 parts. */
public const val MAXIMUM_PART_COUNT: Long = 10_000

/**
 * Chooses a part size that satisfies both limits.
 *
 * Both failures are late and expensive: a part under 5 MB is rejected at
 * CompleteMultipartUpload, *after* the whole file has been transferred, and exceeding the part
 * cap fails near the end of a very large upload. Clamping here means a caller cannot configure
 * either mistake.
 */
public fun partSizeFor(fileSize: Long, preferred: Long): Long {
    val toFitPartCount = (fileSize + MAXIMUM_PART_COUNT - 1) / MAXIMUM_PART_COUNT
    return max(max(preferred, MINIMUM_PART_SIZE), toFitPartCount)
}

/**
 * Uploads a large file in parts.
 *
 * The largest objects here are transcoded videos, pushed over a domestic upstream where a
 * failed single PUT of 200 MB restarts from zero. bunny.net allows 10 000 parts; the part size
 * grows if a file would exceed that.
 *
 * Parts are uploaded one at a time on purpose: §9 measured that concurrent uploads make
 * throughput slightly worse, because the client's uplink is the bottleneck, not the service.
 */
internal suspend fun S3Client.multipartUpload(
    key: String,
    file: Path,
    size: Long,
    contentType: String?,
    progress: ProgressListener?,
): ETag? {
    val partSize = partSizeFor(size, multipartPartSize)
    val uploadId = createMultipartUpload(key, contentType)

    try {
        val parts = mutableListOf<Pair<Int, ETag>>()
        var uploaded = 0L
        var partNumber = 1

        SystemFileSystem.source(file).buffered().use { source ->
            while (uploaded < size) {
                val length = minOf(partSize, size - uploaded).toInt()
                val chunk = ByteArray(length)
                var filled = 0
                while (filled < length) {
                    val read = source.readAtMostTo(chunk, filled, length)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == 0) break

                parts += partNumber to uploadPart(
                    key, uploadId, partNumber,
                    if (filled == length) chunk else chunk.copyOf(filled),
                )
                uploaded += filled
                partNumber += 1
                progress?.onProgress(uploaded, size)
            }
        }

        return completeMultipartUpload(key, uploadId, parts)
    } catch (failure: Throwable) {
        // Leave no half-finished upload holding storage. A failure to abort is not worth
        // masking the original failure.
        runCatching { abortMultipartUpload(key, uploadId) }
        throw failure
    }
}

private suspend fun S3Client.createMultipartUpload(key: String, contentType: String?): String {
    val headers = buildList {
        if (contentType != null) add(HttpHeaders.ContentType to contentType)
    }
    val response = send(
        "POST", keyPath(key), query = listOf("uploads" to ""),
        headers = headers, accepting = setOf(200), key = key,
    )
    val uploadId = response.bodyAsBytes().decodeToString().xmlFields("UploadId")["UploadId"]
    if (uploadId.isNullOrEmpty()) {
        throw MalformedResponseFailure("CreateMultipartUpload returned no UploadId")
    }
    return uploadId
}

private suspend fun S3Client.uploadPart(
    key: String,
    uploadId: String,
    partNumber: Int,
    bytes: ByteArray,
): ETag {
    val response = send(
        "PUT", keyPath(key),
        query = listOf("partNumber" to partNumber.toString(), "uploadId" to uploadId),
        body = Body.Bytes(bytes), accepting = setOf(200), key = key,
    )
    val etag = response.headers[HttpHeaders.ETag]
        ?: throw MalformedResponseFailure("UploadPart $partNumber returned no ETag")
    return etag.asETag()
}

private suspend fun S3Client.completeMultipartUpload(
    key: String,
    uploadId: String,
    parts: List<Pair<Int, ETag>>,
): ETag? {
    val xml = buildString {
        append("<CompleteMultipartUpload>")
        for ((number, etag) in parts.sortedBy { it.first }) {
            append("<Part><PartNumber>").append(number).append("</PartNumber>")
            append("<ETag>").append(etag.headerValue).append("</ETag></Part>")
        }
        append("</CompleteMultipartUpload>")
    }

    val response = send(
        "POST", keyPath(key), query = listOf("uploadId" to uploadId),
        headers = listOf(HttpHeaders.ContentType to "application/xml"),
        body = Body.Bytes(xml.encodeToByteArray()), accepting = setOf(200), key = key,
    )

    // CompleteMultipartUpload can return 200 with an error document in the body.
    val fields = response.bodyAsBytes().decodeToString().xmlFields("Code", "Message", "ETag")
    fields["Code"]?.let { code ->
        throw S3HttpFailure(status = 200, code = code, detail = fields["Message"], key = key)
    }
    return fields["ETag"]?.asETag()
}

private suspend fun S3Client.abortMultipartUpload(key: String, uploadId: String) {
    send(
        "DELETE", keyPath(key), query = listOf("uploadId" to uploadId),
        accepting = setOf(200, 204, 404), key = key,
    )
}
