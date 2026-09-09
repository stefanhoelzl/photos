package net.stho.photos.storage

import io.ktor.http.HttpHeaders
import kotlin.time.Duration
import net.stho.photos.MalformedResponseFailure

/**
 * A presigned URL that authorises one PUT of one object.
 *
 * §8's upload flow pre-signs every PUT for an album while the password is in memory, then hands
 * the URLs to a background uploader. That uploader may still be running long after the app was
 * force-quit and relaunched, so it must never need the key again. bunny.net accepts expiries
 * between 1 second and 7 days.
 *
 * The payload hash is `UNSIGNED-PAYLOAD`, which is what query auth always uses: the body is not
 * known when the URL is minted.
 */
public fun S3Client.presignedPut(key: String, expiresIn: Duration): String =
    presigned("PUT", key, expiresIn)

/**
 * The general form. [presignedPut] is the only consumer in the design; a presigned GET has
 * none, because the app signs its own reads.
 */
internal fun S3Client.presigned(method: String, key: String, expiresIn: Duration): String {
    val seconds = expiresIn.inWholeSeconds
    if (seconds !in 1..604_800) {
        throw MalformedResponseFailure(
            "presigned URL expiry must be between 1 second and 7 days, got ${seconds}s",
        )
    }

    val now = clock.now()
    val path = keyPath(key)

    // Query auth signs the headers it lists, and here that is only `host` — everything else
    // moves into the query string. `X-Amz-SignedHeaders` must therefore be computed before the
    // query is assembled.
    val headers = listOf(HttpHeaders.Host to storage.host)
    val query = mutableListOf(
        "X-Amz-Algorithm" to AWS4_HMAC_SHA256,
        "X-Amz-Credential" to signer.credential(now),
        "X-Amz-Date" to now.amzDate(),
        "X-Amz-Expires" to seconds.toString(),
        "X-Amz-SignedHeaders" to headers.canonicalHeaders().signed,
    )

    val canonical = signer.canonicalRequest(
        method = method,
        path = path,
        query = query,
        headers = headers,
        payloadHash = UNSIGNED_PAYLOAD,
    )
    query += "X-Amz-Signature" to signer.signature(signer.stringToSign(canonical.text, now), now)

    return requestUrl(path, query)
}
