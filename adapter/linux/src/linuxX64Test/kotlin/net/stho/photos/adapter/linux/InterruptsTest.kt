@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.raise

/**
 * That a signal actually reaches the run as something it can act on.
 *
 * The handler is C, and the C is exercised by the pipe it writes to — which is the only part of
 * the self-pipe trick that can be observed from Kotlin at all. What cannot be tested in-process
 * is the second press, because that one is the kernel terminating this test.
 *
 * Every test closes its instance: the handler restores the default disposition on the way past,
 * so an instance left installed would leave the next test's `SIGTERM` fatal.
 */
class InterruptsTest {

    @Test
    fun anInterruptArrivesAsTheSignalThatCausedIt() = runBlocking {
        PosixInterrupts(poll = 1.milliseconds).use { interrupts ->
            assertTrue(interrupts.installed)

            raise(SIGINT)

            val interrupted = withTimeout(5.seconds) { interrupts.awaitInterrupt() }
            assertEquals(SIGINT, interrupted.signal)
        }
    }

    /** `systemctl stop` and a shutdown are `SIGTERM`, and are not a different kind of event. */
    @Test
    fun terminationIsAnInterruptToo() = runBlocking {
        PosixInterrupts(poll = 1.milliseconds).use { interrupts ->
            raise(SIGTERM)

            val interrupted = withTimeout(5.seconds) { interrupts.awaitInterrupt() }
            assertEquals(SIGTERM, interrupted.signal)
        }
    }

    /**
     * A run nobody interrupted must not be stopped by one, which is worth stating: the poll
     * returns "nothing yet" and "the pipe is broken" as different values, and reading either as
     * a signal would end a 39-hour import on its own.
     */
    @Test
    fun nothingArrivesWhenNoSignalIsSent() = runBlocking {
        PosixInterrupts(poll = 1.milliseconds).use { interrupts ->
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(200.milliseconds) { interrupts.awaitInterrupt() }
            }
        }
        Unit
    }
}
