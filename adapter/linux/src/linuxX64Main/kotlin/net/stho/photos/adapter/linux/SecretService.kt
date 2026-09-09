@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import cnames.structs.DBusConnection
import cnames.structs.DBusMessage
import photosdbus.DBusError
import photosdbus.DBusMessageIter
import photosdbus.dbus_bus_register
import photosdbus.dbus_connection_close
import photosdbus.dbus_connection_open_private
import photosdbus.dbus_connection_send_with_reply_and_block
import photosdbus.dbus_connection_set_exit_on_disconnect
import photosdbus.dbus_connection_unref
import photosdbus.dbus_error_free
import photosdbus.dbus_error_init
import photosdbus.dbus_error_is_set
import photosdbus.dbus_message_iter_append_basic
import photosdbus.dbus_message_iter_append_fixed_array
import photosdbus.dbus_message_iter_close_container
import photosdbus.dbus_message_iter_get_arg_type
import photosdbus.dbus_message_iter_get_basic
import photosdbus.dbus_message_iter_get_fixed_array
import photosdbus.dbus_message_iter_init
import photosdbus.dbus_message_iter_init_append
import photosdbus.dbus_message_iter_next
import photosdbus.dbus_message_iter_open_container
import photosdbus.dbus_message_iter_recurse
import photosdbus.dbus_message_new_method_call
import photosdbus.dbus_message_unref

/**
 * The freedesktop Secret Service, spoken directly over D-Bus.
 *
 * This is what §7 means by a binary that is self-sufficient: no `secret-tool` subprocess, and
 * no libsecret either — libdbus-1 is linked statically and, unlike libsecret, drags no glib in.
 *
 * **The connection is opened on an explicit address, never `dbus_bus_get`.** libdbus falls back
 * to `autolaunch:` when it has no address, which forks `dbus-launch` — an executable on `PATH`,
 * i.e. exactly the dependency this file exists to remove. A missing address is treated as "no
 * bus", which is a deferral rather than a failure.
 *
 * **Sessions are opened `plain`.** The secret crosses a peer-credential-authenticated
 * `AF_UNIX` socket inside the caller's own `$XDG_RUNTIME_DIR`; the encrypted algorithm would add
 * a DH exchange and an AES-CBC decrypt to the credential path, and a wrong decrypt surfaces as
 * the opaque 403 §1 forbids. The threat `plain` does not stop — a process running as this uid —
 * can simply ask the keyring itself.
 *
 * **Locked items are never unlocked.** `Unlock` needs a graphical prompter, which an hourly
 * timer does not have; §1's accepted cost is that a run before the first login defers.
 */

// ---------------------------------------------------------------- names

internal const val SECRETS_DESTINATION: String = "org.freedesktop.secrets"
internal const val SECRETS_PATH: String = "/org/freedesktop/secrets"
internal const val SERVICE_INTERFACE: String = "org.freedesktop.Secret.Service"
internal const val COLLECTION_INTERFACE: String = "org.freedesktop.Secret.Collection"
internal const val ITEM_INTERFACE: String = "org.freedesktop.Secret.Item"
internal const val DEFAULT_COLLECTION: String = "/org/freedesktop/secrets/aliases/default"

/**
 * No reply within this and the run defers rather than hanging. An hourly unit that blocks
 * forever on a wedged keyring holds the run lock and stops every later firing too, which is a
 * worse failure than not syncing this hour.
 */
internal const val CALL_TIMEOUT_MILLIS: Int = 10_000

// ---------------------------------------------------------------- type codes
//
// Spelled out rather than imported: libdbus writes these as `((int) 's')` cast expressions,
// which the C importer does not fold into constants.

internal const val TYPE_INVALID: Int = 0
internal val TYPE_BYTE: Int = 'y'.code
internal val TYPE_BOOLEAN: Int = 'b'.code
internal val TYPE_STRING: Int = 's'.code
internal val TYPE_OBJECT_PATH: Int = 'o'.code
internal val TYPE_ARRAY: Int = 'a'.code
internal val TYPE_VARIANT: Int = 'v'.code
internal val TYPE_STRUCT: Int = 'r'.code
internal val TYPE_DICT_ENTRY: Int = 'e'.code

// ---------------------------------------------------------------- failures

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

// ---------------------------------------------------------------- connection

/**
 * One connection, closed when the caller is done with it.
 *
 * Private rather than shared: a shared connection is reference-counted process-wide and
 * outlives the lookup, and this process wants the socket gone once the password is read. It is
 * [AutoCloseable] because Kotlin/Native has no `deinit` — the socket closes when the `use`
 * block ends, not when a collector eventually notices.
 */
