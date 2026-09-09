package net.stho.photos.library

import kotlin.text.CharacterCodingException
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import net.stho.photos.IgnoreRulesUnreadableFailure

/** One line of a `.photosignore`, parsed. */
public data class IgnoreRule(
    /** Folded, with any trailing `/` removed. */
    val pattern: String,
    /** Written with a trailing `/`: matches directories, which are not descended into. */
    val isDirectory: Boolean,
    /**
     * Contains `/`: matched against the path relative to the library root. Otherwise it is
     * matched against the file's own name, at any depth.
     */
    val isAnchored: Boolean,
    /** The line as written, for reporting. */
    val source: String,
    val line: Int,
)

/**
 * The contents of a library's `.photosignore`.
 *
 * The tool carries no built-in exclusions at all — not an extension list, not a name list, not
 * even a dot-file rule. The one thing it always skips is `.photosignore` itself. That is
 * deliberate: what counts as junk is a fact about a particular library, and baking this library's
 * digiKam artefacts into a module DESIGN presents as reusable is the same mixing of concerns
 * `INGEST.md` exists to prevent.
 *
 * The consequence is stated plainly because it is sharp: **with no file, nothing is excluded**.
 * On a library whose `.dtrash/` holds deleted-but-decodable photos, that means uploading them,
 * and §2 has no undelete.
 */
public data class IgnoreRules(val rules: List<IgnoreRule> = emptyList()) {

    public val isEmpty: Boolean get() = rules.isEmpty()

    /**
     * The index of the first rule excluding this entry, or null.
     *
     * Returning the index rather than a `Boolean` is what lets the caller count rule usage, and
     * therefore report a rule that matched nothing — which is the only way a typo like `*.pds` is
     * distinguishable from a rule that is simply not needed today.
     */
    public fun matchIndex(name: String, relativePath: String, isDirectory: Boolean): Int? {
        val foldedName = fold(name)
        val foldedPath = fold(relativePath)
        return rules.indices.firstOrNull { index ->
            val rule = rules[index]
            // A directory pattern never excludes a file: `.dtrash/` should not match a stray
            // file called `.dtrash`.
            if (rule.isDirectory && !isDirectory) return@firstOrNull false
            val subject = if (rule.isAnchored) foldedPath else foldedName
            subject.matchesGlob(rule.pattern)
        }
    }

    public companion object {
        /** The filename, at the library root and nowhere else. */
        public const val FILENAME: String = ".photosignore"

        public fun parse(text: String): IgnoreRules {
            val rules = text.split('\n').mapIndexedNotNull { offset, raw ->
                val line = raw.trim(' ', '\t')
                if (line.isEmpty() || line.startsWith('#')) return@mapIndexedNotNull null

                val isDirectory = line.endsWith('/')
                val body = if (isDirectory) line.dropLast(1) else line
                if (body.isEmpty()) return@mapIndexedNotNull null

                IgnoreRule(
                    pattern = fold(body),
                    isDirectory = isDirectory,
                    isAnchored = '/' in body,
                    source = line,
                    line = offset + 1,
                )
            }
            return IgnoreRules(rules)
        }

        /**
         * Case-folded, and nothing else.
         *
         * Unicode composition is deliberately **not** folded, which a pattern typed on a Mac
         * (commonly NFD) would otherwise need to match an NFC path. Measured on the real library,
         * 34,729 entries carry 51 non-ASCII names and not one of them is decomposed, so the
         * normalisation would buy nothing and cost a rule that is hard to predict. Names are
         * compared exactly as the filesystem gives them.
         */
        internal fun fold(text: String): String = text.lowercase()
    }
}

/**
 * Loads the rules for the library rooted at this path.
 *
 * Absent is not an error — it means no exclusions, per the rule above. *Unreadable* is a
 * different thing entirely: the file exists, so exclusions were intended, and proceeding without
 * them silently changes what gets uploaded. §7 already aborts a run on the byte-size mismatch for
 * the same reason.
 *
 * @throws IgnoreRulesUnreadableFailure when the file exists and cannot be read as UTF-8.
 */
public fun Path.readIgnoreRules(): IgnoreRules {
    val file = Path(this, IgnoreRules.FILENAME)
    if (SystemFileSystem.metadataOrNull(file) == null) return IgnoreRules()
    val bytes = try {
        SystemFileSystem.source(file).buffered().use { it.readByteArray() }
    } catch (e: IOException) {
        throw IgnoreRulesUnreadableFailure(file.toString(), e.message ?: "unreadable")
    }
    // Strictly, rather than with replacement characters: a file that is not UTF-8 is a file
    // whose rules nobody can be sure of, and silently mangling one rule is exactly the failure
    // the two tiers exist to keep apart.
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw IgnoreRulesUnreadableFailure(file.toString(), e.message ?: "not valid UTF-8")
    }
    return IgnoreRules.parse(text)
}
