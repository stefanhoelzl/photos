package net.stho.photos.adapter.linux

import java.util.concurrent.TimeUnit
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * Whether the desktop asks for a dark scheme, as the XDG settings portal says (§6, §11).
 *
 * Compose's `isSystemInDarkTheme()` answers only from what the JVM can see, which on Linux is
 * nothing: both Linux roots came up light on a desktop set to dark. The portal's
 * `org.freedesktop.appearance color-scheme` is the one setting GNOME, KDE and the rest all
 * publish, and its `SettingChanged` signal is how a switch made while the window is open reaches
 * it. Over dbus-java, as the Secret Service is.
 *
 * [dark] is null when the portal is not there or has no preference — the root falls back to
 * Compose's own answer then.
 */
public class Appearance private constructor(private val connection: DBusConnection?) : AutoCloseable {

    @Volatile
    public var dark: Boolean? = connection?.let(::read)
        private set

    private val listeners = mutableListOf<(Boolean?) -> Unit>()

    private val handler = DBusSigHandler<PortalSettings.SettingChanged> { signal ->
        if (signal.namespace == NAMESPACE && signal.key == KEY) {
            dark = scheme(signal.value.value)
            synchronized(listeners) { listeners.toList() }.forEach { it(dark) }
        }
    }

    init {
        runCatching { connection?.addSigHandler(PortalSettings.SettingChanged::class.java, handler) }
    }

    /** Called with the new answer whenever the desktop switches scheme. */
    public fun onChange(listener: (Boolean?) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    override fun close() {
        runCatching { connection?.removeSigHandler(PortalSettings.SettingChanged::class.java, handler) }
        runCatching { connection?.close() }
    }

    private fun read(connection: DBusConnection): Boolean? = runCatching {
        val settings = connection.getRemoteObject(PORTAL, PORTAL_PATH, PortalSettings::class.java)
        // `ReadOne` since version 2 of the interface; `Read`, which wraps the value in one more
        // variant, before it.
        val value = runCatching { settings.ReadOne(NAMESPACE, KEY).value }
            .getOrElse { (settings.Read(NAMESPACE, KEY).value as? Variant<*>)?.value }
        scheme(value)
    }.getOrNull()

    /** 1 is "prefer dark", 2 "prefer light", 0 no preference at all. */
    private fun scheme(value: Any?): Boolean? = when ((value as? UInt32)?.toInt() ?: (value as? Number)?.toInt()) {
        1 -> true
        2 -> false
        else -> null
    }

    @DBusInterfaceName("org.freedesktop.portal.Settings")
    internal interface PortalSettings : DBusInterface {
        fun ReadOne(namespace: String, key: String): Variant<*>

        fun Read(namespace: String, key: String): Variant<*>

        class SettingChanged(path: String, val namespace: String, val key: String, val value: Variant<*>) :
            DBusSignal(path, namespace, key, value)
    }

    public companion object {
        private const val PORTAL = "org.freedesktop.portal.Desktop"
        private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
        private const val NAMESPACE = "org.freedesktop.appearance"
        private const val KEY = "color-scheme"

        /** The session bus's answer, or an [Appearance] with none when there is no bus to ask. */
        public fun ofSession(): Appearance =
            Appearance(runCatching { DBusConnectionBuilder.forSessionBus().build() }.getOrNull())

        /**
         * How much larger than 96 dpi the desktop draws, for `sun.java2d.uiScale`.
         *
         * The JVM runs under XWayland here, and XWayland tells an X client nothing about the
         * monitor's scale: on a 2× GNOME desktop the window came up at half size. What the
         * desktop does publish to X clients is `Xft.dpi` — 192 at 2× — and `GDK_SCALE` where a
         * person set it. Null when neither says, and the JVM's own answer stands.
         */
        public fun displayScale(env: Map<String, String> = System.getenv()): Double? {
            env["GDK_SCALE"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
            val resources = runCatching {
                val process = ProcessBuilder("xrdb", "-query").redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText()
                if (process.waitFor(2, TimeUnit.SECONDS)) text else null
            }.getOrNull() ?: return null
            val dpi = Regex("""(?m)^Xft\.dpi:\s*([0-9.]+)""").find(resources)?.groupValues?.get(1)?.toDoubleOrNull()
            return dpi?.takeIf { it > 0 }?.let { it / 96.0 }
        }
    }
}
