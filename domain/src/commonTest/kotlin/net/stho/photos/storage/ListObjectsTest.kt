package net.stho.photos.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import net.stho.photos.MalformedResponseFailure

/**
 * §4 calls one LIST on `meta/` the entire sync plan, and §2 warns that a single bad key could
 * corrupt the parse. These are the tests for that claim.
 */
class ListObjectsTest {

    @Test
    fun yieldsEveryObjectOnAPageWithItsETagAndSize() = runTest {
        val engine = stubEngine(
            Stub(
                body = listXml(
                    listOf(
                        Triple("meta/Iceland.db", 32768L, "abc"),
                        Triple("meta/Trips/Norway.db", 88000L, "def"),
                    ),
                ),
            ),
        )
        val objects = testClient(engine).list(prefix = "meta/").toList()

        assertEquals(2, objects.size)
        assertEquals("meta/Iceland.db", objects[0].key)
        assertEquals(32768, objects[0].size)
        assertEquals(ETag("abc"), objects[0].etag)
        assertEquals("meta/Trips/Norway.db", objects[1].key)
    }

    @Test
    fun asksForV2UrlEncodingAndThePrefix() = runTest {
        val engine = stubEngine(Stub(body = listXml()))
        testClient(engine).list(prefix = "meta/").toList()

        val url = engine.sentUrl()
        assertTrue(url.contains("list-type=2"), url)
        assertTrue(url.contains("encoding-type=url"), url)
        assertTrue(url.contains("prefix=meta%2F"), url)
        // LIST addresses the zone itself, not a key under it.
        assertTrue(url.startsWith("https://de-s3.storage.bunnycdn.com/my-photos?"), url)
    }

    @Test
    fun pagesTransparently() = runTest {
        val engine = stubEngine(
            Stub(
                body = listXml(
                    listOf(Triple("meta/a.db", 1L, "a"), Triple("meta/b.db", 2L, "b")),
                    truncated = true,
                    nextToken = "TOKEN1",
                ),
            ),
            Stub(body = listXml(listOf(Triple("meta/c.db", 3L, "c")))),
        )
        val objects = testClient(engine).list(prefix = "meta/").toList()

        assertEquals(listOf("meta/a.db", "meta/b.db", "meta/c.db"), objects.map { it.key })
        assertEquals(2, engine.requestHistory.size)
        assertTrue(engine.sentUrl(1).contains("continuation-token=TOKEN1"), engine.sentUrl(1))
    }

    @Test
    fun stopsOnATruncatedPageWithNoTokenRatherThanLoopingForever() = runTest {
        val engine = stubEngine(
            Stub(body = listXml(listOf(Triple("meta/a.db", 1L, "a")), truncated = true)),
        )
        assertEquals(1, testClient(engine).list().toList().size)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun decodesPercentEncodedKeysExactlyOnce() = runTest {
        // What the server returns for `meta/Grün Reise.db` under encoding-type=url.
        val engine = stubEngine(
            Stub(body = listXml(listOf(Triple("meta/Gr%C3%BCn%20Reise.db", 100L, "x")))),
        )
        assertEquals("meta/Grün Reise.db", testClient(engine).list().toList().first().key)
    }

    @Test
    fun leavesKeysUntouchedWhenTheServerIgnoresEncodingType() = runTest {
        // Without the echo check, a literal '%20' in a key would be mangled.
        val engine = stubEngine(
            Stub(
                body = listXml(
                    listOf(Triple("meta/100%20percent.db", 100L, "x")),
                    encodingType = null,
                ),
            ),
        )
        assertEquals("meta/100%20percent.db", testClient(engine).list().toList().first().key)
    }

    @Test
    fun survivesXmlEntitiesInAKey() = runTest {
        val engine = stubEngine(
            Stub(
                body = "<ListBucketResult><EncodingType>url</EncodingType>" +
                    "<IsTruncated>false</IsTruncated>" +
                    "<Contents><Key>meta/Tom%20&amp;%20Jerry.db</Key><Size>10</Size>" +
                    "<ETag>&quot;e1&quot;</ETag></Contents></ListBucketResult>",
            ),
        )
        val object_ = testClient(engine).list().toList().first()
        assertEquals("meta/Tom & Jerry.db", object_.key)
        assertEquals(ETag("e1"), object_.etag)
    }

    @Test
    fun reportsDirectoryMarkersRawForSection4sDiffToSkip() = runTest {
        // bunny.net materialises `meta/Trips/` as a zero-byte key with no ETag when
        // `meta/Trips/Iceland.db` is written (§2). The client does not filter it.
        val engine = stubEngine(
            Stub(
                body = "<ListBucketResult><EncodingType>url</EncodingType>" +
                    "<IsTruncated>false</IsTruncated>" +
                    "<Contents><Key>meta/Trips/</Key><Size>0</Size></Contents>" +
                    "<Contents><Key>meta/Trips/Iceland.db</Key><Size>32768</Size>" +
                    "<ETag>&quot;abc&quot;</ETag></Contents></ListBucketResult>",
            ),
        )
        val objects = testClient(engine).list(prefix = "meta/").toList()

        assertEquals(2, objects.size)
        assertTrue(objects[0].isDirectoryMarker)
        assertNull(objects[0].etag)
        assertTrue(!objects[1].isDirectoryMarker)
        // What §4's sync diff is expected to do with them.
        assertEquals(
            listOf("meta/Trips/Iceland.db"),
            objects.filterNot { it.isDirectoryMarker }.map { it.key },
        )
    }

    /**
     * The most dangerous failure mode in the whole design.
     *
     * xmlutil streams, so a document cut off part-way can yield the elements it did read before
     * it complains, and an empty one gives no elements and no complaint. Either way a connection
     * dropped mid-LIST would parse as *zero objects*, and §4's diff treats a missing key as a
     * deleted album. Without the opened-and-closed root check, a short read would drop the
     * entire catalog.
     */
    @Test
    fun neverReadsATruncatedResponseAsAnEmptyBucket() = runTest {
        val bodies = listOf(
            "<ListBucket",
            "<ListBucketResult><Contents><Key>meta/a.db</Key></Contents>",
            "<ListBucketResult><IsTruncated>false</IsTruncated>",
            "",
            "not xml at all",
        )
        for (body in bodies) {
            assertFailsWith<MalformedResponseFailure>(body) {
                testClient(stubEngine(Stub(body = body))).list().toList()
            }
        }
    }

    @Test
    fun failsRatherThanReportingAShortListWhenAResponseIsCutOff() = runTest {
        val whole = listXml(
            listOf(
                Triple("meta/a.db", 1L, "a"),
                Triple("meta/b.db", 2L, "b"),
                Triple("meta/c.db", 3L, "c"),
            ),
        )
        // Simulate the connection dying two objects in.
        val cut = whole.substring(0, whole.length - 120)
        assertFailsWith<MalformedResponseFailure> {
            testClient(stubEngine(Stub(body = cut))).list().toList()
        }
    }

    @Test
    fun readsAnEmptyBucketAsAnEmptyFlow() = runTest {
        assertTrue(testClient(stubEngine(Stub(body = listXml()))).list().toList().isEmpty())
    }
}

class PresignTest {

