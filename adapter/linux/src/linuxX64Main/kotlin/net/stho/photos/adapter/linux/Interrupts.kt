@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import net.stho.photos.ports.Interrupted
import net.stho.photos.ports.Interrupts
import photossignals.photos_signals_install
import photossignals.photos_signals_poll
import photossignals.photos_signals_surrender
import photossignals.photos_signals_uninstall

/**
 * §7's [Interrupts]: `SIGINT` and `SIGTERM` over the self-pipe trick.
 *
 * The handler is C — see the `photossignals` def in this module's build — because the list of
 * things a signal handler may call is short and Kotlin is not on it. All that reaches Kotlin is a
 * byte on a pipe, which is the whole point of the trick: the awkward part is the two
 * async-signal-safe calls the handler is allowed to make, and everything after it is ordinary code
 * reading a file descriptor.
 *
 * The read end is polled rather than blocked on. Blocking it would need a thread of its own, and a
 * thread parked in `read` is one more thing to wake and join at shutdown — in exchange for a
 * latency nobody can perceive between pressing `^C` and the run saying it is stopping.
 */
public class PosixInterrupts(
    private val poll: Duration = POLL_INTERVAL,
) : Interrupts {

    /**
     * False only when the process is already out of descriptors — `pipe` is the one call here
     * that can fail for an ordinary reason.
     *
     * Not a thrown failure: a run that cannot be interrupted is worth saying out loud, but it is
     * not worth refusing to start over, and every §1 failure this could borrow would say
     * something untrue about what went wrong. [awaitInterrupt] simply never returns instead.
     */
    public val installed: Boolean = photos_signals_install() == 0

    override suspend fun awaitInterrupt(): Interrupted {
        if (!installed) awaitCancellation()
        while (true) {
            val signal = photos_signals_poll()
            if (signal > 0) return Interrupted(signal)
            // A pipe that cannot be read is nothing a run can act on, and spinning on it would
            // be worse than the run merely being uninterruptible.
            if (signal < 0) awaitCancellation()
            delay(poll)
        }
    }

    override fun surrender(interrupted: Interrupted): Nothing {
        photos_signals_surrender(interrupted.signal)
        // Unreachable: the default disposition of both signals handled here is to terminate, so
        // `raise` does not come back. Saying so is what makes the return type `Nothing`.
        error("signal ${interrupted.signal} was raised and did not terminate the process")
    }

    override fun close() {
        photos_signals_uninstall()
    }

    private companion object {
        /**
         * Imperceptible to a person pressing `^C`, and ten wake-ups a second on a run measured in
         * hours.
         */
        val POLL_INTERVAL: Duration = 100.milliseconds
    }
}
