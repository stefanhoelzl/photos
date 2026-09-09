package net.stho.photos.storage

import io.ktor.http.URLParserException
import io.ktor.http.Url
import net.stho.photos.StorageUrlFailure

/**
 * One storage URL carries endpoint, signing region and zone (§1).
 *
 * e.g. `https://de-s3.storage.bunnycdn.com/my-photos` gives endpoint
 * `https://de-s3.storage.bunnycdn.com`, region `de`, zone `my-photos`.
 *
 * On bunny.net the zone name *is* the access key ID, so a single URL plus a password is a
 * complete credential. Both the app's setup screen and the CLI's `PHOTOS_ENDPOINT` parse the
 * same string, which is why this lives beside the signer rather than in either caller.
 */
public data class StorageUrl(
    /** Scheme, host and — when one was given — port. No trailing slash. */
    public val endpoint: String,
    /** SigV4 signing region, taken from the host's `<region>-s3` prefix. */
    public val region: String,
    /** The zone name — also the access key ID on bunny.net. */
    public val zone: String,
) {
    /**
     * What goes in the `Host` header, and so what gets signed: the authority, port included.
     * A signed `Host` that omitted a non-default port would not match what the client sends.
     */
    public val host: String get() = endpoint.substringAfter("://")

    public companion object {
        /**
         * Default when the host carries no `<region>-s3` prefix. bunny.net's signer accepts
         * this for its unprefixed endpoint.
         */
        public const val DEFAULT_REGION: String = "de"
    }
}

/**
 * Parses the one string §1 asks a person to type.
 *
 * The path may be `/zone`, `/zone/`, or `/zone/nested/prefix` — only the first segment is the
 * zone; anything deeper is ignored, since keys are always addressed from the zone root.
 */
public fun String.asStorageUrl(): StorageUrl {
    val text = trim()

    // Checked before handing the string to a URL parser: a parser that is lenient about a
    // missing scheme would guess one, and "de-s3.storage.bunnycdn.com/zone" would then parse
    // as a valid http URL rather than as the mistake it is.
    if (!SCHEME.containsMatchIn(text)) throw StorageUrlFailure.MissingScheme()

    val url = try {
        Url(text)
    } catch (_: URLParserException) {
        throw StorageUrlFailure.NotAUrl(text)
    }

    val scheme = url.protocolOrNull?.name?.lowercase() ?: throw StorageUrlFailure.MissingScheme()
    if (scheme != "https" && scheme != "http") throw StorageUrlFailure.UnsupportedScheme(scheme)
    if (url.host.isEmpty()) throw StorageUrlFailure.MissingHost()

    val zone = url.rawSegments.firstOrNull { it.isNotEmpty() } ?: throw StorageUrlFailure.MissingZone()

    return StorageUrl(
        endpoint = buildString {
            append(scheme).append("://").append(url.host)
            if (url.specifiedPort != DEFAULT_PORT) append(':').append(url.specifiedPort)
        },
        region = url.host.signingRegion(),
        zone = zone,
    )
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** Ktor's marker for "no port was written in the URL". */
private const val DEFAULT_PORT = 0

/**
 * `de-s3.storage.bunnycdn.com` → `de`; `storage.bunnycdn.com` → the default.
 *
 * Hostnames are case-insensitive, so the label is lowercased before the `-s3` test. Without
 * that, a URL typed as `DE-S3.…` would silently fall back to the default region and every
 * signature would fail with an unexplained 403.
 */
private fun String.signingRegion(): String {
    val label = substringBefore('.').lowercase()
    if (!label.endsWith("-s3")) return StorageUrl.DEFAULT_REGION
    return label.dropLast(3).ifEmpty { StorageUrl.DEFAULT_REGION }
}
