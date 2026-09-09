package net.stho.photos.adapter.linux

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.fixtures.deleteTree
import net.stho.photos.fixtures.readBytes
import net.stho.photos.fixtures.scratchDirectory
import net.stho.photos.fixtures.write
import platform.posix.SIGTERM
import platform.posix.kill
import platform.posix.system
import platform.posix.usleep

/**
 * A `dbus-daemon` of the test's own, with nothing on it but the stub.
 *
 * A private bus rather than the real session bus, because the stub owns the well-known name
 * `org.freedesktop.secrets` and the developer's own keyring already owns it on the real one. It
 * also means these tests can never read or write the developer's actual credentials, which is
 * not a property worth leaving to care.
 *
 * `dbus-daemon` is an external binary, exactly as `java` is for S3Mock, and it is handled the
 * same way: absent, these tests skip and the rest still run.
 *
 * **The address is chosen rather than read back.** The daemon is told to listen on a socket
 * inside a scratch directory we made, so there is no pipe to drain and no first line to parse —
 * readiness is "the socket accepts a connection", which is the thing the test actually needs to
 * be true.
 */
internal class PrivateBus private constructor(
    internal val address: String,
    private val pid: Int,
    private val directory: Path,
) : AutoCloseable {

    /** What a [DbusKeyring] should see instead of the developer's real session bus. */
    internal val environment: (String) -> String? = { name ->
        if (name == "DBUS_SESSION_BUS_ADDRESS") address else null
    }

    override fun close() {
        kill(pid, SIGTERM)
        awaitExit()
        deleteTree(directory)
    }

    /**
     * Waits for the daemon to actually go, so the directory being removed is not one it is
     * still writing to. `waitpid` is no use here — the shell that launched it is long gone and
     * the daemon was reparented — so this asks the kernel whether the pid is still signalable.
     */
    private fun awaitExit() {
        var waited = 0L
        while (waited < EXIT_TIMEOUT_MICROSECONDS && kill(pid, 0) == 0) {
            usleep(POLL_MICROSECONDS)
            waited += POLL_MICROSECONDS.toLong()
        }
    }

    internal companion object {
        private const val DAEMON = "/usr/bin/dbus-daemon"
        private const val READY_TIMEOUT_MICROSECONDS = 5_000_000L
        private const val EXIT_TIMEOUT_MICROSECONDS = 2_000_000L
        private const val POLL_MICROSECONDS = 20_000u

        /**
         * Null when a private bus can be started here, and the reason it cannot otherwise.
         *
         * Computed once by starting one and shutting it down again — the only honest test of
         * "can this machine run these tests". Each test still takes a bus of its own: a
         * well-known name has exactly one owner, so two stubs on one bus would mean the second
         * silently answering nothing while the first served the previous test's contents.
         */
        internal val unavailableReason: String? by lazy {
            try {
                start().close()
                null
            } catch (failure: Throwable) {
                failure.message ?: "dbus-daemon could not be started (is dbus installed?)"
            }
        }

        internal fun start(): PrivateBus {
            check(SystemFileSystem.exists(Path(DAEMON))) { "$DAEMON is not installed" }

            val directory = scratchDirectory("bus")
            val socket = Path(directory, "bus")
            val address = "unix:path=$socket"
            val configuration = Path(directory, "bus.conf").write(configuration(socket))
            val pidFile = Path(directory, "pid")

            // Its own socket, its own policy, no services directory: nothing on this bus but
            // the stub and whatever a test connects.
            //
            // `system` rather than fork/exec by hand: the fork happens entirely inside libc,
            // which is what makes it safe to do from a runtime that has threads of its own. The
            // shell backgrounds the daemon and writes down its pid, which is all we need to
            // stop it again.
            val launched = system(
                "$DAEMON --config-file=$configuration --nofork >/dev/null 2>&1 & echo \$! > $pidFile",
            )
            check(launched == 0) { "could not start $DAEMON (sh exited $launched)" }

            val pid = pidFile.readBytes().decodeToString().trim().toIntOrNull()
                ?: error("$DAEMON was started but wrote no pid")
            val bus = PrivateBus(address, pid, directory)
            try {
                bus.awaitReady(socket)
            } catch (failure: Throwable) {
                bus.close()
                throw failure
            }
            return bus
        }

        private fun configuration(socket: Path): ByteArray =
            """
            <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
             "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
            <busconfig>
              <type>session</type>
              <listen>unix:path=$socket</listen>
              <policy context="default">
                <allow send_destination="*"/>
                <allow own="*"/>
                <allow receive_sender="*"/>
              </policy>
            </busconfig>
            """.trimIndent().encodeToByteArray()
    }

    /**
     * Waits until the daemon answers `Hello`, not merely until the socket file exists: `bind`
     * creates the file before `listen` makes it usable, and a connect in that window fails with
     * a message about the keyring that would be a lie.
     */
    private fun awaitReady(socket: Path) {
        var waited = 0L
        while (waited < READY_TIMEOUT_MICROSECONDS) {
            if (SystemFileSystem.exists(socket)) {
                val connected = try {
                    Bus.open(address).close()
                    true
                } catch (failure: SecretServiceFailure) {
                    false
                }
                if (connected) return
            }
            usleep(POLL_MICROSECONDS)
            waited += POLL_MICROSECONDS.toLong()
        }
        error("dbus-daemon did not accept a connection on $address")
    }
}
