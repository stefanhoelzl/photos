package net.stho.photos.library

/**
 * `fnmatch(pattern, subject, FNM_PATHNAME)`, which is what DESIGN §7 defines `.photosignore`
 * matching as.
 *
 * Written out rather than bound, because there is no `fnmatch` in `commonMain` — and binding one
 * per platform would put the rule that decides what gets uploaded behind an `expect`/`actual`
 * pair, where the two sides could disagree. The whole point of the domain module is that they
 * cannot.
 *
 * The one flag that matters is **`FNM_PATHNAME`**: `*`, `?` and a bracket expression never match
 * `/`, so a pattern is confined to one path component and an anchored rule naming `Album` and a
 * `.psd` wildcard cannot reach into `Album/Sub/`. That is why [IgnoreRules.matchIndex] has to
 * match an unanchored pattern against the *name* explicitly — `*.db` would otherwise never match
 * `Album/stray.db`.
 *
 * `FNM_PERIOD` is deliberately **not** set, matching the original: a leading dot is an ordinary
 * character, so `*` matches `.DS_Store`. There is no built-in dot rule anywhere in this tool, and
 * a wildcard that quietly refused to see hidden files would be one.
 *
 * Checked against glibc's own `fnmatch` over every pattern of up to four characters drawn from
 * `ab/.*?[]-!^\` against every subject of up to three, 5.8 million pairs, and it agrees on all of
 * them but two families, both deliberate:
 *
 *  - **POSIX bracket sub-expressions** — `[[:digit:]]`, `[[.a.]]`, `[[=a=]]` — are not supported.
 *    §7 documents the syntax as `*`, `?` and `[abc]`, and glibc's extra forms are treated here as
 *    brackets holding those literal characters.
 *  - **A separator escaped after a star** — `*\/` — matches in this implementation and does not in
 *    glibc, whose star scans forward for the segment end and does not see a quoted `/` as one. A
 *    `.photosignore` has no reason to quote a separator, and the reading here is the one the
 *    syntax describes.
 */
internal fun String.matchesGlob(pattern: String): Boolean = match(pattern, 0, this, 0)

private const val SEPARATOR = '/'

private fun match(pattern: String, patternFrom: Int, subject: String, subjectFrom: Int): Boolean {
    var p = patternFrom
    var s = subjectFrom
    while (p < pattern.length) {
        when (val token = pattern[p]) {
            '*' -> {
                // A run of `*` is one wildcard; collapsing it keeps the backtracking below linear
                // in the pattern rather than exponential in the number of stars.
                while (p < pattern.length && pattern[p] == '*') p++
                var i = s
                while (true) {
                    if (match(pattern, p, subject, i)) return true
                    // The star stops at a separator, and so does the search.
                    if (i == subject.length || subject[i] == SEPARATOR) return false
                    i++
                }
            }

            '?' -> {
                if (s == subject.length || subject[s] == SEPARATOR) return false
                p++
                s++
            }

            '[' -> {
                val close = bracketEnd(pattern, p)
                if (close == null) {
                    // An unterminated `[` is an ordinary character, as it is in fnmatch — the
                    // alternative, refusing the rule, would silently exclude nothing.
                    if (s == subject.length || subject[s] != '[') return false
                    p++
                    s++
                } else {
                    if (s == subject.length || subject[s] == SEPARATOR) return false
                    if (!bracketMatches(pattern, p, close, subject[s])) return false
                    p = close + 1
                    s++
                }
            }

            '\\' -> {
                // fnmatch without `FNM_NOESCAPE`: a backslash quotes the next character. A
                // trailing one has nothing to quote and matches nothing at all, which is what
                // glibc does — a rule ending in a stray backslash then shows up in the
                // unused-rule report rather than quietly excluding files ending in `\`.
                if (p + 1 == pattern.length) return false
                val literal = pattern[p + 1]
                if (s == subject.length || subject[s] != literal) return false
                p += 2
                s++
            }

            else -> {
                if (s == subject.length || subject[s] != token) return false
                p++
                s++
            }
        }
    }
    return s == subject.length
}

/**
 * The index of the `]` closing the bracket opened at [open], or null when there is none.
 *
 * A `!`/`^` negation and a `]` written first are members rather than the close, which is how
 * `[]]` names a literal `]`.
 */
private fun bracketEnd(pattern: String, open: Int): Int? {
    var i = open + 1
    if (i < pattern.length && (pattern[i] == '!' || pattern[i] == '^')) i++
    if (i < pattern.length && pattern[i] == ']') i++
    while (i < pattern.length) {
        when (pattern[i]) {
            '\\' -> i += 2
            ']' -> return i
            else -> i++
        }
    }
    return null
}

/** Whether [ch] is in the bracket expression spanning `[open, close]`. */
private fun bracketMatches(pattern: String, open: Int, close: Int, ch: Char): Boolean {
    var i = open + 1
    var negated = false
    if (i < close && (pattern[i] == '!' || pattern[i] == '^')) {
        negated = true
        i++
    }

    var matched = false
    while (i < close) {
        var low = pattern[i]
        if (low == '\\' && i + 1 < close) {
            i++
            low = pattern[i]
        }
        i++
        // `-` is a range only between two members; last in the bracket it is a plain character.
        if (i + 1 < close && pattern[i] == '-') {
            i++
            var high = pattern[i]
            if (high == '\\' && i + 1 < close) {
                i++
                high = pattern[i]
            }
            i++
            if (ch in low..high) matched = true
        } else {
            if (ch == low) matched = true
        }
    }
    return matched != negated
}
