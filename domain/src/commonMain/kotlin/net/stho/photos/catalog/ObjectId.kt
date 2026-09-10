package net.stho.photos.catalog

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import net.stho.photos.storage.sha256Hex

/**
 * What names a blob: `blob/<this>` (§2).
 *
 * Two shapes, and which one a key has says who wrote it. **A hash is content**, minted by the
 * laptop from bytes it holds, and every blob an `encoded` album references is one. **A UUID is
 * a placeholder**, minted by the phone for an object it is uploading and cannot hash — §8's
 * background session must start sending before it has read the asset, which is the whole reason
 * §2 rejected content addressing in the first place. The laptop re-uploads under a hash when it
 * pulls, and the placeholder is deleted, so a temp id is always transient.
 *
 * A value class over the key text rather than a parsed type: nothing in the zone interprets a
 * key beyond comparing it, and keeping the text is what lets both shapes coexist without the
 * reader caring which it has.
 */
@JvmInline
public value class ObjectId private constructor(private val text: String) {

    override fun toString(): String = text

    /** Whether this names content. False for a phone placeholder awaiting its re-upload. */
    public val isContentAddressed: Boolean get() = text.length == HEX_LENGTH

    public companion object {
        /** SHA-256, lowercase hex. */
        private const val HEX_LENGTH: Int = 64

        /** The id of these bytes. Two callers with the same bytes get the same id (§2). */
        public fun ofContent(bytes: ByteArray): ObjectId = ObjectId(bytes.sha256Hex())

        /** The id of this file's contents, streamed so a 200 MB video never lands in memory. */
        public fun ofContent(path: Path): ObjectId = ObjectId(path.sha256Hex())

        /** A placeholder for an object whose bytes are not in hand. §8's upload, and nothing else. */
        public fun temporary(): ObjectId = ObjectId(Uuid.random().toString())

        /**
         * The id in a key, or null for anything that is not one.
         *
         * Deliberately strict, and stricter than the UUID-only parser it replaces: the sweep
         * deletes what no shard references, so a key it cannot read must be skipped rather than
         * guessed at. Only 64 lowercase hex characters, or a well-formed UUID, are ids.
         */
        public fun parse(text: String): ObjectId? = when {
            text.length == HEX_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' } -> ObjectId(text)
            runCatching { Uuid.parse(text) }.isSuccess -> ObjectId(text)
            else -> null
        }
    }
}
