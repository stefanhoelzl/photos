package net.stho.photos.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.stho.photos.StorageUrlFailure

/**
 * One storage URL has to yield endpoint, signing region and zone (§1), and the zone doubles as
 * the access key ID — so a parsing slip breaks every signature.
 */
class StorageUrlTest {

    @Test
    fun readsTheDocumentedBunnyNetForm() {
        val url = "https://de-s3.storage.bunnycdn.com/my-photos".asStorageUrl()
        assertEquals("https://de-s3.storage.bunnycdn.com", url.endpoint)
        assertEquals("de", url.region)
        assertEquals("my-photos", url.zone)
    }

    @Test
    fun toleratesTrailingSlashNestedPathAndSurroundingSpace() {
        val inputs = listOf(
            "https://de-s3.storage.bunnycdn.com/my-photos/",
            "https://de-s3.storage.bunnycdn.com/my-photos/meta",
            "https://de-s3.storage.bunnycdn.com/my-photos/meta/Trips/",
            "  https://de-s3.storage.bunnycdn.com/my-photos  ",
        )
        for (input in inputs) {
            val url = input.asStorageUrl()
            assertEquals("my-photos", url.zone, input)
            assertEquals("de", url.region, input)
            assertEquals("https://de-s3.storage.bunnycdn.com", url.endpoint, input)
        }
    }

    @Test
    fun takesTheRegionFromTheHostsRegionS3Label() {
        val cases = listOf(
            "https://de-s3.storage.bunnycdn.com/z" to "de",
            "https://ny-s3.storage.bunnycdn.com/z" to "ny",
            "https://UK-S3.storage.bunnycdn.com/z" to "uk",
            "https://storage.bunnycdn.com/z" to StorageUrl.DEFAULT_REGION,
            "https://s3.example.com/z" to StorageUrl.DEFAULT_REGION,
        )
        for ((input, expected) in cases) {
            assertEquals(expected, input.asStorageUrl().region, input)
        }
    }

    @Test
    fun keepsANonDefaultPortOnTheEndpoint() {
        val url = "http://127.0.0.1:9090/test-zone".asStorageUrl()
        assertEquals("http://127.0.0.1:9090", url.endpoint)
        // And in the Host header, which is signed — a signature over a portless authority
        // would not match what the client sends.
        assertEquals("127.0.0.1:9090", url.host)
        assertEquals("test-zone", url.zone)
    }

    @Test
    fun rejectsWhatCannotBeAStorageUrl() {
        assertFailsWith<StorageUrlFailure.MissingZone> {
            "https://de-s3.storage.bunnycdn.com".asStorageUrl()
        }
        assertFailsWith<StorageUrlFailure.MissingZone> {
            "https://de-s3.storage.bunnycdn.com/".asStorageUrl()
        }
        assertEquals(
            "ftp",
            assertFailsWith<StorageUrlFailure.UnsupportedScheme> {
                "ftp://de-s3.storage.bunnycdn.com/zone".asStorageUrl()
            }.scheme,
        )
        assertFailsWith<StorageUrlFailure.MissingScheme> {
            "de-s3.storage.bunnycdn.com/zone".asStorageUrl()
        }
    }

    /** §1: never an opaque error. */
    @Test
    fun parseErrorsSayWhatIsWrong() {
        assertTrue(StorageUrlFailure.MissingZone().message.contains("<zone>"))
        assertTrue(StorageUrlFailure.MissingScheme().message.contains("https://"))
    }
}

class ETagTest {

    @Test
    fun stripsQuotesOnReadAndRestoresThemOnSend() {
        val tag = "\"d41d8cd98f00b204e9800998ecf8427e\"".asETag()
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", tag.value)
        assertEquals("\"d41d8cd98f00b204e9800998ecf8427e\"", tag.headerValue)
    }

    /** It is not an MD5, so nothing may assume its shape. */
    @Test
    fun carriesAMultipartETagUnchanged() {
        val tag = "\"9bb58f26192e4ba00f01e2e7b136bbd8-5\"".asETag()
        assertEquals("9bb58f26192e4ba00f01e2e7b136bbd8-5", tag.value)
    }

    @Test
    fun comparesQuotedAndUnquotedFormsEqual() {
        // The whole point: a stray pair of quotes on one side of the sync diff would
        // re-download the entire library.
        assertEquals(ETag("abc"), "\"abc\"".asETag())
    }

    @Test
    fun dropsTheWeakValidatorPrefix() {
        assertEquals("abc", "W/\"abc\"".asETag().value)
    }
}