internal class Bus private constructor(internal val raw: CPointer<DBusConnection>) : AutoCloseable {

    internal companion object {
        internal fun open(address: String): Bus = withDbusError { error ->
            val connection = dbus_connection_open_private(address, error)
                ?: throw SecretServiceFailure.Unavailable(error.detail("cannot reach $address"))
            // A keyring that restarts must not take the sync with it. libdbus's default is to
            // _exit() the process when the bus drops, which for an hourly unit would look like
            // a crash rather than the deferral it is.
            dbus_connection_set_exit_on_disconnect(connection, 0u)

            // Required before any traffic: the bus daemon assigns this connection its unique
            // name. A peer-to-peer connection (the test stub) answers Hello itself.
            if (dbus_bus_register(connection, error) == 0u) {
                dbus_connection_close(connection)
                dbus_connection_unref(connection)
                throw SecretServiceFailure.Unavailable(error.detail("cannot register on $address"))
            }
            Bus(connection)
        }
    }

    override fun close() {
        dbus_connection_close(raw)
        dbus_connection_unref(raw)
    }

    /**
     * One blocking method call, with the reply unref'd once [read] has taken what it needs.
     *
     * The reply's lifetime is the lambda's rather than the caller's: every reply here is read
     * once and immediately, and an owned pointer handed back would be one more thing for a
     * `finally` to get wrong.
     */
    internal fun <R> call(
        path: String,
        interfaceName: String,
        method: String,
        append: (CPointer<DBusMessageIter>) -> Unit = {},
        read: (CPointer<DBusMessage>) -> R,
    ): R {
        val message = dbus_message_new_method_call(SECRETS_DESTINATION, path, interfaceName, method)
            ?: throw SecretServiceFailure.Unavailable("out of memory building $method")
        try {
            memScoped {
                val iterator = alloc<DBusMessageIter>()
                dbus_message_iter_init_append(message, iterator.ptr)
                append(iterator.ptr)
            }
            val reply = withDbusError { error ->
                dbus_connection_send_with_reply_and_block(raw, message, CALL_TIMEOUT_MILLIS, error)
                    // ServiceUnknown means nothing owns org.freedesktop.secrets: no keyring is
                    // running, which is the same "not now" as no bus at all.
                    ?: throw SecretServiceFailure.Unavailable(error.detail("$method failed"))
            }
            try {
                return read(reply)
            } finally {
                dbus_message_unref(reply)
            }
        } finally {
            dbus_message_unref(message)
        }
    }
}

/** Runs [body] with an initialised `DBusError`, freed however it leaves. */
internal inline fun <R> withDbusError(body: (CPointer<DBusError>) -> R): R = memScoped {
    val error = alloc<DBusError>()
    dbus_error_init(error.ptr)
    try {
        body(error.ptr)
    } finally {
        dbus_error_free(error.ptr)
    }
}

/** What libdbus put in the error, or [fallback] when it set none. */
internal fun CPointer<DBusError>.detail(fallback: String): String {
    if (dbus_error_is_set(this) == 0u) return fallback
    return pointed.message?.toKString() ?: fallback
}

// ---------------------------------------------------------------- the five calls

/** What a `SearchItems` matched, split the way §1's exit codes need it. */
internal data class FoundItems(val unlocked: List<String>, val locked: List<String>)

/** `OpenSession("plain", "")` — the session every secret is read and written through. */
internal fun Bus.openSession(): String = call(
    path = SECRETS_PATH,
    interfaceName = SERVICE_INTERFACE,
    method = "OpenSession",
    append = { iterator ->
        iterator.appendString("plain")
        // The input is a variant even for `plain`, where it carries nothing.
        iterator.container(TYPE_VARIANT, "s") { it.appendString("") }
    },
) { reply ->
    reply.readArguments("OpenSession") { iterator ->
        // Skip the algorithm-negotiation output variant; `plain` carries nothing in it.
        val session = if (dbus_message_iter_next(iterator) != 0u &&
            dbus_message_iter_get_arg_type(iterator) == TYPE_OBJECT_PATH
        ) {
            iterator.readText()
        } else {
            null
        }
        session ?: throw SecretServiceFailure.Malformed("OpenSession returned no session path")
    }
}

