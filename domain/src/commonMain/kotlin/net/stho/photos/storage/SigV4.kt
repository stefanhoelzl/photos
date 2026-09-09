package net.stho.photos.storage

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * AWS Signature Version 4.
 *
 * Hand-written rather than taken from an SDK, whose signer arrives welded to a credential
 * chain, a retry policy and a transport this design has already decided differently about
 * (§7). The surface needed here is small: one service, one region, static credentials, no STS
 * session tokens, path-style URLs.
 *
 * A subtle bug here fails *every* request, and the S3 server used in tests does not validate
 * signatures — so the vendored AWS vector suite is the only proof this code is correct. It is
 * compared stage by stage (canonical request, string-to-sign, signature) so a failure names
 * which stage broke.
 *
 * Deliberately not a `data class`: the generated `toString` would print the storage password
 * into the first log line that mentions a signer.
 */
public class SigV4Signer(
    public val accessKeyId: String,
    private val secretAccessKey: String,
    public val region: String,
    public val service: String = "s3",
    /**
     * S3 signs the path exactly as sent — `/a/./b` is a real key, not `/a/b`. Every other AWS
     * service normalises first. The vector suite covers both.
     */
    public val normalizePath: Boolean = false,
) {

    // ------------------------------------------------------------------ the three stages

    public fun canonicalRequest(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        headers: List<Pair<String, String>> = emptyList(),
        payloadHash: String,
    ): CanonicalRequest {
        val canonicalHeaders = headers.canonicalHeaders()
        val text = listOf(
            method,
            canonicalPath(path),
            query.canonicalQuery(),
            canonicalHeaders.text,
            canonicalHeaders.signed,
            payloadHash,
        ).joinToString("\n")
        return CanonicalRequest(text, canonicalHeaders.signed)
    }

    public fun stringToSign(canonicalRequest: String, at: Instant): String = listOf(
        AWS4_HMAC_SHA256,
        at.amzDate(),
        credentialScope(at),
        canonicalRequest.encodeToByteArray().sha256Hex(),
    ).joinToString("\n")

    public fun signature(stringToSign: String, at: Instant): String {
        var key = "AWS4$secretAccessKey".encodeToByteArray()
        for (component in listOf(at.dateStamp(), region, service, "aws4_request")) {
            key = HmacSHA256(key).doFinal(component.encodeToByteArray())
        }
        return HmacSHA256(key).doFinal(stringToSign.encodeToByteArray()).hex()
    }

    public fun credentialScope(at: Instant): String =
        "${at.dateStamp()}/$region/$service/aws4_request"

    /** `<access key id>/<scope>`, for the `Authorization` header and `X-Amz-Credential`. */
    public fun credential(at: Instant): String = "$accessKeyId/${credentialScope(at)}"

    private fun canonicalPath(rawPath: String): String {
        val path = rawPath.ifEmpty { "/" }
        return (if (normalizePath) path.normalizedPath() else path).uriEncoded(encodeSlash = false)
    }
}

/** The canonical request and the `SignedHeaders` list that has to accompany it. */
public data class CanonicalRequest(public val text: String, public val signedHeaders: String)

public const val AWS4_HMAC_SHA256: String = "AWS4-HMAC-SHA256"

/**
 * What `x-amz-content-sha256` says when the body is not hashed.
 *
 * Verified against the live zone: bunny.net accepts this on header-authenticated PUTs, which is
 * what lets a 120 GB import skip a second pass over every byte. Query auth always uses it — the
 * body is not known when the URL is minted.
 */
public const val UNSIGNED_PAYLOAD: String = "UNSIGNED-PAYLOAD"

// ---------------------------------------------------------------------- canonicalisation

private const val HEX_UPPER = "0123456789ABCDEF"
private const val HEX_LOWER = "0123456789abcdef"

/**
 * AWS's unreserved set: everything else is percent-encoded with uppercase hex.
 *
 * Deliberately not a URL library's encoder — those treat an already-present `%` as literal, and
 * several emit lowercase hex, either of which changes the signature and nothing else.
 */