    @Test
    fun carriesEveryQueryParameterAndNoAuthorizationHeader() {
        val url = testClient(stubEngine())
            .presignedPut("originals/Trip/IMG_1.heic", expiresIn = 3600.seconds)

        assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), url)
        assertTrue(url.contains("X-Amz-Credential=my-photos%2F"), url)
        assertTrue(url.contains("X-Amz-Expires=3600"), url)
        assertTrue(url.contains("X-Amz-SignedHeaders=host"), url)
        assertTrue(url.contains("X-Amz-Signature="), url)
        assertTrue(
            url.startsWith(
                "https://de-s3.storage.bunnycdn.com/my-photos/originals/Trip/IMG_1.heic?",
            ),
            url,
        )
    }

    @Test
    fun givesTheSameSignatureForTheSameInputs() {
        val client = testClient(stubEngine(), clock = fixedClock)
        assertEquals(
            client.presignedPut("meta/a.db", expiresIn = 60.seconds),
            client.presignedPut("meta/a.db", expiresIn = 60.seconds),
        )
    }

    @Test
    fun enforcesBunnyNets1SecondTo7DayExpiryWindow() {
        val client = testClient(stubEngine())
        for (expiry in listOf(0.seconds, (-1).seconds, 604_801.seconds)) {
            assertFailsWith<MalformedResponseFailure>(expiry.toString()) {
                client.presignedPut("meta/a.db", expiresIn = expiry)
            }
        }
    }

    @Test
    fun acceptsTheWindowsEdges() {
        val client = testClient(stubEngine())
        for (expiry in listOf(1.seconds, 604_800.seconds)) {
            assertNotNull(client.presignedPut("meta/a.db", expiresIn = expiry))
        }
    }
}

class MultipartSizingTest {

    @Test
    fun keepsThePreferredPartSizeForAnOrdinaryFile() {
        assertEquals(
            16L * 1024 * 1024,
            partSizeFor(100L * 1024 * 1024, preferred = 16L * 1024 * 1024),
        )
    }

    @Test
    fun growsThePartSizeSoBunnys10000PartCapIsNeverHit() {
        // 1 TB at 16 MB parts would be 65 536 parts — beyond the limit.
        val huge = 1024L * 1024 * 1024 * 1024
        val size = partSizeFor(huge, preferred = 16L * 1024 * 1024)
        assertTrue(huge / size <= MAXIMUM_PART_COUNT)
    }

    /**
     * S3 rejects a short part only at CompleteMultipartUpload — after the entire file has been
     * uploaded. Clamping means a caller cannot configure that.
     */
    @Test
    fun raisesAPartSizeBelowS3s5MbMinimum() {
        for (preferred in listOf(1024L, 512L * 1024, 4L * 1024 * 1024)) {
            assertEquals(
                MINIMUM_PART_SIZE,
                partSizeFor(100L * 1024 * 1024, preferred),
                preferred.toString(),
            )
        }
    }
}