/** `SearchItems({attributes})` on the service, which searches every collection. */
internal fun Bus.searchItems(attributes: Map<String, String>): FoundItems = call(
    path = SECRETS_PATH,
    interfaceName = SERVICE_INTERFACE,
    method = "SearchItems",
    append = { it.appendStringDictionary(attributes) },
) { reply ->
    reply.readArguments("SearchItems") { iterator ->
        val unlocked = iterator.readObjectPaths()
        if (dbus_message_iter_next(iterator) == 0u) {
            throw SecretServiceFailure.Malformed("SearchItems returned no locked array")
        }
        FoundItems(unlocked, iterator.readObjectPaths())
    }
}

/** `GetSecret(session)` on one item, returning the raw value. */
internal fun Bus.itemSecret(item: String, session: String): ByteArray = call(
    path = item,
    interfaceName = ITEM_INTERFACE,
    method = "GetSecret",
    append = { it.appendObjectPath(session) },
) { reply ->
    reply.readArguments("GetSecret") { iterator ->
        if (dbus_message_iter_get_arg_type(iterator) != TYPE_STRUCT) {
            throw SecretServiceFailure.Malformed("GetSecret did not return a Secret struct")
        }
        // Secret is (o session, ay parameters, ay value, s content_type); only value matters.
        memScoped {
            val fields = alloc<DBusMessageIter>()
            dbus_message_iter_recurse(iterator, fields.ptr)
            if (dbus_message_iter_next(fields.ptr) == 0u ||
                dbus_message_iter_next(fields.ptr) == 0u
            ) {
                throw SecretServiceFailure.Malformed("Secret struct is too short")
            }
            fields.ptr.readBytes()
        }
    }
}

/**
 * `CreateItem` on the default collection, replacing any item with the same attributes.
 *
 * Replace rather than add: the attributes are the identity of the item here, so storing twice
 * must update rather than leave two items that `SearchItems` returns in an order nothing
 * defines — which would make the password a coin flip.
 */
internal fun Bus.createItem(
    label: String,
    attributes: Map<String, String>,
    value: ByteArray,
    session: String,
    collection: String = DEFAULT_COLLECTION,
) {
    call(
        path = collection,
        interfaceName = COLLECTION_INTERFACE,
        method = "CreateItem",
        append = { iterator ->
            iterator.container(TYPE_ARRAY, "{sv}") { properties ->
                properties.appendVariantProperty("org.freedesktop.Secret.Item.Label", "s") {
                    it.appendString(label)
                }
                properties.appendVariantProperty(
                    "org.freedesktop.Secret.Item.Attributes",
                    "a{ss}",
                ) { it.appendStringDictionary(attributes) }
            }

            // Secret is (o session, ay parameters, ay value, s content_type). `plain` leaves
            // parameters empty.
            iterator.container(TYPE_STRUCT, null) { secret ->
                secret.appendObjectPath(session)
                secret.appendBytes(ByteArray(0))
                secret.appendBytes(value)
                secret.appendString("text/plain; charset=utf8")
            }

            iterator.appendBoolean(true)
        },
    ) { reply ->
        // (o item, o prompt). A prompt means the collection is locked, and §1 never prompts.
        reply.readArguments("CreateItem") { iterator ->
            val prompt = if (dbus_message_iter_next(iterator) != 0u) iterator.readText() else null
            if (prompt == null) {
                throw SecretServiceFailure.Malformed("CreateItem returned no prompt path")
            }
            if (prompt != "/") throw SecretServiceFailure.Locked()
        }
    }
}

/** `Delete()` on one item. */
internal fun Bus.deleteItem(item: String) {
    call(path = item, interfaceName = ITEM_INTERFACE, method = "Delete") { reply ->
        reply.readArguments("Delete") { iterator ->
            val prompt = iterator.readText()
                ?: throw SecretServiceFailure.Malformed("Delete returned no prompt path")
            if (prompt != "/") throw SecretServiceFailure.Locked()
        }
    }
}

// ---------------------------------------------------------------- marshalling
//
// libdbus's variadic entry points are unreachable from Kotlin, exactly as they were from
// Swift. The `DBusMessageIter` API they wrap is not variadic, and is what everything above
// uses; these are the pieces of it worth naming.

/** The reply's arguments, or [SecretServiceFailure.Malformed] when it carries none. */
internal inline fun <R> CPointer<DBusMessage>.readArguments(
    method: String,
    body: (CPointer<DBusMessageIter>) -> R,
): R = memScoped {
    val iterator = alloc<DBusMessageIter>()
    if (dbus_message_iter_init(this@readArguments, iterator.ptr) == 0u) {
        throw SecretServiceFailure.Malformed("$method returned nothing")
    }
    body(iterator.ptr)
}

