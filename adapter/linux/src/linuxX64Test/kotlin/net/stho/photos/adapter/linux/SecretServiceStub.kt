@file:OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class)

package net.stho.photos.adapter.linux

import cnames.structs.DBusMessage
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import net.stho.photos.fixtures.readBytes
import net.stho.photos.ingest.Credentials
import photosdbus.DBusMessageIter
import photosdbus.dbus_bus_request_name
import photosdbus.dbus_connection_flush
import photosdbus.dbus_connection_pop_message
import photosdbus.dbus_connection_read_write
import photosdbus.dbus_connection_send
import photosdbus.dbus_message_get_member
import photosdbus.dbus_message_get_path
import photosdbus.dbus_message_get_type
import photosdbus.dbus_message_iter_get_arg_type
import photosdbus.dbus_message_iter_init
import photosdbus.dbus_message_iter_init_append
import photosdbus.dbus_message_iter_next
import photosdbus.dbus_message_iter_recurse
import photosdbus.dbus_message_new_error
import photosdbus.dbus_message_new_method_return
import photosdbus.dbus_message_unref
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

/**
 * A Secret Service for the tests to talk to, owning `org.freedesktop.secrets` on a
 * [PrivateBus].
 *
 * The client is the one part of the credential path that cannot be covered by injecting a fake:
 * everything interesting about it — marshalling, the session, telling a locked collection from a
 * missing item — happens on the wire. So the wire is what the tests drive.
 *
 * Dispatch runs on a worker of its own, because the client's calls block waiting for replies: a
 * single-threaded stub would deadlock against the code it exists to test.
 */
internal class SecretServiceStub private constructor(private val bus: Bus) : AutoCloseable {

    internal class Item(
        val attributes: Map<String, String>,
        val secret: ByteArray,
        val locked: Boolean,
    ) {
        val text: String get() = secret.decodeToString()
    }

    private val guard = PosixMutex()
    private val items = mutableMapOf<String, Item>()
    private var nextItem = 1
    private var running = true
    private var broken = false
    private val worker = Worker.start(name = "secret-service-stub")

    internal companion object {
        /** `DBUS_NAME_FLAG_DO_NOT_QUEUE`, and `DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER`. */
        private const val DO_NOT_QUEUE = 4u
        private const val PRIMARY_OWNER = 1

        /** `DBUS_MESSAGE_TYPE_METHOD_CALL`. Spelled out for the same reason the type codes are. */
        private const val METHOD_CALL = 1

        internal fun on(bus: PrivateBus): SecretServiceStub {
            val connection = Bus.open(bus.address)
            // Primary ownership exactly, not merely "no error": being queued behind another
            // owner returns success and then answers nothing, which presents as one test
            // reading the previous test's secrets.
            val owned = withDbusError { error ->
                dbus_bus_request_name(connection.raw, SECRETS_DESTINATION, DO_NOT_QUEUE, error)
            }
            if (owned != PRIMARY_OWNER) {
                connection.close()
                error("the stub did not get $SECRETS_DESTINATION (reply $owned)")
            }
            return SecretServiceStub(connection)
        }
    }

    init {
        worker.executeAfter(0L) { loop() }
    }

    override fun close() {
        guard.withLock { running = false }
        // The loop wakes at least every 50 ms, so waiting for the queued job to end is what
        // guarantees nothing touches the socket after it closes.
        worker.requestTermination(processScheduledJobs = true).result
        bus.close()
        guard.close()
    }

    // ------------------------------------------------------------ contents

    /** Puts one item in the keyring, as `photos-cli login` would have. */
    internal fun store(
        field: String,
        secret: String,
        locked: Boolean = false,
        service: String = Credentials.SERVICE,
    ): String = guard.withLock {
        val path = "/org/freedesktop/secrets/collection/stub/${nextItem++}"
        items[path] = Item(
            attributes = mapOf("service" to service, "field" to field),
            secret = secret.encodeToByteArray(),
            locked = locked,
        )
        path
    }

    internal val storedPaths: List<String> get() = guard.withLock { items.keys.sorted() }

