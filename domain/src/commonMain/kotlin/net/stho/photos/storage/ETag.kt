package net.stho.photos.storage

import kotlin.jvm.JvmInline

/**
 * An object's entity tag.
 *
 * Deliberately opaque: never parsed, never compared against a computed MD5. Multipart ETags are
 * not MD5s at all (they carry a `-<partCount>` suffix), and bunny.net is free to use any scheme
 * it likes. Treating the value as a token means none of that matters.
 *
 * A distinct type rather than `String` so it cannot be mixed up with a key, and so the
 * quoted/unquoted distinction is handled in exactly one place — S3 returns ETags quoted, and a
 * stray pair of quotes on one side of a comparison would make the sync diff re-download the
 * entire library.
 *
 * [value] is always unquoted; a header value becomes one through [asETag].
 */
@JvmInline
public value class ETag(public val value: String) {
    /** Re-quoted, ready for `If-Match` / `If-None-Match`. */
    public val headerValue: String get() = "\"$value\""

    override fun toString(): String = value
}

/** Reads a raw header value, e.g. `"d41d8cd9…"` or `W/"d41d8cd9…"`. */
public fun String.asETag(): ETag {
    var text = trim()
    if (text.startsWith("W/")) text = text.substring(2)
    if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
        text = text.substring(1, text.length - 1)
    }
    return ETag(text)
}
