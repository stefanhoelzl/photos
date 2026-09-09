package net.stho.photos.ingest

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.stho.photos.CredentialFailure
import net.stho.photos.ports.Keyring
import net.stho.photos.ports.KeyringRead
import net.stho.photos.storage.StorageUrl
import net.stho.photos.storage.asStorageUrl

/**
 * Where a run gets its endpoint, its library root and its password.
 *
 * **Production takes both credentials from the desktop keyring**, read in-process over D-Bus.
 * Two items under one service, told apart by a `field` attribute, both written by
 * `photos-cli login`:
 *
 *     photos-cli login
 *
 * The endpoint is not a secret — it is a URL — but it is *configuration the run cannot do
 * without*, and putting it beside the password means a working install is one concept rather than
 * a keyring entry plus an exported variable somebody has to remember.
 *
 * **Development overrides both from the environment**, because this repository already keeps them
 * in Proton Pass and `secrets-env` — which reads `.secrets.yaml` and execs with the entries
 * injected — is how every other command here reaches them:
 *
 *     secrets-env photos-cli sync --dry-run
 *
 * The environment therefore wins when it is set, which is exactly what an override means. A run
 * that takes that path says so on stderr, so a stale variable silently outranking the keyring is
 * visible rather than an hour of confusion.
 *
 * The [environment] is a plain map rather than a port: the domain needs a few values once, at
 * startup, not a live query. The CLI reads the process environment and passes it in.
 *
 * The attributes are the ones `secret-tool store service photos-cli field password` wrote, so
 * items stored before the client existed are found unchanged.
 */
public class Credentials(
    private val environment: Map<String, String>,
    private val keyring: Keyring,
) {

    /** The `field` attribute distinguishing the two items under `service photos-cli`. */
    public enum class Field(public val attribute: String) {
        PASSWORD("password"),
        ENDPOINT("endpoint"),
    }

    /** Which source answered. The caller says so when it was not the keyring. */
    public enum class Source {
        KEYRING,

        /** The environment, i.e. `secrets-env` or a shell — development only. */
        ENVIRONMENT,
    }

    public data class Resolved<out T>(public val value: T, public val source: Source)

    /** The environment if it has one, the keyring otherwise. */
    public fun password(): Resolved<String> {
        injected(environment[PASSWORD_VARIABLE])?.let { return Resolved(it, Source.ENVIRONMENT) }
        return Resolved(read(Field.PASSWORD), Source.KEYRING)
    }

    /**
     * The storage URL: host, signing region and zone in one value (§1).
     *
     * Resolved exactly like the password — a flag, then the environment, then the keyring — so
     * there is one rule to remember rather than one per credential. A value that came from
     * [override] reports [Source.KEYRING]: the source is only ever named so that an unexpected
     * *environment* override is visible, and a flag the operator typed is not unexpected.
     */
    public fun storage(override: String? = null): Resolved<StorageUrl> {
        val raw = injected(override ?: environment[ENDPOINT_VARIABLE])
        if (raw != null) {
            val source = if (override == null) Source.ENVIRONMENT else Source.KEYRING
            return Resolved(raw.asStorageUrl(), source)
        }
        return Resolved(read(Field.ENDPOINT).asStorageUrl(), Source.KEYRING)
    }

    /**
     * The library root: the flag if given, otherwise the working directory.
     *
     * Defaulting to the cwd pairs with the `.photosignore` marker guard: `photos-cli sync` typed
     * in the wrong directory finds no marker and refuses, rather than deciding the library is
     * empty. So the convenient default is also the safe one.
     */
    public fun libraryRoot(workingDirectory: Path, override: String? = null): Path {
        val raw = injected(override) ?: return workingDirectory
        val expanded = expandingTilde(raw)
        val path = Path(expanded)
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory != true) {
            throw CredentialFailure.MissingLibraryRoot()
        }
        return path
    }

    /**
     * One item, with the port's three outcomes mapped onto §7's two exit paths.
     *
     * *Could not look* is a deferral (75) and *looked and found nothing* is a real error (3);
     * conflating them is what would turn a laptop that has not been logged into yet into an
     * hourly failure notification.
     */
    private fun read(field: Field): String = when (val answer = keyring.read(field.attribute)) {
        is KeyringRead.Found -> injected(answer.secret)
            ?: throw CredentialFailure.KeyringProtocol("the stored ${field.attribute} is empty")

        KeyringRead.Absent -> throw CredentialFailure.NoSuchItem(SERVICE, field.attribute)
        is KeyringRead.Unavailable -> throw CredentialFailure.KeyringUnavailable(answer.reason)
    }

    private fun expandingTilde(raw: String): String {
        val home = environment["HOME"] ?: return raw
        return when {
            raw == "~" -> home
            raw.startsWith("~/") -> "$home/${raw.drop(2)}"
            else -> raw
        }
    }

    public companion object {
        public const val SERVICE: String = "photos-cli"
        public const val PASSWORD_VARIABLE: String = "PHOTOS_PASSWORD"
        public const val ENDPOINT_VARIABLE: String = "PHOTOS_ENDPOINT"

        /**
         * An empty variable is an unset one. `secrets-env` failing to resolve an entry leaves the
         * name defined and empty, and a blank secret would otherwise reach the signer and come
         * back as an opaque 403 — which is exactly the error §1 says must never be opaque.
         */
        private fun injected(raw: String?): String? = raw?.trim()?.ifEmpty { null }
    }
}
