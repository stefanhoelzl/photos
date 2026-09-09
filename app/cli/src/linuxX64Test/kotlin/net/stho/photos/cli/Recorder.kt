package net.stho.photos.cli

/**
 * A [Console] whose two streams are lists.
 *
 * Both sinks are constructor parameters for exactly this: the end-of-run block is a contract §7
 * states, and asserting on it should not mean capturing a file descriptor. `isTerminal` is false,
 * which is the case that matters — an unattended run's record is exactly the facts, with no
 * redrawing counter in it.
 */
internal class Recorder {
    val out = mutableListOf<String>()
    val err = mutableListOf<String>()

    val console: Console = Console(
        isTerminal = false,
        out = { out += it.trimEnd('\n') },
        err = { err += it.trimEnd('\n') },
    )
}
