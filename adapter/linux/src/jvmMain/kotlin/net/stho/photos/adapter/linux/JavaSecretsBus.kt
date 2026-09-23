package net.stho.photos.adapter.linux

import net.stho.photos.ingest.Credentials
import net.stho.photos.ports.Keyring
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.BusAddress
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

/**
 * [SecretsBus] over dbus-java, for the desktop app.
 *
 * The JVM cannot reach the cinterop binding the CLI uses, so the same five operations are
 * spoken through a pure-Java client instead. The protocol above — which reply means *absent*,
 * which means *not now* — is `DbusKeyring`'s and is not repeated here; this file is marshalling
 * and nothing else, which is the whole reason the port was drawn where it was.
 *
 * A connection per call, closed by `use`, exactly as the native transport does it: a shared
 * connection outlives the lookup, and this process wants the socket gone once the password has
 * been read.
 */
internal class JavaSecretsBus(private val address: String) : SecretsBus {

    private val connection = try {
        DBusConnectionBuilder.forAddress(BusAddress.of(address)).build()
    } catch (failure: Exception) {
        throw SecretServiceFailure.Unavailable("cannot reach $address: ${failure.message}")
    }

    private val service: SecretService = remote(SECRETS_PATH)

    override fun openSession(): String = translating("OpenSession") {
        // "plain", and the same reasoning as the native side: the secret crosses a
        // peer-credential-authenticated AF_UNIX socket inside our own $XDG_RUNTIME_DIR, and a
        // wrong DH decrypt would surface as exactly the opaque 403 §1 forbids.
        service.OpenSession("plain", Variant("")).result.path
    }

    override fun searchItems(attributes: Map<String, String>): FoundItems =
        translating("SearchItems") {
            val found = service.SearchItems(attributes)
            FoundItems(
                unlocked = found.unlocked.map { it.path },
                locked = found.locked.map { it.path },
            )
        }

    override fun itemSecret(item: String, session: String): ByteArray =
        translating("GetSecret") {
            remote<SecretItem>(item).GetSecret(DBusPath(session)).value
        }

    override fun createItem(
        label: String,
        attributes: Map<String, String>,
        value: ByteArray,
        session: String,
    ): Unit = translating("CreateItem") {
        val properties = mapOf(
            "org.freedesktop.Secret.Item.Label" to Variant(label),
            "org.freedesktop.Secret.Item.Attributes" to Variant(attributes, "a{ss}"),
        )
        val secret = Secret(DBusPath(session), ByteArray(0), value, "text/plain")
        // `replace = true`: the attributes are the item's identity here, so storing twice must
        // update rather than leave two items SearchItems returns in an order nothing defines.
        val created = remote<SecretCollection>(DEFAULT_COLLECTION)
            .CreateItem(properties, secret, true)
        // A non-trivial prompt path means the collection is locked. Never prompted: that needs
        // a graphical prompter, which an unattended caller has not got.
        if (created.prompt.path != "/") throw SecretServiceFailure.Locked()
    }

    override fun deleteItem(item: String): Unit = translating("Delete") {
        if (remote<SecretItem>(item).Delete().path != "/") throw SecretServiceFailure.Locked()
    }

    override fun close() {
        connection.close()
    }

    private inline fun <reified T : DBusInterface> remote(path: String): T =
        connection.getRemoteObject(SECRETS_DESTINATION, path, T::class.java)

    /**
     * Anything the library throws becomes *unavailable*, except what we threw ourselves.
     *
     * dbus-java reports a missing service, a dropped socket and a timeout all as `DBusException`
     * subclasses, and none of them is distinguishable here from "the keyring is not running" —
     * which is §1's deferral rather than an error, and the conservative reading.
     */
    private inline fun <R> translating(call: String, body: () -> R): R =
        try {
            body()
        } catch (failure: SecretServiceFailure) {
            throw failure
        } catch (failure: DBusException) {
            throw SecretServiceFailure.Unavailable("$call failed: ${failure.message}")
        } catch (failure: RuntimeException) {
            throw SecretServiceFailure.Unavailable("$call failed: ${failure.message}")
        }
}

/**
 * The keyring the desktop app uses: this process's environment, dbus-java underneath.
 *
 * `:app:harness` is the composition root and this is what it names.
 */
public fun desktopKeyring(
    service: String = Credentials.SERVICE,
    environment: (String) -> String? = { System.getenv(it) },
): Keyring = DbusKeyring(service, environment, ::JavaSecretsBus)

// ---------------------------------------------------------------- the wire

@DBusInterfaceName("org.freedesktop.Secret.Service")
internal interface SecretService : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): OpenSessionResult
    fun SearchItems(attributes: Map<String, String>): SearchResult
}

@DBusInterfaceName("org.freedesktop.Secret.Collection")
internal interface SecretCollection : DBusInterface {
    fun CreateItem(properties: Map<String, Variant<*>>, secret: Secret, replace: Boolean): CreateResult
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
internal interface SecretItem : DBusInterface {
    fun GetSecret(session: DBusPath): Secret
    fun Delete(): DBusPath
}

/** `(oayays)`: session, parameters, value, content type. */
internal class Secret(
    @field:Position(0) val session: DBusPath,
    @field:Position(1) val parameters: ByteArray,
    @field:Position(2) val value: ByteArray,
    @field:Position(3) val contentType: String,
) : Struct()

/*
 * The three replies below are two OUT arguments each, not one struct: `vo`, `aoao` and `oo` on
 * the wire. A `Tuple` is how dbus-java spells several return values. They were `Struct`s, which
 * expect a single `(vo)` — the private-bus tests passed, because the stub replied with the same
 * classes, and gnome-keyring's real reply failed to decode as "number of parameters didn't match
 * receiving signature", which the desktop reported as a keyring it could not reach.
 */

/** `OpenSession`'s `vo`: the algorithm's output, and the session. */
internal class OpenSessionResult(
    @field:Position(0) val output: Variant<*>,
    @field:Position(1) val result: DBusPath,
) : Tuple()

/** `SearchItems`' `aoao`: unlocked items, then locked ones. */
internal class SearchResult(
    @field:Position(0) val unlocked: List<DBusPath>,
    @field:Position(1) val locked: List<DBusPath>,
) : Tuple()

/** `CreateItem`'s `oo`: the item, and a prompt that is `/` when none is needed. */
internal class CreateResult(
    @field:Position(0) val item: DBusPath,
    @field:Position(1) val prompt: DBusPath,
) : Tuple()
