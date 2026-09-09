package net.stho.photos.storage

import io.ktor.client.HttpClient
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import net.stho.photos.S3HttpFailure

/**
 * The tests that need a real server rather than a scripted one.
 *
 * `MockEngine` proves we send what we meant to; it cannot prove a server accepts it. These
 * cover the parts where the wire contract is the thing under test — conditional PUT, range
 * GET, paging, and the two file paths (`download`, multipart `put`) that §8's background
 * session depends on and that nothing offline exercises.
 *
 * The endpoint is supplied by the build, which owns S3Mock's lifecycle. Without java the
 * variable is absent and these skip, exactly as the Swift suite did — a machine that cannot run
 * a JVM should still be able to run the signer vectors and everything offline.
 *
 * S3Mock does **not** validate signatures. That is accepted and stated in
 * `Scripts/fetch-s3mock.sh`: the vendored AWS vector suite is what proves the signer, and it
 * names the failing stage where a server could only say pass or fail.
 */
class RoundTripTest {

    /** The full storage URL, bucket included — the build creates the bucket and names it. */
    private val endpoint: String? = environmentVariable("PHOTOS_S3MOCK_ENDPOINT")

    private fun client(
        multipartThreshold: Long = 64L * 1024 * 1024,
        multipartPartSize: Long = 16L * 1024 * 1024,
    ): S3Client? {
        val base = endpoint ?: return null
        return S3Client(
            storage = base.asStorageUrl(),
            secretAccessKey = "test-secret-key",
            http = HttpClient(),
            payloadSigning = S3Client.PayloadSigning.SIGNED,
            multipartThreshold = multipartThreshold,
            multipartPartSize = multipartPartSize,
        )
    }

    private fun key(suffix: String) = "round-trip/${Random.nextLong().toString(16)}-$suffix"

    private fun tempFile(name: String): Path =
        Path(SystemTemporaryDirectory, "photos-roundtrip-${Random.nextLong().toString(16)}-$name")

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun putHeadGetDelete() = runTest {
        val s3 = client() ?: return@runTest
        val k = key("basic")
        val payload = "hello".encodeToByteArray()

        assertTrue(s3.put(k, Body.Bytes(payload)) is PutResult.Written)

        val head = assertNotNull(s3.head(k), "HEAD should find what PUT wrote")
        assertEquals(payload.size.toLong(), head.size)

        val got = s3.get(k)
        assertTrue(got is GetResult.Content)
        assertContentEquals(payload, got.bytes)

        s3.delete(k)
        assertNull(s3.head(k), "HEAD should not find a deleted object")
    }

    /**
     * §2 makes every key a UUID, so this is no longer load-bearing — but §10 verified umlaut
     * keys round-trip against the live zone, and dropping the check would silently give that up.
     */
    @Test
    fun aKeyWithUmlautsRoundTrips() = runTest {
        val s3 = client() ?: return@runTest
        val k = key("Rauhöd-Grün")
        s3.put(k, Body.Bytes("x".encodeToByteArray()))

        assertNotNull(s3.head(k))
        val listed = s3.list(prefix = k).toList()
        assertEquals(listOf(k), listed.map { it.key })
    }

    // ---------------------------------------------------------------- conditional

    @Test
    fun conditionalGetReturnsNotModifiedWhenTheETagIsCurrent() = runTest {
        val s3 = client() ?: return@runTest
        val k = key("conditional-get")
        val written = s3.put(k, Body.Bytes("v1".encodeToByteArray()))
        val etag = assertNotNull((written as PutResult.Written).etag)

        assertEquals(GetResult.NotModified, s3.get(k, ifNoneMatch = etag))
    }

    /**
     * `If-Match` is what guards §2's single-owner shard rule: a stale ETag must not write.
     * §10 confirmed bunny.net honours it — this confirms we ask for it correctly.
     */
    @Test
    fun ifMatchOnPutWritesOnCurrentAndRefusesOnStale() = runTest {
        val s3 = client() ?: return@runTest
        val k = key("conditional-put")

        val first = s3.put(k, Body.Bytes("v1".encodeToByteArray())) as PutResult.Written
        val firstETag = assertNotNull(first.etag)

        val second = s3.put(k, Body.Bytes("v2".encodeToByteArray()), ifMatch = firstETag)
        assertTrue(second is PutResult.Written, "the current ETag should write")

        val stale = s3.put(k, Body.Bytes("v3".encodeToByteArray()), ifMatch = firstETag)
        assertEquals(PutResult.StaleETag, stale, "a stale ETag must not overwrite")

        val got = s3.get(k) as GetResult.Content
        assertContentEquals("v2".encodeToByteArray(), got.bytes, "v3 must not have landed")
    }