    /** The item carrying this field, if any — how a test asserts what `login` wrote. */
    internal fun item(field: String): Item? =
        guard.withLock { items.values.firstOrNull { it.attributes["field"] == field } }

    /** Makes every method answer a well-formed reply of the wrong shape. */
    internal fun breakEverything() {
        guard.withLock { broken = true }
    }

    // ------------------------------------------------------------ dispatch

    private fun loop() {
        while (guard.withLock { running }) {
            dbus_connection_read_write(bus.raw, 50)
            while (true) {
                val message = dbus_connection_pop_message(bus.raw) ?: break
                try {
                    handle(message)
                } finally {
                    dbus_message_unref(message)
                }
            }
        }
    }

    private fun handle(message: CPointer<DBusMessage>) {
        if (dbus_message_get_type(message) != METHOD_CALL) return
        val member = dbus_message_get_member(message)?.toKString() ?: ""
        val path = dbus_message_get_path(message)?.toKString() ?: ""

        if (guard.withLock { broken }) {
            // A return with no arguments: well-formed, wrong shape.
            reply(message) {}
            return
        }

        when (member) {
            "OpenSession" -> reply(message) { iterator ->
                iterator.container(TYPE_VARIANT, "s") { it.appendString("") }
                iterator.appendObjectPath(SESSION)
            }

            "SearchItems" -> {
                val wanted = message.readStringDictionaryArgument()
                val matches = guard.withLock {
                    items.filterValues { item ->
                        wanted.all { (key, value) -> item.attributes[key] == value }
                    }
                }
                val unlocked = matches.filterValues { !it.locked }.keys.sorted()
                val locked = matches.filterValues { it.locked }.keys.sorted()
                reply(message) { iterator ->
                    iterator.appendObjectPaths(unlocked)
                    iterator.appendObjectPaths(locked)
                }
            }

            "GetSecret" -> {
                val item = guard.withLock { items[path] }
                if (item == null || item.locked) {
                    replyError(message, "org.freedesktop.Secret.Error.NoSuchObject", "no such item")
                    return
                }
                reply(message) { iterator ->
                    iterator.container(TYPE_STRUCT, null) { secret ->
                        secret.appendObjectPath(SESSION)
                        secret.appendBytes(ByteArray(0))
                        secret.appendBytes(item.secret)
                        secret.appendString("text/plain; charset=utf8")
                    }
                }
            }

            "CreateItem" -> {
                val (attributes, secret) = message.readCreateItem()
                val itemPath = guard.withLock {
                    // `replace: true` is what the client sends, and what makes storing twice an
                    // update rather than two items SearchItems returns in an undefined order.
                    val existing = items.entries.firstOrNull { it.value.attributes == attributes }
                    val itemPath = existing?.key
                        ?: "/org/freedesktop/secrets/collection/stub/${nextItem++}"
                    items[itemPath] = Item(attributes, secret, locked = false)
                    itemPath
                }
                reply(message) { iterator ->
                    iterator.appendObjectPath(itemPath)
                    iterator.appendObjectPath("/")
                }
            }

            "Delete" -> {
                guard.withLock { items.remove(path) }
                reply(message) { it.appendObjectPath("/") }
            }

            else -> replyError(message, "org.freedesktop.DBus.Error.UnknownMethod", member)
        }
    }

    // ------------------------------------------------------------ plumbing

    private fun reply(
        message: CPointer<DBusMessage>,
        arguments: (CPointer<DBusMessageIter>) -> Unit,
    ) {
        val response = dbus_message_new_method_return(message) ?: return
        try {
            memScoped {
                val iterator = alloc<DBusMessageIter>()
                dbus_message_iter_init_append(response, iterator.ptr)
                arguments(iterator.ptr)
            }
            dbus_connection_send(bus.raw, response, null)
            dbus_connection_flush(bus.raw)
        } finally {
            dbus_message_unref(response)
        }
    }

    private fun replyError(message: CPointer<DBusMessage>, name: String, detail: String) {
        val response = dbus_message_new_error(message, name, detail) ?: return
        try {
            dbus_connection_send(bus.raw, response, null)
            dbus_connection_flush(bus.raw)
        } finally {
            dbus_message_unref(response)
        }
    }
}

