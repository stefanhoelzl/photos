package net.stho.photos.storage

import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.decodeURLPart
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.stho.photos.MalformedResponseFailure
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * Every object under a prefix, paging internally.
 *
 * §4's whole sync mechanism is one LIST on `meta/`: the response carries the ETag and size of
 * every shard, so that single request *is* the sync plan.
 *
 * A `Flow` rather than a list, so a full-bucket scan for `--prune` streams rather than
 * materialising ~140 000 keys at once; `toList()` covers the small `meta/` case. The caller
 * never sees a continuation token.
 *
 * Keys are reported **raw**. bunny.net returns zero-byte directory markers ending in `/`, and
 * §4 puts the responsibility for skipping them in the sync diff, not here — see
 * [S3Object.isDirectoryMarker].
 */
public fun S3Client.list(prefix: String = "", maxKeysPerPage: Int = 1000): Flow<S3Object> = flow {
    var continuationToken: String? = null
    while (true) {
        val page = listPage(prefix, continuationToken, maxKeysPerPage)
        for (object_ in page.objects) emit(object_)
        continuationToken = page.nextContinuationToken
        // A truncated page with no token would otherwise ask for the same page forever.
        if (!page.isTruncated || continuationToken == null) return@flow
    }
}

/** One page of a LIST. */
internal suspend fun S3Client.listPage(
    prefix: String,
    continuationToken: String?,
    maxKeys: Int,
): ListObjectsPage {
    val query = buildList {
        add("list-type" to "2")
        // LIST responses are XML, and C1 control characters are illegal in XML. A single such
        // key would corrupt the parse — and LIST *is* the sync mechanism — so the server
        // percent-encodes keys for us (§2).
        add("encoding-type" to "url")
        add("max-keys" to maxKeys.toString())
        if (prefix.isNotEmpty()) add("prefix" to prefix)
        if (continuationToken != null) add("continuation-token" to continuationToken)
    }
    // LIST addresses the zone itself, not a key under it.
    val response = send("GET", "/${storage.zone}", query = query, accepting = setOf(200))
    return response.bodyAsBytes().decodeToString().asListObjectsPage()
}

internal data class ListObjectsPage(
    val objects: List<S3Object>,
    val nextContinuationToken: String?,
    val isTruncated: Boolean,
)

/**
 * Parses a `ListObjectsV2` response.
 *
 * A real XML parser rather than a hand-rolled scanner: entity escapes and odd codepoints land
 * on exactly the data path §2 flags as fragile.
 *
 * The completeness check is ours, not the parser's, and it is the most important line in this
 * file. xmlutil streams, so a document that is cut off mid-way yields the elements it did read
 * and only then throws — and a document that is empty, or whose root is never closed, may not
 * throw at all. That is the §2 failure mode in its worst form: a connection dropped mid-LIST
 * would parse as zero objects, and §4's diff reads a missing key as "album deleted". A
 * truncated response would delete the whole catalog. So a parse counts only when the
 * `ListBucketResult` element was both opened *and* closed.
 */
internal fun String.asListObjectsPage(): ListObjectsPage {
    val objects = mutableListOf<S3Object>()
    var isTruncated = false
    var nextToken: String? = null
    var encodingType: String? = null
    var sawRoot = false
    var closedRoot = false

    var inContents = false
    val buffer = StringBuilder()
    var key = ""
    var size = 0L
    var etag: ETag? = null
    var lastModified: Instant? = null

    try {
        val reader = xmlStreaming.newGenericReader(this)
        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> {
                    buffer.clear()
                    when (reader.localName) {
                        "ListBucketResult" -> sawRoot = true
                        "Contents" -> {
                            inContents = true
                            key = ""
                            size = 0L
                            etag = null
                            lastModified = null
                        }
                    }
                }

                EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF ->
                    buffer.append(reader.text)

                EventType.END_ELEMENT -> {
                    val text = buffer.toString()
                    buffer.clear()
                    when {
                        reader.localName == "ListBucketResult" -> closedRoot = true
                        reader.localName == "Contents" -> {
                            inContents = false
                            objects.add(S3Object(key, size, etag, lastModified))
                        }
                        inContents -> when (reader.localName) {
                            "Key" -> key = text
                            "Size" -> size = text.trim().toLongOrNull() ?: 0L
                            "ETag" -> etag = text.asETag()
                            "LastModified" -> lastModified = text.asIso8601()
                        }
                        else -> when (reader.localName) {
                            "IsTruncated" -> isTruncated = text.trim() == "true"
                            "NextContinuationToken" -> nextToken = text
                            "EncodingType" -> encodingType = text.trim()
                        }
                    }
                }

                else -> Unit
            }
        }
    } catch (failure: Exception) {
        // Deliberately broad. xmlutil signals malformed input with more than one type — an
        // `XmlException` for a bad document, an `IllegalArgumentException` for an empty one —
        // and every one of them means the same thing here: this response cannot be trusted, so
        // it must not become "the bucket is empty".
        throw MalformedResponseFailure("could not parse the LIST response: ${failure.message}")
    }

    if (!sawRoot || !closedRoot) {
        throw MalformedResponseFailure(
            "LIST response was truncated — no complete <ListBucketResult> element",
        )
    }

    // We always ask for encoding-type=url, so keys arrive percent-encoded and must be decoded
    // exactly once. A server that ignored the parameter would otherwise have its literal '%'
    // sequences mangled — hence the echo check.
    if (encodingType?.lowercase() != "url") {
        return ListObjectsPage(objects, nextToken, isTruncated)
    }
    return ListObjectsPage(
        objects = objects.map { it.copy(key = it.key.percentDecoded()) },
        nextContinuationToken = nextToken?.percentDecoded(),
        isTruncated = isTruncated,
    )
}

/** A key that is not valid percent-encoding is kept as it came, never dropped. */
private fun String.percentDecoded(): String = runCatching { decodeURLPart() }.getOrDefault(this)

private fun String.asIso8601(): Instant? = runCatching { Instant.parse(trim()) }.getOrNull()
