package net.stho.photos.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.PlaintextHelpFormatter
import com.github.ajalt.clikt.parameters.options.versionOption
import kotlin.system.exitProcess

/** The version `--version` reports. */
private const val VERSION = "1.0.0"

/**
 * `photos-cli`.
 *
 * Argument parsing and wiring over the domain, and no judgement about the library (§7). Everything
 * that decides what the zone should contain lives in `:domain`; this module's whole job is to
 * choose an adapter for each port and hand them over.
 *
 * `CoreCliktCommand` rather than the `clikt` umbrella's `CliktCommand`: the umbrella pulls in
 * mordant, which duplicates a symbol on Kotlin/Native and fails the link. Nothing here wants a
 * styled terminal anyway — §7 asks for output that reads the same in a terminal and in the journal.
 */
internal class PhotosCli : CoreCliktCommand("photos-cli") {

    init {
        versionOption(VERSION)


        // Plain text, and defaults shown. Subcommands inherit this context, so it is set once.
        // `--jobs` defaults to the core count and `--upload-jobs` to one for reasons §7 measured;
        // a help page that hides both leaves the reader guessing at the two numbers most likely to
        // be worth changing.
        context { helpFormatter = { PlaintextHelpFormatter(it, showDefaultValues = true) } }
    }

    override fun help(context: Context): String =
        "Keeps a bunny.net storage zone in step with a local photo library."

    override fun helpEpilog(context: Context): String =
        "The library is the master copy and the only way to say anything. Adding a folder adds " +
            "an album; deleting a file deletes its photo; `rm -rf` on an album deletes the " +
            "album. There is no confirmation step — `--dry-run` is the one place to look before " +
            "it happens. A run refuses to touch anything unless \$LIBRARY_ROOT/.photosignore " +
            "exists and is readable. That file is what says the directory really is the " +
            "library, so an unmounted disk or a mistyped root cannot be mistaken for a library " +
            "whose every album was deleted. A library that wants no exclusions writes an empty one."

    override fun run(): Unit = Unit
}

/**
 * The entry point, and the one place a process exit code is chosen.
 *
 * Clikt's own `main` would do everything here except the last line: it exits `1` for a usage error,
 * and §7 gives usage its own number so that `1` can keep meaning "the run finished and something in
 * the zone changed".
 */
public fun main(args: Array<String>) {
    val command = PhotosCli().subcommands(SyncCommand(), LoginCommand(), LogoutCommand())
    try {
        command.parse(args)
    } catch (error: CliktError) {
        command.echoFormattedHelp(error)
        exitProcess(exitCodeFor(error))
    }
}