private const val SESSION = "/org/freedesktop/secrets/session/stub"

private fun CPointer<DBusMessageIter>.appendObjectPaths(paths: List<String>) {
    container(TYPE_ARRAY, "o") { array -> paths.forEach(array::appendObjectPath) }
}

/** The `a{ss}` first argument of a call, or nothing when the call carries none. */
private fun CPointer<DBusMessage>.readStringDictionaryArgument(): Map<String, String> = memScoped {
    val iterator = alloc<DBusMessageIter>()
    if (dbus_message_iter_init(this@readStringDictionaryArgument, iterator.ptr) == 0u) {
        return emptyMap()
    }
    iterator.ptr.readStringDictionary()
}

/** `CreateItem` is `(a{sv} properties, (oayays) secret, b replace)`. */
private fun CPointer<DBusMessage>.readCreateItem(): Pair<Map<String, String>, ByteArray> =
    memScoped {
        val iterator = alloc<DBusMessageIter>()
        if (dbus_message_iter_init(this@readCreateItem, iterator.ptr) == 0u) {
            return emptyMap<String, String>() to ByteArray(0)
        }

        var attributes = emptyMap<String, String>()
        val properties = alloc<DBusMessageIter>()
        dbus_message_iter_recurse(iterator.ptr, properties.ptr)
        while (dbus_message_iter_get_arg_type(properties.ptr) != TYPE_INVALID) {
            val entry = alloc<DBusMessageIter>()
            dbus_message_iter_recurse(properties.ptr, entry.ptr)
            val key = entry.ptr.readText()
            if (dbus_message_iter_next(entry.ptr) != 0u) {
                val variant = alloc<DBusMessageIter>()
                dbus_message_iter_recurse(entry.ptr, variant.ptr)
                if (key == "org.freedesktop.Secret.Item.Attributes") {
                    attributes = variant.ptr.readStringDictionary()
                }
            }
            dbus_message_iter_next(properties.ptr)
        }

        if (dbus_message_iter_next(iterator.ptr) == 0u) return attributes to ByteArray(0)
        val secret = alloc<DBusMessageIter>()
        dbus_message_iter_recurse(iterator.ptr, secret.ptr)
        if (dbus_message_iter_next(secret.ptr) == 0u || dbus_message_iter_next(secret.ptr) == 0u) {
            return attributes to ByteArray(0)
        }
        attributes to secret.ptr.readBytes()
    }

private fun CPointer<DBusMessageIter>.readStringDictionary(): Map<String, String> {
    if (dbus_message_iter_get_arg_type(this) != TYPE_ARRAY) return emptyMap()
    return memScoped {
        val entries = alloc<DBusMessageIter>()
        dbus_message_iter_recurse(this@readStringDictionary, entries.ptr)
        buildMap {
            while (dbus_message_iter_get_arg_type(entries.ptr) != TYPE_INVALID) {
                val pair = alloc<DBusMessageIter>()
                dbus_message_iter_recurse(entries.ptr, pair.ptr)
                val key = pair.ptr.readText()
                if (key != null && dbus_message_iter_next(pair.ptr) != 0u) {
                    pair.ptr.readText()?.let { put(key, it) }
                }
                dbus_message_iter_next(entries.ptr)
            }
        }
    }
}

/**
 * A mutex, because the stub's contents are written by the test thread and read by the worker.
 *
 * Kotlin/Native's stdlib has atomics but no lock, and the state here is a map rather than a
 * word. [close] returns the pthread's own allocation, which is the same discipline `PixelImage`
 * follows for the imaging structs.
 */
private class PosixMutex : AutoCloseable {
    private val raw = nativeHeap.alloc<pthread_mutex_t>().also { pthread_mutex_init(it.ptr, null) }

    fun <R> withLock(body: () -> R): R {
        pthread_mutex_lock(raw.ptr)
        try {
            return body()
        } finally {
            pthread_mutex_unlock(raw.ptr)
        }
    }

    override fun close() {
        pthread_mutex_destroy(raw.ptr)
        nativeHeap.free(raw)
    }
}