/** Opens a container, fills it, and closes it — the pairing libdbus leaves to the caller. */
internal inline fun CPointer<DBusMessageIter>.container(
    type: Int,
    signature: String?,
    body: (CPointer<DBusMessageIter>) -> Unit,
) {
    memScoped {
        val sub = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(this@container, type, signature, sub.ptr)
        body(sub.ptr)
        dbus_message_iter_close_container(this@container, sub.ptr)
    }
}

/** `s` and `o` are both a `const char *` on the wire; only the type code differs. */
private fun CPointer<DBusMessageIter>.appendText(type: Int, value: String) {
    memScoped {
        val holder = alloc<CPointerVar<ByteVar>>()
        holder.value = value.cstr.ptr
        dbus_message_iter_append_basic(this@appendText, type, holder.ptr)
    }
}

internal fun CPointer<DBusMessageIter>.appendString(value: String): Unit =
    appendText(TYPE_STRING, value)

internal fun CPointer<DBusMessageIter>.appendObjectPath(value: String): Unit =
    appendText(TYPE_OBJECT_PATH, value)

internal fun CPointer<DBusMessageIter>.appendBoolean(value: Boolean) {
    memScoped {
        val holder = alloc<IntVar>()
        holder.value = if (value) 1 else 0
        dbus_message_iter_append_basic(this@appendBoolean, TYPE_BOOLEAN, holder.ptr)
    }
}

internal fun CPointer<DBusMessageIter>.appendBytes(value: ByteArray) {
    container(TYPE_ARRAY, "y") { array ->
        if (value.isEmpty()) return@container
        value.usePinned { pinned ->
            memScoped {
                val base = alloc<CPointerVar<ByteVar>>()
                base.value = pinned.addressOf(0)
                dbus_message_iter_append_fixed_array(array, TYPE_BYTE, base.ptr, value.size)
            }
        }
    }
}

internal fun CPointer<DBusMessageIter>.appendStringDictionary(pairs: Map<String, String>) {
    container(TYPE_ARRAY, "{ss}") { array ->
        for ((key, value) in pairs) {
            array.container(TYPE_DICT_ENTRY, null) { entry ->
                entry.appendString(key)
                entry.appendString(value)
            }
        }
    }
}

/** One `{sv}` entry: the name, then whatever [body] writes into the variant. */
private fun CPointer<DBusMessageIter>.appendVariantProperty(
    name: String,
    signature: String,
    body: (CPointer<DBusMessageIter>) -> Unit,
) {
    container(TYPE_DICT_ENTRY, null) { entry ->
        entry.appendString(name)
        entry.container(TYPE_VARIANT, signature, body)
    }
}

/** The string or object path under the iterator, or null when libdbus handed back nothing. */
internal fun CPointer<DBusMessageIter>.readText(): String? = memScoped {
    val holder = alloc<CPointerVar<ByteVar>>()
    dbus_message_iter_get_basic(this@readText, holder.ptr)
    holder.value?.toKString()
}

internal fun CPointer<DBusMessageIter>.readObjectPaths(): List<String> {
    if (dbus_message_iter_get_arg_type(this) != TYPE_ARRAY) return emptyList()
    return memScoped {
        val element = alloc<DBusMessageIter>()
        dbus_message_iter_recurse(this@readObjectPaths, element.ptr)
        buildList {
            while (dbus_message_iter_get_arg_type(element.ptr) != TYPE_INVALID) {
                element.ptr.readText()?.let(::add)
                dbus_message_iter_next(element.ptr)
            }
        }
    }
}

internal fun CPointer<DBusMessageIter>.readBytes(): ByteArray {
    if (dbus_message_iter_get_arg_type(this) != TYPE_ARRAY) return ByteArray(0)
    return memScoped {
        val element = alloc<DBusMessageIter>()
        dbus_message_iter_recurse(this@readBytes, element.ptr)
        if (dbus_message_iter_get_arg_type(element.ptr) != TYPE_BYTE) {
            return@memScoped ByteArray(0)
        }
        val base = alloc<CPointerVar<ByteVar>>()
        val count = alloc<IntVar>()
        dbus_message_iter_get_fixed_array(element.ptr, base.ptr, count.ptr)
        val start = base.value
        if (start == null || count.value <= 0) ByteArray(0) else start.readBytes(count.value)
    }
}
