package net.stho.photos.storage

import io.ktor.client.HttpClient
import io.ktor.client.content.ProgressListener
import io.ktor.client.request.header
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import net.stho.photos.S3HttpFailure

/**
 * A minimal S3 client for one bunny.net storage zone.
 *
 * Concrete methods for exactly the operations the design needs — no generic request layer. The
 * configuration is immutable and the Ktor client underneath is safe to share, so callers may
 * use one instance from as many concurrent coroutines as they like.
 *
 * The [http] client is supplied rather than built here: Ktor *is* the transport abstraction
 * (§7), which makes its engine the seam a test replaces and its retry plugin the whole retry
 * policy — see [retryStorageFailures].
 *
 * Deliberately absent: a concurrency limiter. §9 measured that more concurrent uploads makes
 * throughput slightly *worse*, so that knob belongs in the caller's worker pool where it can be
 * seen, not buried here.
 */
public class S3Client(
    public val storage: StorageUrl,
    secretAccessKey: String,
    private val http: HttpClient,
    public val payloadSigning: PayloadSigning = PayloadSigning.UNSIGNED,
    /**
     * Uploads at or above this size go through multipart. Ordinary photos and shards never
     * reach it; transcoded video does.
     */
    public val multipartThreshold: Long = 64L * 1024 * 1024,
    public val multipartPartSize: Long = 16L * 1024 * 1024,
    internal val clock: Clock = Clock.System,
) {

    /** On bunny.net the zone name is the access key ID (§1). */
    internal val signer: SigV4Signer = SigV4Signer(
        accessKeyId = storage.zone,
        secretAccessKey = secretAccessKey,
        region = storage.region,
        service = "s3",
        // S3 signs the path exactly as sent.
        normalizePath = false,
    )

    /**
     * How the `x-amz-content-sha256` header is filled for file bodies.
     *
     * **Verified against the live zone: bunny.net accepts `UNSIGNED-PAYLOAD` on
     * header-authenticated PUTs**, so that is the default — it saves a full read pass over every
     * uploaded byte, which across ~34 000 objects is a second pass over 120 GB competing with
     * the encoders.
     *
     * In-memory bodies are always hashed for real; they are small, and the pass costs nothing.
     * [SIGNED] remains available for a service that rejects it.
     */
    public enum class PayloadSigning { SIGNED, UNSIGNED }

    // ------------------------------------------------------------------ operations

    /** `null` when the object does not exist. */
    public suspend fun head(key: String): S3Object? {
        val response = send("HEAD", keyPath(key), accepting = setOf(200, 404), key = key)
        if (response.status.value != 200) return null
        return S3Object(
            key = key,
            size = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0,
            etag = response.headers[HttpHeaders.ETag]?.asETag(),
            lastModified = response.headers[HttpHeaders.LastModified]?.asHttpDate(),
        )
    }

    /**
     * Fetches an object, optionally a byte range, optionally conditional.
     *
     * A 304 comes back as [GetResult.NotModified] rather than a failure: it is the common case
     * in §4's sync diff, and burying the normal path inside a `catch` reads backwards and is
     * easy to swallow.
     */
    public suspend fun get(
        key: String,
        range: LongRange? = null,
        ifNoneMatch: ETag? = null,
    ): GetResult {
        val headers = buildList {
            // Inclusive at both ends, which is what the header means and what a `LongRange` is.
            if (range != null) add(HttpHeaders.Range to "bytes=${range.first}-${range.last}")
            if (ifNoneMatch != null) add(HttpHeaders.IfNoneMatch to ifNoneMatch.headerValue)
        }
        val response = send(
            "GET", keyPath(key), headers = headers,
            accepting = setOf(200, 206, 304), key = key,
        )
        if (response.status.value == 304) return GetResult.NotModified
        return GetResult.Content(response.bodyAsBytes(), response.headers[HttpHeaders.ETag]?.asETag())
    }

    /**
     * Streams an object to a file. Used for previews and originals, which have no reason to
     * pass through memory.
     */
    public suspend fun download(key: String, to: Path, progress: ProgressListener? = null): ETag? {
        val request = sign("GET", keyPath(key))
        return reachingZone {
            http.prepareRequest(request.url) {
                method = HttpMethod.Get
                request.headers.forEach { (name, value) -> header(name, value) }
                if (progress != null) onDownload(progress)
            }.execute { response ->
                if (response.status.value != 200) throw response.asFailure(key)
                SystemFileSystem.sink(to).buffered().use { sink ->
                    val channel = response.bodyAsChannel()
                    val chunk = ByteArray(1 shl 16)
                    while (true) {
                        val read = channel.readAvailable(chunk, 0, chunk.size)
                        if (read <= 0) break
                        sink.write(chunk, 0, read)
                    }
                }
                response.headers[HttpHeaders.ETag]?.asETag()
            }
        }
    }

    /**
     * Uploads an object, switching to multipart above [multipartThreshold].
     *
     * [ifMatch] guards §2's single-owner shard rule: a stale ETag yields [PutResult.StaleETag]
     * (HTTP 412), meaning "someone else wrote it — re-read and retry".
     */
    public suspend fun put(
        key: String,
        body: Body,
        contentType: String? = null,
        ifMatch: ETag? = null,
        progress: ProgressListener? = null,
    ): PutResult {
        val size = body.byteCount
        if (body is Body.File && size != null && size >= multipartThreshold && ifMatch == null) {
            return PutResult.Written(multipartUpload(key, body.path, size, contentType, progress))
        }

        val headers = buildList {
            if (contentType != null) add(HttpHeaders.ContentType to contentType)
            if (ifMatch != null) add(HttpHeaders.IfMatch to ifMatch.headerValue)
        }
        val response = send(
            "PUT", keyPath(key), headers = headers, body = body,
            accepting = setOf(200, 412), key = key, progress = progress,
        )
        if (response.status.value == 412) return PutResult.StaleETag
        return PutResult.Written(response.headers[HttpHeaders.ETag]?.asETag())
    }

    /**
     * Permanently removes an object. bunny.net has no versioning and no undelete, so this is
     * the single irreversible operation in the system (§7). The iOS app never calls it.
     */
    public suspend fun delete(key: String) {
        send("DELETE", keyPath(key), accepting = setOf(200, 204, 404), key = key)
    }

    // ------------------------------------------------------------------ keys and URLs

    /**
     * Builds the zone-relative request path for a key. Path-style, because bunny.net addresses
     * a zone as the first path segment rather than as a subdomain.
     */
    internal fun keyPath(key: String): String = "/${storage.zone}/${key.removePrefix("/")}"

    internal fun requestUrl(path: String, query: List<Pair<String, String>>): String = buildString {
        append(storage.endpoint)
        append(path.uriEncoded(encodeSlash = false))
        if (query.isNotEmpty()) append('?').append(query.canonicalQuery())
    }

    // ------------------------------------------------------------------ signing and sending

    /** A signed request, ready for the wire: URL, the headers to attach, and the payload. */
    internal class SignedRequest(
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: Body,
    )

    internal fun sign(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        headers: List<Pair<String, String>> = emptyList(),
        body: Body = Body.Empty,
    ): SignedRequest {
        val now = clock.now()
        val hash = when (body) {
            Body.Empty -> EMPTY_PAYLOAD_SHA256
            is Body.Bytes -> body.value.sha256Hex()
            is Body.File -> when (payloadSigning) {
                PayloadSigning.UNSIGNED -> UNSIGNED_PAYLOAD
                PayloadSigning.SIGNED -> body.path.sha256Hex()
            }
        }

        // Signed but not attached: the HTTP client derives `Host` from the URL and
        // `Content-Length` from the body, and a second copy of either is rejected or sent twice.
        // The values are the ones it will send, so the signature still matches.
        val derived = buildList {
            add(HttpHeaders.Host to storage.host)
            body.byteCount?.takeIf { it > 0 }?.let { add(HttpHeaders.ContentLength to it.toString()) }
        }
        val attached = buildList {
            add("X-Amz-Date" to now.amzDate())
            add("X-Amz-Content-Sha256" to hash)
            addAll(headers)
        }

        val canonical = signer.canonicalRequest(
            method = method,
            path = path,
            query = query,
            headers = derived + attached,
            payloadHash = hash,
        )
        val signature = signer.signature(signer.stringToSign(canonical.text, now), now)
        val authorization = "$AWS4_HMAC_SHA256 Credential=${signer.credential(now)}, " +
            "SignedHeaders=${canonical.signedHeaders}, Signature=$signature"

        return SignedRequest(
            url = requestUrl(path, query),
            headers = attached + (HttpHeaders.Authorization to authorization),
            body = body,
        )
    }

    /**
     * Signs, sends, and turns an unacceptable status into the failure §1 promises to show.
     *
     * Retry is not here: it is Ktor's `HttpRequestRetry` plugin on [http], which has already
     * exhausted its attempts by the time a response reaches this line.
     */
    internal suspend fun send(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        headers: List<Pair<String, String>> = emptyList(),
        body: Body = Body.Empty,
        accepting: Set<Int>,
        key: String? = null,
        progress: ProgressListener? = null,
    ): HttpResponse {
        val request = sign(method, path, query, headers, body)
        val response = reachingZone {
            http.request(request.url) {
                this.method = HttpMethod.parse(method)
                request.headers.forEach { (name, value) -> header(name, value) }
                when (val payload = request.body) {
                    Body.Empty -> Unit
                    is Body.Bytes -> setBody(payload.value)
                    is Body.File -> setBody(FileContent(payload.path))
                }
                if (progress != null) onUpload(progress)
            }
        }
        if (response.status.value !in accepting) throw response.asFailure(key)
        return response
    }

    private suspend fun HttpResponse.asFailure(key: String?): S3HttpFailure =
        bodyAsBytes().asS3HttpFailure(status.value, key, headers[AMZ_REQUEST_ID])
}

