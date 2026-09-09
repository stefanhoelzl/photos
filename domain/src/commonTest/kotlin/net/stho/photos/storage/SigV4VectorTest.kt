package net.stho.photos.storage

import io.ktor.http.decodeURLPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * Drives the vendored AWS SigV4 suite (see Fixtures/sigv4/PROVENANCE.md).
 *
 * The S3 server used elsewhere does not validate signatures, so these vectors are the sole proof
 * the signer is correct. Each stage — canonical request, string-to-sign, signature — is asserted
 * separately so a failure names which stage broke rather than just "signature mismatch".
 */
class SigV4VectorTest {

    @Test
    fun theSuiteIsVendoredAndNonTrivial() {
        assertTrue(
            vectors.size >= 30,
            "only ${vectors.size} vectors found — the suite is the sole proof the signer works",
        )
    }

    @Test
    fun headerAuthCanonicalRequest() = forEachVector { vector ->
        val expected = vector.expect("header-canonical-request")
        val payloadHash = vector.body.sha256Hex()

        // The signer has no notion of STS or body-hash headers (§7: no session tokens); it signs
        // whatever headers it is handed, so the harness supplies the ones these vectors expect.
        val headers = buildList {
            addAll(vector.headers)
            if (none { it.first.lowercase() == "x-amz-date" }) {
                add("X-Amz-Date" to vector.at.amzDate())
            }
            vector.sessionToken?.let { add("X-Amz-Security-Token" to it) }
            if (vector.signBody) add("X-Amz-Content-Sha256" to payloadHash)
        }

        val actual = vector.signer.canonicalRequest(
            method = vector.method,
            path = vector.path,
            query = vector.query,
            headers = headers,
            payloadHash = payloadHash,
        ).text
        assertEquals(expected, actual, vector.name)
    }

    @Test
    fun headerAuthStringToSign() = forEachVector { vector ->
        assertEquals(
            vector.expect("header-string-to-sign"),
            vector.signer.stringToSign(vector.expect("header-canonical-request"), vector.at),
            vector.name,
        )
    }

    @Test
    fun headerAuthSignature() = forEachVector { vector ->
        assertEquals(
            vector.expect("header-signature"),
            vector.signer.signature(vector.expect("header-string-to-sign"), vector.at),
            vector.name,
        )
    }

    @Test
    fun queryAuthCanonicalRequest() = forEachVector { vector ->
        // Query auth signs the request's headers as sent — no X-Amz-Date header is added,
        // because the date moves into the query string. `X-Amz-SignedHeaders` must therefore be
        // computed before the query is assembled.
        val query = buildList {
            addAll(vector.query)
            add("X-Amz-Algorithm" to AWS4_HMAC_SHA256)
            add("X-Amz-Credential" to vector.signer.credential(vector.at))
            add("X-Amz-Date" to vector.at.amzDate())
            add("X-Amz-Expires" to vector.expiresInSeconds.toString())
            vector.sessionToken?.let { add("X-Amz-Security-Token" to it) }
            add("X-Amz-SignedHeaders" to vector.headers.canonicalHeaders().signed)
        }

        val actual = vector.signer.canonicalRequest(
            method = vector.method,
            path = vector.path,
            query = query,
            headers = vector.headers,
            payloadHash = vector.body.sha256Hex(),
        ).text
        assertEquals(vector.expect("query-canonical-request"), actual, vector.name)
    }

    @Test
    fun queryAuthSignature() = forEachVector { vector ->
        assertEquals(
            vector.expect("query-signature"),
            vector.signer.signature(vector.expect("query-string-to-sign"), vector.at),
            vector.name,
        )
    }
}

/**
 * Every vector, so one failing stage names every case it fails on rather than only the first.
 */
private fun forEachVector(check: (Vector) -> Unit) {
    for (vector in vectors) check(vector)
}

private class Vector(
    val name: String,
    val signer: SigV4Signer,
    val at: Instant,
    val expiresInSeconds: Int,
    val sessionToken: String?,
    val signBody: Boolean,
    val method: String,
    val path: String,
    val query: List<Pair<String, String>>,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val expected: Map<String, String>,
) {
    fun expect(file: String): String =
        assertNotNull(expected[file], "$name is missing $file.txt")
}

private const val FIXTURES_VARIABLE = "PHOTOS_SIGV4_FIXTURES"