internal fun String.uriEncoded(encodeSlash: Boolean): String = buildString(length) {
    for (byte in this@uriEncoded.encodeToByteArray()) {
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        when {
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '_' || char == '.' || char == '~' -> append(char)
            char == '/' && !encodeSlash -> append('/')
            else -> append('%').append(HEX_UPPER[value shr 4]).append(HEX_UPPER[value and 0xF])
        }
    }
}

/**
 * Removes `.` and `..` segments and collapses duplicate slashes. Applied only when the signer
 * normalises — never for S3.
 */
internal fun String.normalizedPath(): String {
    if (isEmpty()) return "/"
    val trailingSlash = endsWith("/") && this != "/"
    val stack = mutableListOf<String>()
    for (segment in split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> stack.removeLastOrNull()
            else -> stack.add(segment)
        }
    }
    if (stack.isEmpty()) return "/"
    return "/" + stack.joinToString("/") + if (trailingSlash) "/" else ""
}

/** Sorted by encoded name, then by encoded value. Values may repeat per name. */
internal fun List<Pair<String, String>>.canonicalQuery(): String =
    map { (name, value) -> name.uriEncoded(encodeSlash = true) to value.uriEncoded(encodeSlash = true) }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .joinToString("&") { (name, value) -> "$name=$value" }

/** The canonical header block and the `;`-joined names it signs. */
internal data class CanonicalHeaders(val text: String, val signed: String)

/**
 * Lowercased names, values trimmed and inner runs of whitespace collapsed. Repeated names are
 * joined with `,` in the order they were sent.
 */
internal fun List<Pair<String, String>>.canonicalHeaders(): CanonicalHeaders {
    val grouped = linkedMapOf<String, MutableList<String>>()
    for ((name, value) in this) {
        grouped.getOrPut(name.lowercase()) { mutableListOf() }.add(value.whitespaceCollapsed())
    }
    val names = grouped.keys.sorted()
    return CanonicalHeaders(
        text = names.joinToString("") { "$it:${grouped.getValue(it).joinToString(",")}\n" },
        signed = names.joinToString(";"),
    )
}

/**
 * Trims outer whitespace and collapses inner runs to a single space.
 *
 * Applies inside double-quoted sections too — `"a   b   c"` signs as `"a b c"`. An earlier
 * draft preserved quoted runs, which the `get-header-value-trim` vector rejects.
 */
internal fun String.whitespaceCollapsed(): String = buildString(length) {
    var pendingSpace = false
    var started = false
    for (char in this@whitespaceCollapsed) {
        if (char == ' ' || char == '\t') {
            if (started) pendingSpace = true
            continue
        }
        if (pendingSpace) {
            append(' ')
            pendingSpace = false
        }
        append(char)
        started = true
    }
}

// ---------------------------------------------------------------------- primitives

internal fun ByteArray.hex(): String = buildString(size * 2) {
    for (byte in this@hex) {
        val value = byte.toInt() and 0xFF
        append(HEX_LOWER[value shr 4]).append(HEX_LOWER[value and 0xF])
    }
}

public fun ByteArray.sha256Hex(): String = SHA256().digest(this).hex()

/** SHA-256 of a file, read in chunks so a 200 MB video never lands in memory. */
public fun Path.sha256Hex(): String {
    val digest = SHA256()
    SystemFileSystem.source(this).buffered().use { source ->
        val chunk = ByteArray(1 shl 20)
        while (true) {
            val read = source.readAtMostTo(chunk, 0, chunk.size)
            if (read <= 0) break
            digest.update(chunk, 0, read)
        }
    }
    return digest.digest().hex()
}

/** `20150830T123600Z` — the format `X-Amz-Date` and the credential scope are cut from. */
public fun Instant.amzDate(): String {
    val at = toLocalDateTime(TimeZone.UTC)
    return buildString(16) {
        append(at.year.toString().padStart(4, '0'))
        append(at.month.number.pad2())
        append(at.day.pad2())
        append('T')
        append(at.hour.pad2())
        append(at.minute.pad2())
        append(at.second.pad2())
        append('Z')
    }
}

/** `20150830` — the day half of the credential scope. */
public fun Instant.dateStamp(): String = amzDate().take(8)

private fun Int.pad2(): String = toString().padStart(2, '0')
