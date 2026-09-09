package net.stho.photos.storage

import net.stho.photos.S3HttpFailure
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * Parses S3's XML error body into the failure §1 promises to show.
 *
 * Best-effort by design: a body that is missing, truncated or not XML at all still yields a
 * failure carrying the status, because the status alone is already an actionable message.
 */
internal fun ByteArray.asS3HttpFailure(
    status: Int,
    key: String? = null,
    requestId: String? = null,
): S3HttpFailure {
    if (isEmpty()) return S3HttpFailure(status = status, key = key, requestId = requestId)
    val fields = decodeToString().xmlFields("Code", "Message", "Key", "RequestId")
    return S3HttpFailure(
        status = status,
        code = fields["Code"],
        detail = fields["Message"],
        key = fields["Key"] ?: key,
        requestId = fields["RequestId"] ?: requestId,
    )
}

/**
 * Pulls the first occurrence of each named element out of a small XML document.
 *
 * Used for error bodies and for the two elements that matter in a multipart response, where the
 * document is a handful of elements. Whatever was read before a malformed document gave out is
 * kept: an error body is a courtesy, and half of one still beats none.
 */
internal fun String.xmlFields(vararg names: String): Map<String, String> {
    val wanted = names.toSet()
    val found = mutableMapOf<String, String>()
    var current: String? = null
    val buffer = StringBuilder()

    try {
        val reader = xmlStreaming.newGenericReader(this)
        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> {
                    current = reader.localName.takeIf { it in wanted && it !in found }
                    buffer.clear()
                }
                EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF ->
                    if (current != null) buffer.append(reader.text)
                EventType.END_ELEMENT -> {
                    if (current == reader.localName) found[reader.localName] = buffer.toString()
                    current = null
                    buffer.clear()
                }
                else -> Unit
            }
        }
    } catch (_: Exception) {
        // Deliberately swallowed, and deliberately broad: xmlutil raises several types for
        // malformed input, and every one of them means "there was nothing more to read here".
        // The LIST parse, where a short read *is* dangerous, does not come through this path.
    }
    return found
}
