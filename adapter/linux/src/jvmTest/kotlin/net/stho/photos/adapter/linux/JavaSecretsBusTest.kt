package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.stho.photos.ports.KeyringRead

/**
 * The desktop app's keyring client against a real bus.
 *
 * `SecretServiceTest` does the same for the CLI's cinterop transport. Both drive the *same*
 * `DbusKeyring`, which is the point of the port: §1's three outcomes are decided once, and
 * these two suites check that each transport feeds it the right facts.
 */
class JavaSecretsBusTest {

    @Test
    fun readsAStoredSecret() = withStub { stub, environment ->
        stub.store(field = PASSWORD, secret = "hunter2")
        assertEquals(
            KeyringRead.Found("hunter2"),
            desktopKeyring(environment = environment).read(PASSWORD),
        )
    }

    @Test
    fun aKeyringThatHoldsNothingIsAbsentNotUnavailable() = withStub { _, environment ->
        // The distinction §1 spends an exit code on: *absent* is a real error (3) and
        // *unavailable* is a deferral (75), and conflating them turns "you have not logged in
        // yet" into an hourly page.
        assertEquals(KeyringRead.Absent, desktopKeyring(environment = environment).read(PASSWORD))
    }

    @Test
    fun noSessionBusIsUnavailable() {
        val read = desktopKeyring(environment = { null }).read(PASSWORD)
        val unavailable = assertIs<KeyringRead.Unavailable>(read)
        assertTrue("no session bus" in unavailable.reason, unavailable.reason)
    }

    @Test
    fun aLockedCollectionIsUnavailableRatherThanAbsent() = withStub { stub, environment ->
        stub.store(field = PASSWORD, secret = "hunter2")
        stub.lock()
        assertIs<KeyringRead.Unavailable>(desktopKeyring(environment = environment).read(PASSWORD))
    }

    @Test
    fun writesRoundTripThroughTheWireBytesIntact() = withStub { stub, environment ->
        // A non-ASCII secret, because the value crosses as `ay` and a client that treated it as
        // a string would corrupt it somewhere this test can see.
        val secret = "hünter2-ßæ"
        desktopKeyring(environment = environment).write(PASSWORD, secret)
        assertEquals(secret, stub.item(PASSWORD)?.secret?.decodeToString())
        assertEquals(KeyringRead.Found(secret), desktopKeyring(environment = environment).read(PASSWORD))
    }

    @Test
    fun writingTwiceReplacesRatherThanAdding() = withStub { stub, environment ->
        val keyring = desktopKeyring(environment = environment)
        keyring.write(PASSWORD, "first")
        keyring.write(PASSWORD, "second")
        // Two items with one set of attributes would make the password a coin flip.
        assertEquals(KeyringRead.Found("second"), keyring.read(PASSWORD))
    }

    @Test
    fun removeTakesTheItemAwayAndRemovingNothingIsNotAnError() =
        withStub { stub, environment ->
            val keyring = desktopKeyring(environment = environment)
            keyring.write(PASSWORD, "hunter2")
            keyring.remove(PASSWORD)
            assertNull(stub.item(PASSWORD))
            // The gesture means *make sure it is gone*, and afterwards it is.
            keyring.remove(PASSWORD)
        }

    private fun withStub(body: (SecretServiceStub, (String) -> String?) -> Unit) {
        val bus = PrivateBus.openOrNull()
        if (bus == null) {
            println("dbus-daemon not available — skipping the JVM Secret Service tests")
            return
        }
        bus.use { running ->
            SecretServiceStub.on(running).use { stub -> body(stub, running.environment) }
        }
    }

    private companion object {
        const val PASSWORD = "password"
    }
}
