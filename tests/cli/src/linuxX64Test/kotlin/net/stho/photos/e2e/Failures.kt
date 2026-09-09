package net.stho.photos.e2e

/**
 * What a failed scenario says.
 *
 * These are `AssertionError` subclasses carrying structured data rather than strings built at
 * the call site, so the assertion reads declaratively and the rendering lives in one place.
 *
 * **The message is rendered eagerly, into the base constructor, deliberately.** Overriding
 * `val message` as a computed getter yields `null` on Kotlin/Native: the reporter reads the
 * field rather than calling the getter. Verified, not assumed.
 *
 * `:tests:cli` also sets `testLogging { exceptionFormat = FULL }`, without which Gradle's
 * console prints only the class name. The text reaches the XML report either way.
 */

/** The zone did not hold the albums and photos the scenario declared. */
internal class ZoneMismatch(
    scenario: String,
    declared: Map<String, List<String>>,
    actual: Map<String, List<String>>,
) : AssertionError(render(scenario, declared, actual)) {

    private companion object {
        fun render(
            scenario: String,
            declared: Map<String, List<String>>,
            actual: Map<String, List<String>>,
        ): String = buildString {
            appendLine("$scenario: the zone is not what was declared.")
            appendLine()
            for (album in (declared.keys + actual.keys).sorted()) {
                val want = declared[album]
                val got = actual[album]
                when {
                    want == null -> appendLine("  album $album  UNDECLARED -- present in the zone")
                    got == null -> appendLine("  album $album  MISSING -- declared, not in the zone")
                    else -> appendLine("  album $album")
                }
                for (file in ((want ?: emptyList()) + (got ?: emptyList())).distinct().sorted()) {
                    val inWant = want?.contains(file) == true
                    val inGot = got?.contains(file) == true
                    val mark = when {
                        inWant && inGot -> "    ok      "
                        inWant -> "    MISSING "
                        else -> "    EXTRA   "
                    }
                    appendLine("$mark$file")
                }
            }
        }
    }
}

/**
 * A derivative was present but is not what §5 says it should be.
 *
 * Names the libraries that produced it, deliberately: `expected 2048, was 1536` sends the reader
 * to the assertion's source, whereas naming libheif sends them to the encoder.
 */
internal class DerivativeWrong(
    what: String,
    subject: String,
    producedBy: String,
    expected: String,
    actual: String,
) : AssertionError(
    buildString {
        appendLine("$what for $subject is not what DESIGN §5 specifies.")
        appendLine("  produced by  $producedBy")
        appendLine("  expected     $expected")
        appendLine("  actual       $actual")
    },
)

/** The zone holds blobs nothing references, or references blobs that are not there. */
internal class BlobsStranded(
    scenario: String,
    orphans: Set<String>,
    dangling: Set<String>,
    empty: Set<String>,
) : AssertionError(
    buildString {
        appendLine("$scenario: the zone's blobs and its shards disagree.")
        if (orphans.isNotEmpty()) {
            appendLine("  orphaned -- present under blob/, referenced by no shard:")
            orphans.sorted().forEach { appendLine("    $it") }
        }
        if (dangling.isNotEmpty()) {
            appendLine("  dangling -- referenced by a shard, absent from blob/:")
            dangling.sorted().forEach { appendLine("    $it") }
        }
        if (empty.isNotEmpty()) {
            appendLine("  empty -- present but zero bytes:")
            empty.sorted().forEach { appendLine("    $it") }
        }
    },
)

/** A file the scenario spoke about is not in the state it declared. */
internal class LibraryWrong(scenario: String, claims: List<String>) :
    AssertionError(
        buildString {
            appendLine("$scenario: the library is not what was declared.")
            claims.forEach { appendLine("  $it") }
        },
    )

/** The binary exited with a code the scenario did not declare. */
internal class ExitWrong(scenario: String, command: String, expected: Int, actual: Int, output: String) :
    AssertionError(
        buildString {
            appendLine("$scenario: `$command` exited $actual, expected $expected.")
            if (output.isNotBlank()) {
                appendLine("  its output was:")
                output.trimEnd().lines().forEach { appendLine("    $it") }
            }
        },
    )