    @Test
    fun rangeGetReturnsExactlyTheRequestedBytes() = runTest {
        val s3 = client() ?: return@runTest
        val k = key("range")
        val payload = ByteArray(1024) { (it % 251).toByte() }
        s3.put(k, Body.Bytes(payload))

        val got = s3.get(k, range = 10L..19L) as GetResult.Content
        assertContentEquals(payload.copyOfRange(10, 20), got.bytes)
    }

    // ---------------------------------------------------------------- file paths

    /** §6 streams previews to disk; nothing offline covers this path. */
    @Test
    fun downloadStreamsAnObjectToAFile() = runTest {
        val s3 = client() ?: return@runTest
        val k = key("download")
        val payload = ByteArray(64 * 1024) { (it % 97).toByte() }
        s3.put(k, Body.Bytes(payload))

        val target = tempFile("download.bin")
        val etag = s3.download(k, target)
        assertNotNull(etag)

        val onDisk = SystemFileSystem.source(target).buffered().use { it.readByteArray() }
        assertContentEquals(payload, onDisk)
        SystemFileSystem.delete(target)
    }

    /** §8's background session uploads from files on disk, never from memory. */
    @Test
    fun uploadFromAFile() = runTest {
        val s3 = client() ?: return@runTest
        val k = key("upload-file")
        val source = tempFile("upload.txt")
        SystemFileSystem.sink(source).buffered().use { it.writeString("from a file") }

        s3.put(k, Body.File(source))

        val got = s3.get(k) as GetResult.Content
        assertContentEquals("from a file".encodeToByteArray(), got.bytes)
        SystemFileSystem.delete(source)
    }

    /** Above the threshold the upload splits into parts and the server reassembles them. */
    @Test
    fun aFileAboveTheThresholdUploadsInPartsAndReassemblesByteExact() = runTest {
        val s3 = client(multipartThreshold = 256 * 1024, multipartPartSize = 64 * 1024)
            ?: return@runTest
        val k = key("multipart")
        val payload = ByteArray(300 * 1024) { (it % 253).toByte() }

        val source = tempFile("multipart.bin")
        SystemFileSystem.sink(source).buffered().use { it.write(payload) }

        assertTrue(s3.put(k, Body.File(source)) is PutResult.Written)

        val head = assertNotNull(s3.head(k))
        assertEquals(payload.size.toLong(), head.size, "the reassembled object must be whole")

        val got = s3.get(k) as GetResult.Content
        assertContentEquals(payload, got.bytes, "reassembly must be byte-exact")
        SystemFileSystem.delete(source)
    }

    @Test
    fun aFileBelowTheThresholdTakesTheSinglePutPath() = runTest {
        val s3 = client(multipartThreshold = 1024 * 1024) ?: return@runTest
        val k = key("single-put")
        val source = tempFile("small.bin")
        SystemFileSystem.sink(source).buffered().use { it.write(ByteArray(1024) { i -> i.toByte() }) }

        s3.put(k, Body.File(source))
        assertEquals(1024L, assertNotNull(s3.head(k)).size)
        SystemFileSystem.delete(source)
    }

    // ---------------------------------------------------------------- listing and errors

    /** §4: one LIST on `meta/` *is* the sync plan, so it must carry ETag and size. */
    @Test
    fun listCarriesEveryObjectWithItsETagAndSize() = runTest {
        val s3 = client() ?: return@runTest
        val prefix = key("list-plan")
        repeat(3) { s3.put("$prefix/$it.db", Body.Bytes(ByteArray(it + 1))) }

        val listed = s3.list(prefix = prefix).toList().sortedBy { it.key }
        assertEquals(3, listed.size)
        listed.forEachIndexed { i, o ->
            assertEquals((i + 1).toLong(), o.size)
            assertNotNull(o.etag, "the diff needs an ETag on every entry")
        }
    }

    @Test
    fun pagingIsTransparentAcrossMoreObjectsThanFitInAPage() = runTest {
        val s3 = client() ?: return@runTest
        val prefix = key("paging")
        repeat(7) { s3.put("$prefix/$it", Body.Bytes(ByteArray(1))) }

        assertEquals(7, s3.list(prefix = prefix, maxKeysPerPage = 2).toList().size)
    }

    @Test
    fun anEmptyPrefixListsNothingRatherThanFailing() = runTest {
        val s3 = client() ?: return@runTest
        assertEquals(emptyList(), s3.list(prefix = key("nothing-here")).toList())
    }

    @Test
    fun aMissingObjectIsNullFromHeadAndAFailureFromGet() = runTest {
        val s3 = client() ?: return@runTest
        val k = key("absent")

        assertNull(s3.head(k))
        val failure = runCatching { s3.get(k) }.exceptionOrNull()
        assertTrue(failure is S3HttpFailure, "expected an S3 failure, got $failure")
        assertEquals(404, failure.status)
        // §1: never an opaque error — the status and the cause must reach the user.
        assertTrue(failure.userMessage.contains("404"), failure.userMessage)
    }
}
