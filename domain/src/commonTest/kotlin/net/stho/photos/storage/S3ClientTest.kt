package net.stho.photos.storage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.S3HttpFailure

class S3ClientKeyTest {

    @Test
    fun addressesTheZoneAsTheFirstPathSegment() = runTest {
        val engine = stubEngine(Stub(status = 200))
        testClient(engine).head("meta/Trips/Iceland.db")
        assertEquals(
            "https://de-s3.storage.bunnycdn.com/my-photos/meta/Trips/Iceland.db",
            engine.sentUrl(),
        )
    }

    @Test
    fun percentEncodesKeyCharactersButKeepsSlashes() = runTest {
        val engine = stubEngine(Stub(status = 200))
        testClient(engine).head("meta/Trips 2024/a+b&c.db")
        assertTrue(
            engine.sentUrl().contains("/meta/Trips%202024/a%2Bb%26c.db"),
            engine.sentUrl(),
        )
    }

    @Test
    fun signsEveryRequestForTheRightScope() = runTest {
        val engine = stubEngine(Stub(status = 200))
        testClient(engine).head("meta/a.db")

        val authorization = assertNotNull(engine.sentHeader("Authorization"))
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 "), authorization)
        // On bunny.net the zone name is the access key ID (§1).
        assertTrue(authorization.contains("Credential=my-photos/"), authorization)
        assertTrue(authorization.contains("/de/s3/aws4_request"), authorization)
        assertTrue(
            authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"),
            authorization,
        )
    }
}

class PayloadSigningTest {

    /**
     * Confirmed against the live zone: bunny.net accepts UNSIGNED-PAYLOAD on
     * header-authenticated PUTs, which is what lets a 120 GB import skip a second pass over
     * every byte.
     */
    @Test
    fun uploadsFileBodiesWithUnsignedPayloadByDefault() = runTest {
        val file = temporaryFile(1024, "unsigned-default")
        try {
            val engine = stubEngine(Stub(headers = listOf("etag" to "\"e\"")))
            defaultClient(engine).put("originals/big.mp4", Body.File(file))
            assertEquals(UNSIGNED_PAYLOAD, engine.sentHeader("x-amz-content-sha256"))
        } finally {
            SystemFileSystem.delete(file, mustExist = false)
        }
    }

    @Test
    fun alwaysHashesAnInMemoryBodyForReal() = runTest {
        val payload = "shard".encodeToByteArray()
        val engine = stubEngine(Stub(headers = listOf("etag" to "\"e\"")))
        defaultClient(engine).put("meta/a.db", Body.Bytes(payload))
        assertEquals(payload.sha256Hex(), engine.sentHeader("x-amz-content-sha256"))
    }

    @Test
    fun signedModeHashesTheFile() = runTest {
        val file = temporaryFile(4096, "signed-mode")
        try {
            val engine = stubEngine(Stub(headers = listOf("etag" to "\"e\"")))
            testClient(engine, payloadSigning = S3Client.PayloadSigning.SIGNED)
                .put("originals/big.mp4", Body.File(file))
            assertEquals(file.sha256Hex(), engine.sentHeader("x-amz-content-sha256"))
        } finally {
            SystemFileSystem.delete(file, mustExist = false)
        }
    }

    @Test
    fun hashesAFileInChunksToTheSameDigestAsInOneGo() {
        // Spans several of the 1 MB chunks the file hasher reads.
        val file = temporaryFile(3 * 1024 * 1024 + 17, "chunked")
        try {
            assertEquals(file.readAllBytes().sha256Hex(), file.sha256Hex())
        } finally {
            SystemFileSystem.delete(file, mustExist = false)
        }
    }
}

class S3ClientStatusTest {

    @Test
    fun reportsAMissingObjectAsNullRatherThanAFailure() = runTest {
        assertNull(testClient(stubEngine(Stub(status = 404))).head("meta/gone.db"))
    }

    @Test
    fun parsesSizeETagAndLastModifiedFromHead() = runTest {
        val engine = stubEngine(
            Stub(
                headers = listOf(
                    "content-length" to "32768",
                    "etag" to "\"d41d8cd98f00b204e9800998ecf8427e\"",
                    "last-modified" to "Wed, 15 Jan 2026 10:30:00 GMT",
                ),
            ),
        )
        val object_ = assertNotNull(testClient(engine).head("meta/a.db"))
        assertEquals(32768, object_.size)
        assertEquals(ETag("d41d8cd98f00b204e9800998ecf8427e"), object_.etag)
        assertNotNull(object_.lastModified)
    }

    /** 304 is a value, because it is the common case in §4's sync diff. */
    @Test
    fun returnsNotModifiedAsAValue() = runTest {
        val engine = stubEngine(Stub(status = 304))
        val result = testClient(engine).get("meta/a.db", ifNoneMatch = ETag("abc"))
        assertEquals(GetResult.NotModified, result)
        assertEquals("\"abc\"", engine.sentHeader("If-None-Match"))
    }

