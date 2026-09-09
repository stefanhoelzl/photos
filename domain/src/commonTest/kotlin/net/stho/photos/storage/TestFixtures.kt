package net.stho.photos.storage

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.write

/** One scripted exchange: what the server answers. */
internal data class Stub(
    val status: Int = 200,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: String = "",
)

/**
 * A scripted engine, so the logic that surrounds the network — retry, error mapping, LIST
 * paging, key encoding — can be tested without one.
 *
 * `MockEngine` *is* the seam the design asks for (§7): no transport port, no hand-written stub,
 * and `requestHistory` records what would have gone on the wire.
 */
internal fun stubEngine(vararg script: Stub): MockEngine {
    var index = 0
    return MockEngine { _ ->
        val stub = script.getOrNull(index++)
            ?: error("the stub ran out of scripted responses after ${script.size}")
        respond(
            content = stub.body.encodeToByteArray(),
            status = HttpStatusCode.fromValue(stub.status),
            headers = Headers.build {
                stub.headers.forEach { (name, value) -> append(name, value) }
            },
        )
    }
}

internal val testStorage: StorageUrl =
    "https://de-s3.storage.bunnycdn.com/my-photos".asStorageUrl()

internal const val TEST_SECRET: String = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"

/**
 * Retry is off unless a test asks for it, so a test that does not care about backoff does not
 * wait for one — the Swift suite's `RetryPolicy.none` by another name.
 */
internal fun testClient(
    engine: MockEngine,
    payloadSigning: S3Client.PayloadSigning = S3Client.PayloadSigning.SIGNED,
    retryDelay: Duration? = null,
    maxAttempts: Int = 4,
    clock: Clock = Clock.System,
): S3Client = S3Client(
    storage = testStorage,
    secretAccessKey = TEST_SECRET,
    http = HttpClient(engine) {
        if (retryDelay != null) {
            retryStorageFailures(
                maxAttempts = maxAttempts,
                baseDelay = retryDelay,
                maxDelay = retryDelay,
            )
        }
    },
    payloadSigning = payloadSigning,
    clock = clock,
)

/**
 * A client built the way production builds one — every default left alone — for the tests that
 * are about what those defaults are.
 */
internal fun defaultClient(engine: MockEngine): S3Client =
    S3Client(storage = testStorage, secretAccessKey = TEST_SECRET, http = HttpClient(engine))

/** The scripted equivalent of `RetryPolicy(maxAttempts:baseDelay:)` with a delay of nothing. */
internal val instantRetry: Duration = 1.milliseconds

/**
 * A clock stopped at the AWS suite's own timestamp: §7 injects a clock precisely so that a
 * presigned URL, whose signature covers the minute it was minted, can be asserted on at all.
 */
internal val fixedClock: Clock = object : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(1_440_938_160)
}

/** The URL of the nth request the engine was asked to make. */
internal fun MockEngine.sentUrl(index: Int = 0): String =
    requestHistory[index].url.toString()

internal fun MockEngine.sentHeader(name: String, index: Int = 0): String? =
    requestHistory[index].headers[name]

internal fun listXml(
    keys: List<Triple<String, Long, String>> = emptyList(),
    truncated: Boolean = false,
    nextToken: String? = null,
    encodingType: String? = "url",
): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""")
    append("""<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">""")
    append("<Name>my-photos</Name>")
    if (encodingType != null) append("<EncodingType>$encodingType</EncodingType>")
    append("<IsTruncated>$truncated</IsTruncated>")
    if (nextToken != null) append("<NextContinuationToken>$nextToken</NextContinuationToken>")
    for ((key, size, etag) in keys) {
        append("<Contents><Key>$key</Key>")
        append("<LastModified>2026-01-15T10:30:00.000Z</LastModified>")
        append("<ETag>&quot;$etag&quot;</ETag>")
        append("<Size>$size</Size></Contents>")
    }
    append("</ListBucketResult>")
}

/** A file of [bytes] 0x41s, in the platform's temporary directory. */
internal fun temporaryFile(bytes: Int, name: String): Path {
    val path = Path(SystemTemporaryDirectory, "photos-test-$name")
    SystemFileSystem.sink(path).buffered().use { it.write(ByteArray(bytes) { 0x41 }) }
    return path
}

internal fun Path.readAllBytes(): ByteArray =
    SystemFileSystem.source(this).buffered().use { it.readByteArray() }
