package net.stho.photos.adapter.linux

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The five things the Secret Service protocol needs a D-Bus connection to do.
 *
 * A port, for the second of §7's three reasons: the protocol above it is one piece of code, and
 * the transport beneath it is two — libdbus through cinterop for the shipped CLI, dbus-java for
 * the desktop app, which runs on the JVM and cannot reach a cinterop binding at all.
 *
 * **The seam is here, at the operations, rather than one layer down at "send a method call".**
 * A generic D-Bus call would have to carry D-Bus's type system across the boundary — variants,
 * dict entries, object paths, the `(oayays)` secret struct — and both implementations would
 * then re-derive the same marshalling. Expressed as five operations the boundary carries object
 * paths as strings, attributes as a `Map`, and a secret as bytes, and each transport marshals in
 * whichever way its own library prefers.
 *
 * What is deliberately *not* here is any of §1's judgement: which reply means "absent", which
 * means "locked", which is a deferral and which is a real error. That is `DbusKeyring`'s, once,
 * so the two transports cannot disagree about the thing the exit codes turn on.
 */
internal interface SecretsBus : AutoCloseable {

    /** `OpenSession("plain", "")` — the session every secret is read and written through. */
    fun openSession(): String

    /** `SearchItems`, split the way §1's exit codes need it. */
    fun searchItems(attributes: Map<String, String>): FoundItems

    /** `GetSecret` on one item, returning the value exactly as stored. */
    fun itemSecret(item: String, session: String): ByteArray

    /** `CreateItem` on the default collection, replacing any item with the same attributes. */
    fun createItem(label: String, attributes: Map<String, String>, value: ByteArray, session: String)

    /** `Delete()` on one item. */
    fun deleteItem(item: String)
}

/** What a `SearchItems` matched, split the way §1's exit codes need it. */
internal data class FoundItems(val unlocked: List<String>, val locked: List<String>)

/**
 * Why a call did not produce what was asked for.
 *
 * The split is the whole point of speaking the protocol rather than shelling out: [Unavailable]
 * and [Locked] are deferrals (exit 75) and a search that matches nothing is a real error
 * (exit 3), whereas `secret-tool` only ever told us whether it had written to stderr.
 * [Malformed] is a third thing again — waiting an hour will not make a reply legal — so it
 * aborts like any other condition that stops a run before it writes.
 *
 * "No such item" is deliberately absent: a search that matches nothing is a value the caller
 * returns ([net.stho.photos.ports.KeyringRead.Absent]), not something to throw.
 */
internal sealed class SecretServiceFailure(override val message: String) : Exception(message) {

    /** No bus address, no connection, no such service, or no reply in time. */
    internal class Unavailable(val detail: String) : SecretServiceFailure(detail)

    /** The keyring answered and the item exists, but the collection is locked. */
    internal class Locked : SecretServiceFailure("the keyring is locked")

    /** The keyring answered with something the spec does not allow. */
    internal class Malformed(val detail: String) :
        SecretServiceFailure("unexpected reply from the keyring: $detail")
}

/** The well-known names both transports address. */
internal const val SECRETS_DESTINATION: String = "org.freedesktop.secrets"
internal const val SECRETS_PATH: String = "/org/freedesktop/secrets"
internal const val SERVICE_INTERFACE: String = "org.freedesktop.Secret.Service"
internal const val COLLECTION_INTERFACE: String = "org.freedesktop.Secret.Collection"
internal const val ITEM_INTERFACE: String = "org.freedesktop.Secret.Item"
internal const val DEFAULT_COLLECTION: String = "/org/freedesktop/secrets/aliases/default"

/**
 * `$DBUS_SESSION_BUS_ADDRESS`, else the well-known socket, else nothing.
 *
 * Both are checked because a systemd user unit inherits the variable while a plain login shell
 * may not, and `$XDG_RUNTIME_DIR/bus` is where every current session puts it. Nothing here
 * falls back to libdbus's `dbus_bus_get`: it would autolaunch `dbus-launch` off `PATH`, which is
 * the dependency this adapter exists to remove.
 */
internal fun sessionBusAddress(environment: (String) -> String?): String? {
    environment("DBUS_SESSION_BUS_ADDRESS").orNullIfBlank()?.let { return it }
    val runtime = environment("XDG_RUNTIME_DIR").orNullIfBlank() ?: return null
    val socket = "$runtime/bus"
    return if (SystemFileSystem.exists(Path(socket))) "unix:path=$socket" else null
}