private const val AMZ_REQUEST_ID = "x-amz-request-id"

private val EMPTY_PAYLOAD_SHA256 = ByteArray(0).sha256Hex()

/**
 * Request payloads. [File] exists because §8's background uploads must come from a file on
 * disk, and because a 200 MB transcode should never sit in memory.
 */
public sealed interface Body {
    public data object Empty : Body

    public data class Bytes(public val value: ByteArray) : Body {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
    }

    public data class File(public val path: Path) : Body
}

/** Null when a file body's size cannot be read — the file is gone, or is not a file. */
public val Body.byteCount: Long?
    get() = when (this) {
        Body.Empty -> 0
        is Body.Bytes -> value.size.toLong()
        is Body.File -> SystemFileSystem.metadataOrNull(path)?.size
    }

/**
 * A file body, streamed rather than read into memory.
 *
 * [contentLength] is declared rather than left to chunked encoding on purpose: SigV4 signs
 * `Content-Length`, so a chunked upload would send no header for one that was signed and every
 * request would come back 403.
 */
private class FileContent(private val path: Path) : OutgoingContent.WriteChannelContent() {
    override val contentLength: Long? = SystemFileSystem.metadataOrNull(path)?.size

    override suspend fun writeTo(channel: ByteWriteChannel) {
        SystemFileSystem.source(path).buffered().use { source ->
            val chunk = ByteArray(1 shl 16)
            while (true) {
                val read = source.readAtMostTo(chunk, 0, chunk.size)
                if (read <= 0) break
                channel.writeFully(chunk, 0, read)
            }
        }
        channel.flushAndClose()
    }
}

private val HTTP_MONTHS =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * `Wed, 15 Jan 2026 10:30:00 GMT` — RFC 7231's one mandatory date format.
 *
 * Hand-parsed for the same reason EXIF dates are: the format is fixed and its zone is always
 * GMT, so a formatter would only add locale behaviour that has to be pinned anyway. Null when
 * the header is anything else, since a missing modification date costs nothing.
 */
internal fun String.asHttpDate(): Instant? {
    val parts = substringAfter(',').trim().split(' ').filter { it.isNotEmpty() }
    if (parts.size < 4) return null
    val day = parts[0].toIntOrNull() ?: return null
    val month = HTTP_MONTHS.indexOf(parts[1]) + 1
    if (month == 0) return null
    val year = parts[2].toIntOrNull() ?: return null
    val time = parts[3].split(':').mapNotNull(String::toIntOrNull)
    if (time.size != 3) return null
    return runCatching {
        LocalDateTime(year, month, day, time[0], time[1], time[2]).toInstant(TimeZone.UTC)
    }.getOrNull()
}
