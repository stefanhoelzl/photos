@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.posix.ECHO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.TCSAFLUSH
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.isatty
import platform.posix.memcpy
import platform.posix.stderr
import platform.posix.stdout
import platform.posix.tcgetattr
import platform.posix.tcsetattr
import platform.posix.termios

/**
 * Output that reads the same in a terminal and in the journal.
 *
 * One line per fact, no ANSI and no carriage returns on stdout, so `journalctl` stays greppable.
 * The only thing that redraws is a progress counter, and it goes to stderr and only when stdout
 * is a terminal — so an unattended run's log is exactly the facts (§7).
 *
 * Both sinks are injected for the same reason [net.stho.photos.adapter.linux.XdgPaths] injects
 * the environment: the end-of-run block is a contract worth asserting on, and a test that has to
 * capture a file descriptor to read it would assert on the plumbing instead.
 */
internal class Console(
    private val isTerminal: Boolean = isatty(STDOUT_FILENO) == 1,
    private val out: (String) -> Unit = ::writeStandardOutput,
    private val err: (String) -> Unit = ::writeStandardError,
) {

    fun line(text: String) {
        out(text + "\n")
    }

    /** Something a person should see but that did not stop the run. */
    fun note(text: String) {
        line("! $text")
    }

    fun error(text: String) {
        err("photos-cli: $text\n")
    }

    /** A single redrawing line, only on a terminal. Never part of the record. */
    fun progress(text: String) {
        if (isTerminal) err("\u001B[2K\r$text")
    }

    fun clearProgress() {
        if (isTerminal) err("\u001B[2K\r")
    }

    /**
     * Asks for one value on the terminal.
     *
     * The prompt goes to stderr so that piping stdout somewhere does not swallow it, and so
     * `photos-cli login < answers` still works for anyone who wants it scripted.
     */
    fun ask(prompt: String, secret: Boolean = false): String? {
        err(prompt)
        val text = if (secret) withoutEcho(::readlnOrNull) else readlnOrNull()
        if (secret) err("\n")
        return text
    }
}

/**
 * Reads with the terminal's echo turned off, and turns it back on however we leave.
 *
 * Not conditional on whether stdout is a terminal: that is a different descriptor, and the
 * password is read from stdin, which may be a terminal when stdout is a pipe. `tcgetattr`
 * failing is the answer for the case where stdin is not a terminal at all.
 */
private fun <T> withoutEcho(body: () -> T): T = memScoped {
    val original = alloc<termios>()
    if (tcgetattr(STDIN_FILENO, original.ptr) != 0) return@memScoped body()
    val quiet = alloc<termios>()
    memcpy(quiet.ptr, original.ptr, sizeOf<termios>().convert())
    quiet.c_lflag = quiet.c_lflag and ECHO.toUInt().inv()
    tcsetattr(STDIN_FILENO, TCSAFLUSH, quiet.ptr)
    try {
        body()
    } finally {
        tcsetattr(STDIN_FILENO, TCSAFLUSH, original.ptr)
    }
}

/**
 * Unbuffered writes, so a 39-hour run's journal is current rather than a page behind.
 *
 * `print` would do for stdout and nothing at all would do for stderr, so both go through the
 * same two lines instead of one of each.
 */
private fun writeStandardOutput(text: String) {
    fputs(text, stdout)
    fflush(stdout)
}

private fun writeStandardError(text: String) {
    fputs(text, stderr)
    fflush(stderr)
}
