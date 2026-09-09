package net.stho.photos.adapter.linux

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.CredentialFailure
import net.stho.photos.ingest.Credentials
import net.stho.photos.ports.Keyring
import net.stho.photos.ports.KeyringRead

/**
 * §7's `Keyring`, over the desktop Secret Service (§1).
 *
 * Two items live under one service, told apart by a `field` attribute, and the attributes are
 * the ones `secret-tool store service photos-cli field password` wrote — so anything stored
 * before this client existed is still found. That compatibility is the reason the attribute
 * names are spelled here and nowhere else.
 *
 * The three outcomes of [read] are the point of the whole file. §1 turns the CLI's exit codes
 * on them: a value, *absent* — the keyring answered and holds no such item, a real error and
 * exit 3 — and *unavailable* — no bus, a failed connect, `NoReply`, `ServiceUnknown` or a
 * locked collection, which is a deferral and exit 75 because gnome-keyring unlocks through PAM
 * at login and an hourly timer has no prompter. Collapsing the last two turns "you have not
 * logged in yet" into a page at 3 a.m., or hides a broken install forever.
 *
 * [write] and [remove] have no such three-way answer to give — the port returns `Unit` — so
 * everything that stops them is thrown, and the distinction survives in the type: a keyring
 * that could not be reached is a [CredentialFailure.KeyringUnavailable], a reply the spec does
 * not allow is a [CredentialFailure.KeyringProtocol].
 */
public class DbusKeyring(
    /** The service attribute both items carry; also what `secret-tool` was given. */
    private val service: String = Credentials.SERVICE,
    /** Injected so a test can be pointed at a private bus rather than the developer's own. */
    private val environment: (String) -> String? = ::systemEnvironment,
) : Keyring {

    override fun read(field: String): KeyringRead =
        try {
            connect().use { bus ->
                val found = bus.searchItems(attributes(field))
                val item = found.unlocked.firstOrNull()
                when {
                    item != null -> KeyringRead.Found(bus.secretOf(item))
                    // Found but locked. Never unlocked: `Unlock` needs a graphical prompter,
                    // which is exactly what an unattended timer does not have.
                    found.locked.isNotEmpty() -> throw SecretServiceFailure.Locked()
                    else -> KeyringRead.Absent
                }
            }
        } catch (failure: SecretServiceFailure) {
            when (failure) {
                is SecretServiceFailure.Unavailable -> KeyringRead.Unavailable(failure.detail)
                is SecretServiceFailure.Locked ->
                    KeyringRead.Unavailable("the keyring is locked; log in and it will unlock")
                // Not a deferral — an hour will not make a reply legal — so it is thrown rather
                // than returned, which is what stops it becoming exit 75.
                is SecretServiceFailure.Malformed ->
                    throw CredentialFailure.KeyringProtocol(failure.message)
            }
        }

    /**
     * Writes one item, replacing whatever carried the same attributes. Used by `photos-cli
     * login`.
     */
    override fun write(field: String, secret: String): Unit = translatingFailures {
        connect().use { bus ->
            val session = bus.openSession()
            bus.createItem(
                label = "$service $field",
                attributes = attributes(field),
                value = secret.encodeToByteArray(),
                session = session,
            )
        }
    }

    /**
     * Removes the item, if it is there. Used by `photos-cli logout`.
     *
     * Removing nothing is not an error: the gesture means *make sure it is gone*, and
     * afterwards it is. A locked collection is the exception — the item exists and cannot be
     * touched, and pretending otherwise would report a logout that did not happen.
     */
    override fun remove(field: String): Unit = translatingFailures {
        connect().use { bus ->
            val found = bus.searchItems(attributes(field))
            if (found.unlocked.isEmpty() && found.locked.isNotEmpty()) {
                throw SecretServiceFailure.Locked()
            }
            found.unlocked.forEach(bus::deleteItem)
        }
    }

    private fun connect(): Bus = Bus.open(
        sessionBusAddress(environment) ?: throw SecretServiceFailure.Unavailable(
            "no session bus: DBUS_SESSION_BUS_ADDRESS is unset and " +
                "\$XDG_RUNTIME_DIR/bus does not exist",
        ),
    )

    /** The attributes `secret-tool` wrote. Renaming either of these orphans every stored item. */
    private fun attributes(field: String): Map<String, String> =
        mapOf("service" to service, "field" to field)

    /**
     * The item's value, exactly as it is stored.
     *
     * Nothing is trimmed or rejected here: whether a blank secret is usable is a rule about
     * credentials, and `Credentials` in the domain already owns it. An adapter that decided the
     * same thing again is how the two answers drift apart.
     */
    private fun Bus.secretOf(item: String): String =
        itemSecret(item, openSession()).decodeToString()

    /** The one place the protocol's outcomes become §7's exit codes, for the write path. */
    private inline fun translatingFailures(body: () -> Unit) {
        try {
            body()
        } catch (failure: SecretServiceFailure) {
            throw when (failure) {
                is SecretServiceFailure.Unavailable ->
                    CredentialFailure.KeyringUnavailable(failure.detail)
                is SecretServiceFailure.Locked ->
                    CredentialFailure.KeyringUnavailable(
                        "the keyring is locked; log in and it will unlock",
                    )
                is SecretServiceFailure.Malformed ->
                    CredentialFailure.KeyringProtocol(failure.message)
            }
        }
    }
}

/**
 * `$DBUS_SESSION_BUS_ADDRESS`, else the well-known socket, else nothing.
 *
 * Both are checked because a systemd user unit inherits the variable while a plain login shell
 * may not, and `$XDG_RUNTIME_DIR/bus` is where every current session puts it. Nothing here
 * falls back to `dbus_bus_get`: libdbus would autolaunch `dbus-launch` off `PATH`, which is the
 * dependency this adapter exists to remove.
 */
internal fun sessionBusAddress(environment: (String) -> String?): String? {
    environment("DBUS_SESSION_BUS_ADDRESS").orNullIfBlank()?.let { return it }
    val runtime = environment("XDG_RUNTIME_DIR").orNullIfBlank() ?: return null
    val socket = "$runtime/bus"
    return if (SystemFileSystem.exists(Path(socket))) "unix:path=$socket" else null
}