    /** 412 is a value, because the caller must re-read before trying again. */
    @Test
    fun returnsAStaleETagAsAValue() = runTest {
        val engine = stubEngine(Stub(status = 412))
        val result = testClient(engine)
            .put("meta/a.db", Body.Bytes("x".encodeToByteArray()), ifMatch = ETag("old"))
        assertEquals(PutResult.StaleETag, result)
        assertEquals("\"old\"", engine.sentHeader("If-Match"))
    }

    @Test
    fun asksForAnInclusiveByteRangeAndAccepts206() = runTest {
        val engine = stubEngine(Stub(status = 206, body = "partial"))
        val result = testClient(engine).get("preview/a.heic", range = 0L..1023L)
        assertEquals("partial", assertNotNull(result.asBytes).decodeToString())
        assertEquals("bytes=0-1023", engine.sentHeader("Range"))
    }
}

class S3ClientErrorTest {

    @Test
    fun turnsAnXmlErrorBodyIntoAMessageThatNamesStatusAndCause() = runTest {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Error><Code>AccessDenied</Code><Message>Access Denied</Message><Key>meta/a.db</Key><RequestId>ABC123</RequestId></Error>
        """.trimIndent()

        val failure = assertFailsWith<S3HttpFailure> {
            testClient(stubEngine(Stub(status = 403, body = body))).get("meta/a.db")
        }
        assertEquals(403, failure.status)
        assertEquals("AccessDenied", failure.code)
        assertEquals("ABC123", failure.requestId)
        assertEquals("403 Forbidden — AccessDenied: Access Denied (meta/a.db)", failure.message)
        // §1: never an opaque error — the message must be actionable.
        assertTrue(failure.userMessage.contains("log out and check the password"))
    }

    @Test
    fun stillYieldsTheStatusWhenTheBodyIsMissingOrNotXml() {
        val empty = ByteArray(0).asS3HttpFailure(503, key = "meta/a.db")
        assertEquals(503, empty.status)
        assertTrue(empty.message.startsWith("503 Service Unavailable"), empty.message)

        val junk = "<<not xml".encodeToByteArray().asS3HttpFailure(500)
        assertEquals(500, junk.status)
    }
}

/**
 * Retry is Ktor's `HttpRequestRetry` plugin rather than a policy of our own, so these tests
 * assert the *configuration* — which statuses come back for another try, and how many.
 */
class RetryTest {

    @Test
    fun retriesServerErrorsAndThenSucceeds() = runTest {
        val engine = stubEngine(
            Stub(status = 503),
            Stub(status = 503),
            Stub(status = 200, headers = listOf("etag" to "\"ok\"")),
        )
        val result = testClient(engine, retryDelay = instantRetry).get("meta/a.db")
        assertNotNull(result.asBytes)
        assertEquals(3, engine.requestHistory.size)
    }

    /** A wrong password fails immediately: repeating it only delays §1's message. */
    @Test
    fun neverRetriesClientErrors() = runTest {
        val engine = stubEngine(Stub(status = 403), Stub(status = 200))
        val client = testClient(engine, retryDelay = instantRetry)
        assertFailsWith<S3HttpFailure> { client.get("meta/a.db") }
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun retriesARateLimit() = runTest {
        val engine = stubEngine(
            Stub(status = 429, headers = listOf("retry-after" to "0")),
            Stub(status = 200),
        )
        testClient(engine, retryDelay = instantRetry).get("meta/a.db")
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun retriesATransportFailure() = runTest {
        // Counted here rather than from `requestHistory`, which only records exchanges that
        // produced a response — and the point of this test is the one that did not.
        var attempts = 0
        val engine = MockEngine {
            attempts += 1
            if (attempts == 1) throw IOException("connection dropped")
            respond("")
        }
        testClient(engine, retryDelay = instantRetry).get("meta/a.db")
        assertEquals(2, attempts)
    }

    @Test
    fun boundsTheNumberOfAttempts() = runTest {
        val engine = stubEngine(*Array(10) { Stub(status = 500) })
        val client = testClient(engine, retryDelay = instantRetry, maxAttempts = 4)
        assertFailsWith<S3HttpFailure> { client.get("meta/a.db") }
        // The first try plus three retries.
        assertEquals(4, engine.requestHistory.size)
    }

    @Test
    fun leavesA412ToTheCallerRatherThanRetryingIt() = runTest {
        val engine = stubEngine(Stub(status = 412))
        val result = testClient(engine, retryDelay = instantRetry)
            .put("meta/a.db", Body.Bytes("x".encodeToByteArray()), ifMatch = ETag("old"))
        assertEquals(PutResult.StaleETag, result)
        assertEquals(1, engine.requestHistory.size)
    }
}
