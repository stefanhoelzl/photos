@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.e2e

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen

/** What running the shipped binary produced. */
internal data class Run(val command: String, val exitCode: Int, val output: String)

/**
 * Runs the shipped `photos-cli` with [args], in an environment holding only [environment].
 *
 * `popen` rather than `fork`/`exec`: it gives the exit status and the merged output in one call,
 * and forking a Kotlin/Native process to run `exec` in the child means running Kotlin between
 * the two, which is exactly where a forked runtime is least defined.
 *
 * `env -i` is deliberate. The CLI reads `HOME`, `PHOTOS_PASSWORD` and `PHOTOS_ENDPOINT`; a
 * scenario that means "no endpoint configured" has to be able to get one, and an inherited
 * variable from the developer's shell would quietly point the run at a real zone.
 */
internal fun runCli(vararg args: String, environment: Map<String, String>): Run {
    val binary = requireNotNull(getenv("PHOTOS_CLI_BINARY")?.toKString()) {
        "PHOTOS_CLI_BINARY is unset -- the Gradle task is what supplies it"
    }
    val assignments = environment.entries.joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" }
    val arguments = args.joinToString(" ") { shellQuote(it) }
    val command = "env -i $assignments ${shellQuote(binary)} $arguments"

    val output = StringBuilder()
    val pipe = requireNotNull(popen("$command 2>&1", "r")) { "cannot start $command" }
    memScoped {
        val line = allocArray<ByteVar>(BUFFER)
        while (fgets(line, BUFFER, pipe) != null) output.append(line.toKString())
    }
    // `pclose` returns a wait status, not an exit code: the low byte says how it died and the
    // next one carries the code. This is what WEXITSTATUS does, which cinterop does not expose
    // because it is a macro.
    val status = pclose(pipe)
    val exitCode = if (status < 0) status else (status shr 8) and 0xFF

    return Run(args.joinToString(" "), exitCode, output.toString())
}

/** Single quotes, with embedded quotes closed and reopened -- the only escape `sh` honours. */
private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private const val BUFFER = 4096
