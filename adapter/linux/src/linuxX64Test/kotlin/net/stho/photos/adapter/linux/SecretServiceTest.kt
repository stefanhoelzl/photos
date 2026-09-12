package net.stho.photos.adapter.linux

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.stho.photos.CredentialFailure
import net.stho.photos.fixtures.write
import net.stho.photos.ingest.Credentials
import net.stho.photos.ports.KeyringRead

/**
 * The keyring client against a real bus.
 *
 * These cover what a fake `Keyring` deliberately cannot: the marshalling, the session, and — the
 * part that decides whether an unattended run pages somebody at 3 a.m. — telling a keyring that
 * is not there from one that answers and holds nothing.
 */
class SecretServiceTest {

    @Test
    fun readsAStoredSecret() = withStub { stub, environment ->
        stub.store(field = PASSWORD, secret = "hunter2")
        assertEquals(
            KeyringRead.Found("hunter2"),
            nativeKeyring(environment = environment).read(PASSWORD),
        )
    }

    /** Two items live under one service, so a lookup naming only the service could get either. */
    @Test
    fun fieldsAreToldApart() = withStub { stub, environment ->
        stub.store(field = PASSWORD, secret = "the-password")
        stub.store(field = ENDPOINT, secret = ZONE)
        val keyring = nativeKeyring(environment = environment)
        assertEquals(KeyringRead.Found("the-password"), keyring.read(PASSWORD))
        assertEquals(KeyringRead.Found(ZONE), keyring.read(ENDPOINT))
    }

    /**
     * The distinction §1 rests on. A keyring that answers and holds nothing is a real error
     * (exit 3); everything else about the keyring is a deferral (exit 75). Getting these two the
     * wrong way round either pages somebody hourly or hides a broken install forever.
     */
    @Test
    fun missingItemIsARealError() = withStub { _, environment ->
        assertEquals(KeyringRead.Absent, nativeKeyring(environment = environment).read(PASSWORD))
    }

    @Test
    fun lockedItemDefers() = withStub { stub, environment ->
        stub.store(field = PASSWORD, secret = "hunter2", locked = true)
        val read = nativeKeyring(environment = environment).read(PASSWORD)
        assertIs<KeyringRead.Unavailable>(read, "a locked keyring must not return a secret")
        assertContains(read.reason, "locked")
    }

    /** No bus at all is the state of a machine that has booted but nobody has logged into. */
    @Test
    fun noSessionBusDefers() {
        val keyring = nativeKeyring(environment = { name ->
            if (name == "XDG_RUNTIME_DIR") "/nonexistent" else null
        })
        assertIs<KeyringRead.Unavailable>(
            keyring.read(PASSWORD),
            "a missing bus must not return a secret",
        )
    }

    @Test
    fun storeAndRemoveRoundTrip() = withStub { stub, environment ->
        val keyring = nativeKeyring(environment = environment)
        keyring.write(PASSWORD, "hunter2")
        keyring.write(ENDPOINT, ZONE)

        assertEquals("hunter2", stub.item(PASSWORD)?.text)
        assertEquals(KeyringRead.Found(ZONE), keyring.read(ENDPOINT))

        keyring.remove(PASSWORD)
        keyring.remove(ENDPOINT)
        assertTrue(stub.storedPaths.isEmpty(), "logout must leave nothing behind")
    }

    /**
     * Storing twice must update, not leave two items that `SearchItems` returns in an order
     * nothing defines — which would make the password a coin flip.
     */
    @Test
    fun storingTwiceReplaces() = withStub { stub, environment ->
        val keyring = nativeKeyring(environment = environment)
        keyring.write(PASSWORD, "first")
        keyring.write(ENDPOINT, ZONE)
        keyring.write(PASSWORD, "second")
        keyring.write(ENDPOINT, ZONE)

        assertEquals(2, stub.storedPaths.size)
        assertEquals(KeyringRead.Found("second"), keyring.read(PASSWORD))
    }

    /**
     * Decision: removing nothing is not an error. The gesture means "make sure they are gone",
     * and afterwards they are.
     */
    @Test
    fun removeOnEmptyKeyringSucceeds() = withStub { stub, environment ->
        nativeKeyring(environment = environment).remove(PASSWORD)
        assertTrue(stub.storedPaths.isEmpty())
    }

    /**
     * A reply the spec does not allow is not something an hour will fix, so it aborts (3) rather
     * than deferring (75) — which is why it is thrown rather than returned.
     */
    @Test
    fun malformedReplyAborts() = withStub { stub, environment ->
        stub.store(field = PASSWORD, secret = "hunter2")
        stub.breakEverything()
        assertFailsWith<CredentialFailure.KeyringProtocol> {
            nativeKeyring(environment = environment).read(PASSWORD)
        }
    }
}

// The field attributes the CLI actually uses: items stored by `secret-tool` before this client
// existed are found only if these stay exactly what they were.
private val PASSWORD = Credentials.Field.PASSWORD.attribute
private val ENDPOINT = Credentials.Field.ENDPOINT.attribute

private const val ZONE = "https://de-s3.storage.bunnycdn.com/zone"

/**
 * One bus and one stub per test, so nothing leaks between them — and nothing at all when this
 * machine has no `dbus-daemon`, exactly as the round-trip tests skip without a JVM.
 */
private fun withStub(body: (SecretServiceStub, (String) -> String?) -> Unit) {
    val unavailable = PrivateBus.unavailableReason
    if (unavailable != null) {
        println("skipping: $unavailable")
        return
    }
    PrivateBus.start().use { bus ->
        SecretServiceStub.on(bus).use { stub -> body(stub, bus.environment) }
    }
}
