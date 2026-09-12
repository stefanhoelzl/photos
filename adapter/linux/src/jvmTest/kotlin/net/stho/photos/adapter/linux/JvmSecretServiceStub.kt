package net.stho.photos.adapter.linux

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.deleteRecursively
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.BusAddress
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant

/**
 * A `dbus-daemon` of the test's own, with nothing on it but the stub.
 *
 * The JVM counterpart of `PrivateBus`, and it exists for the same reason: the stub owns the
 * well-known name `org.freedesktop.secrets`, which the developer's own keyring already owns on
 * the real session bus. A private bus is also what keeps these tests from ever reading or
 * writing real credentials, which is not a property worth leaving to care.
 *
 * `dbus-daemon` is an external binary, exactly as `java` is for S3Mock, and it is handled the
 * same way: absent, these tests skip and the rest still run.
 */
internal class PrivateBus private constructor(
    val address: String,
    private val daemon: Process,
    private val directory: Path,
) : AutoCloseable {

    /** What a keyring should see instead of the developer's real session bus. */
    val environment: (String) -> String? = { name ->
        if (name == "DBUS_SESSION_BUS_ADDRESS") address else null
    }

    override fun close() {
        daemon.destroyForcibly()
        daemon.waitFor()
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        directory.deleteRecursively()
    }

    companion object {
        /** Null when `dbus-daemon` is not installed, which is a skip rather than a failure. */
        fun openOrNull(): PrivateBus? {
            val directory = Files.createTempDirectory("photos-dbus-")
            val socket = directory.resolve("bus")
            val address = "unix:path=$socket"
            val config = directory.resolve("session.conf")
            // The address is chosen rather than read back, so there is no pipe to drain and no
            // first line to parse: readiness is "the socket exists and accepts a connection",
            // which is the thing the test actually needs to be true.
            Files.writeString(
                config,
                """
                <busconfig>
                  <type>session</type>
                  <listen>$address</listen>
                  <policy context="default">
                    <allow send_destination="*"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent(),
            )
            val daemon = try {
                ProcessBuilder("dbus-daemon", "--config-file=$config", "--nofork", "--nopidfile")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (missing: java.io.IOException) {
                @OptIn(kotlin.io.path.ExperimentalPathApi::class)
                directory.deleteRecursively()
                return null
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (Files.exists(socket) && runCatching {
                        DBusConnectionBuilder.forAddress(BusAddress.of(address)).build().close()
                    }.isSuccess
                ) {
                    return PrivateBus(address, daemon, directory)
                }
                Thread.sleep(50)
            }
            daemon.destroyForcibly()
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            directory.deleteRecursively()
            return null
        }
    }
}

/**
 * Enough of `org.freedesktop.Secret.Service` to answer the five operations.
 *
 * It is written against dbus-java, which means this test has dbus-java on both ends and so
 * cannot prove the two agree with *gnome-keyring*. What it does prove is the part that is ours:
 * the interface declarations, the `@Position` order inside the `(oayays)` secret struct, and
 * that a search matching nothing comes back as an empty array rather than an error — which is
 * the distinction §1's exit codes are built on.
 */
internal class SecretServiceStub private constructor(
    private val connection: DBusConnection,
) : AutoCloseable {

    private val items = linkedMapOf<String, Item>()
    private val paths = AtomicInteger()
    private var locked = false

    internal data class Item(val attributes: Map<String, String>, val secret: ByteArray)

    /** Put an item there without going through the client, so a read has something to find. */
    fun store(field: String, secret: String, service: String = "photos-cli") {
        val attributes = mapOf("service" to service, "field" to field)
        val path = items.entries.firstOrNull { it.value.attributes == attributes }?.key
            ?: newItemPath()
        items[path] = Item(attributes, secret.toByteArray())
        connection.exportObject(path, ItemObject(path))
    }

    fun item(field: String, service: String = "photos-cli"): Item? =
        items.values.firstOrNull { it.attributes == mapOf("service" to service, "field" to field) }

    /** Everything answers "locked" from here on, which is §1's deferral rather than an error. */
    fun lock() {
        locked = true
    }

    override fun close() {
        connection.close()
    }

    private fun newItemPath(): String = "/org/freedesktop/secrets/item/i${paths.incrementAndGet()}"

    private inner class ServiceObject : SecretService {
        override fun getObjectPath() = SECRETS_PATH
        override fun isRemote() = false

        override fun OpenSession(algorithm: String, input: Variant<*>): OpenSessionResult =
            OpenSessionResult(Variant(""), DBusPath("/org/freedesktop/secrets/session/s1"))

        override fun SearchItems(attributes: Map<String, String>): SearchResult {
            val matched = items.entries.filter { it.value.attributes == attributes }.map { it.key }
            return if (locked) {
                SearchResult(emptyList(), matched.map(::DBusPath))
            } else {
                SearchResult(matched.map(::DBusPath), emptyList())
            }
        }
    }

    private inner class CollectionObject : SecretCollection {
        override fun getObjectPath() = DEFAULT_COLLECTION
        override fun isRemote() = false

        override fun CreateItem(
            properties: Map<String, Variant<*>>,
            secret: Secret,
            replace: Boolean,
        ): CreateResult {
            if (locked) return CreateResult(DBusPath("/"), DBusPath("/prompt/p1"))
            @Suppress("UNCHECKED_CAST")
            val attributes = properties["org.freedesktop.Secret.Item.Attributes"]
                ?.value as? Map<String, String> ?: emptyMap()
            // Replace rather than add: the attributes are the item's identity, so storing twice
            // must update rather than leave two items in an order nothing defines.
            val path = items.entries.firstOrNull { it.value.attributes == attributes }?.key
                ?: newItemPath()
            items[path] = Item(attributes, secret.value)
            connection.exportObject(path, ItemObject(path))
            return CreateResult(DBusPath(path), DBusPath("/"))
        }
    }

    private inner class ItemObject(private val path: String) : SecretItem {
        override fun getObjectPath() = path
        override fun isRemote() = false

        override fun GetSecret(session: DBusPath): Secret {
            val item = items[path] ?: error("no such item $path")
            return Secret(session, ByteArray(0), item.secret, "text/plain")
        }

        override fun Delete(): DBusPath {
            if (locked) return DBusPath("/prompt/p1")
            items.remove(path)
            connection.unExportObject(path)
            return DBusPath("/")
        }
    }

    companion object {
        fun on(bus: PrivateBus): SecretServiceStub {
            val connection = DBusConnectionBuilder.forAddress(BusAddress.of(bus.address)).build()
            val stub = SecretServiceStub(connection)
            connection.exportObject(SECRETS_PATH, stub.ServiceObject())
            connection.exportObject(DEFAULT_COLLECTION, stub.CollectionObject())
            connection.requestBusName(SECRETS_DESTINATION)
            return stub
        }
    }
}
