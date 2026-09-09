package net.stho.photos

import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * An absolute scratch root for tests that need real files.
 *
 * `SystemTemporaryDirectory` is not guaranteed absolute on Kotlin/Native, and when it is not,
 * every scratch path resolves against the test's working directory — which is the module
 * directory. That is not a tidiness problem: three such files were committed to this repository
 * before anyone noticed, one of them a 307 KB binary, because they looked like ordinary
 * untracked output to `git add -A`.
 *
 * Resolving it once, here, is what stops a test's leftovers from being indistinguishable from
 * source.
 */
internal val scratchRoot: Path =
    SystemTemporaryDirectory.takeIf { it.isAbsolute } ?: Path("/tmp")

/** A unique scratch path under [scratchRoot]. Callers still delete what they create. */
internal fun scratchPath(label: String): Path =
    Path(scratchRoot, "photos-tests-$label-${kotlin.random.Random.nextLong().toString(16)}")