private val vectors: List<Vector> by lazy {
    // A miss here fails rather than skips, on purpose: a subtle signing bug fails every request
    // (§7), and a suite that quietly does not run looks exactly like a suite that passes.
    val root = environmentVariable(FIXTURES_VARIABLE)
        ?: error(
            "$FIXTURES_VARIABLE is not set — the SigV4 vectors are the only proof the signer " +
                "works, so this suite must never be skipped. The Gradle build sets it; run " +
                "the tests through :domain:linuxX64Test.",
        )
    val directory = Path(root)
    require(SystemFileSystem.exists(directory)) { "$FIXTURES_VARIABLE points at $root, which does not exist" }

    SystemFileSystem.list(directory)
        .filter { SystemFileSystem.exists(Path(it, "request.txt")) }
        .sortedBy { it.name }
        .map { it.readVector() }
}

private fun Path.readVector(): Vector {
    val context = readText("context.json") ?: error("$name has no context.json")
    val request = parseRequest(readText("request.txt") ?: error("$name has no request.txt"))

    val expected = listOf(
        "header-canonical-request", "header-string-to-sign", "header-signature",
        "query-canonical-request", "query-string-to-sign", "query-signature",
    ).mapNotNull { file -> readText("$file.txt")?.let { file to it } }.toMap()

    return Vector(
        name = name,
        signer = SigV4Signer(
            accessKeyId = context.jsonText("access_key_id").orEmpty(),
            secretAccessKey = context.jsonText("secret_access_key").orEmpty(),
            region = context.jsonText("region") ?: "us-east-1",
            service = context.jsonText("service") ?: "service",
            normalizePath = context.jsonFlag("normalize") ?: true,
        ),
        at = Instant.parse(context.jsonText("timestamp") ?: "1970-01-01T00:00:00Z"),
        expiresInSeconds = context.jsonNumber("expiration_in_seconds") ?: 3600,
        // `omit_session_token` means the token is attached after signing, so it must not appear
        // in the canonical request.
        sessionToken = if (context.jsonFlag("omit_session_token") == true) {
            null
        } else {
            context.jsonText("token")
        },
        signBody = context.jsonFlag("sign_body") ?: false,
        method = request.method,
        path = request.path,
        query = request.query,
        headers = request.headers,
        body = request.body,
        expected = expected,
    )
}

private fun Path.readText(file: String): String? {
    val path = Path(this, file)
    if (!SystemFileSystem.exists(path)) return null
    return SystemFileSystem.source(path).buffered().use { it.readString() }
}

// A regex apiece rather than a JSON parser: the suite's context files are eight flat fields with
// unique names, and a serialization dependency for that would cost more than it explains.
private fun String.jsonText(key: String): String? =
    Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(this)?.groupValues?.get(1)

private fun String.jsonFlag(key: String): Boolean? =
    Regex("\"$key\"\\s*:\\s*(true|false)").find(this)?.groupValues?.get(1)?.toBooleanStrictOrNull()

private fun String.jsonNumber(key: String): Int? =
    Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(this)?.groupValues?.get(1)?.toIntOrNull()

private class ParsedRequest(
    val method: String,
    val path: String,
    val query: List<Pair<String, String>>,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
)

/**
 * Parses the suite's raw-HTTP `request.txt`, including obsolete line folding (a continuation
 * line joins its predecessor with a single space).
 */
private fun parseRequest(raw: String): ParsedRequest {
    val lines = raw.split("\n")
    val words = lines.first().split(" ").toMutableList()
    val method = words.firstOrNull() ?: "GET"
    if (words.size > 1) words.removeFirst()
    if (words.size > 1 && words.last().startsWith("HTTP/")) words.removeLast()
    // A path may itself contain spaces (get-space-*), so rejoin what is left.
    val target = words.joinToString(" ")

    val path = target.substringBefore('?')
    val queryString = if ('?' in target) target.substringAfter('?') else ""

    val query = queryString.split("&").filter { it.isNotEmpty() }.map { pair ->
        pair.substringBefore('=').percentDecoded() to
            (if ('=' in pair) pair.substringAfter('=') else "").percentDecoded()
    }

    val headers = mutableListOf<Pair<String, String>>()
    var body = ByteArray(0)
    var index = 1
    while (index < lines.size) {
        val line = lines[index]
        if (line.isEmpty()) {
            body = lines.drop(index + 1).joinToString("\n").encodeToByteArray()
            break
        }
        if (line.startsWith(" ") || line.startsWith("\t")) {
            // Obsolete folding: a continuation of the previous header value.
            headers.removeLastOrNull()?.let { (name, value) ->
                headers.add(name to "$value ${line.trim()}")
            }
        } else if (':' in line) {
            headers.add(line.substringBefore(':') to line.substringAfter(':'))
        }
        index += 1
    }
    return ParsedRequest(method, path, query, headers, body)
}

private fun String.percentDecoded(): String = runCatching { decodeURLPart() }.getOrDefault(this)
