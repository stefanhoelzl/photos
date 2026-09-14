package net.stho.photos.app

import net.stho.photos.StorageUrlFailure
import net.stho.photos.ingest.Credentials
import net.stho.photos.ports.Keyring
import net.stho.photos.ports.KeyringRead
import net.stho.photos.storage.StorageUrl
import net.stho.photos.storage.asStorageUrl

/**
 * Whether this device is set up, and the only thing that can change it (§1).
 *
 * §1 is strict about the shape: credentials are entered exactly once, there is no edit sheet and
 * no second path to a configured state — the app is either set up or it is not. So this offers
 * three verbs and no more: ask, [save], [logOut].
 *
 * It sits above `AppModel` rather than inside it, because the model cannot be built at all until
 * there is a zone to talk to: the S3 client, the sync loop and the queue all take the credential
 * at construction. That is also what makes logging out simple — the session is discarded whole
 * rather than asked to forget things one at a time.
 *
 * The items are the CLI's, under `service photos-cli` with a `field` attribute, so a laptop that
 * has run `photos-cli login` is already set up and the app has nothing to ask.
 */
public class Account(
    private val keyring: Keyring,
    /**
     * §1's development override, honoured here exactly as the CLI honours it: the environment
     * beats the store when it is set. `secrets-env ./gradlew :app:desktop:run` therefore needs
     * no setup at all, which is what keeps the harness's inner loop as short as it was.
     *
     * Empty on a phone, which has no environment to read.
     */
    private val environment: Map<String, String> = emptyMap(),
) {

    public fun current(): AccountState {
        injected(environment[Credentials.ENDPOINT_VARIABLE])?.let { endpoint ->
            injected(environment[Credentials.PASSWORD_VARIABLE])?.let { password ->
                return parse(endpoint, password, fromEnvironment = true)
            }
        }
        val endpoint = when (val read = keyring.read(ENDPOINT)) {
            is KeyringRead.Found -> read.secret
            KeyringRead.Absent -> return AccountState.Absent
            is KeyringRead.Unavailable -> return AccountState.Unavailable(read.reason)
        }
        val password = when (val read = keyring.read(PASSWORD)) {
            is KeyringRead.Found -> read.secret
            // Half an install is not an install. Saying *absent* sends the person back through
            // setup, which writes both again -- the only state this design allows.
            KeyringRead.Absent -> return AccountState.Absent
            is KeyringRead.Unavailable -> return AccountState.Unavailable(read.reason)
        }
        return parse(endpoint, password, fromEnvironment = false)
    }

    /**
     * Stores both, or neither.
     *
     * §1: the credentials are **not** validated with a test request, so a mistyped password is
     * stored happily and surfaces on the first sync. The compensating requirement is that sync
     * failures name the status and the cause, which `CatalogSyncer` does.
     *
     * The URL *is* checked, because that is a parse rather than a question for the network, and
     * an unparseable one could never reach a zone to be wrong about.
     */
    public fun save(storageUrl: String, password: String): SaveOutcome {
        val parsed = try {
            storageUrl.asStorageUrl()
        } catch (failure: StorageUrlFailure) {
            return SaveOutcome.Rejected(failure.message)
        }
        // Trimmed like the URL: a zone password never contains whitespace, and a pasted one that
        // carries a trailing space or newline would otherwise be stored happily and fail every
        // request as `SignatureDoesNotMatch`, which names nothing a person could see.
        val secret = password.trim()
        if (secret.isEmpty()) return SaveOutcome.Rejected("The password cannot be empty.")
        return try {
            keyring.write(ENDPOINT, storageUrl.trim())
            keyring.write(PASSWORD, secret)
            SaveOutcome.Saved(parsed, secret)
        } catch (failure: Exception) {
            SaveOutcome.Rejected(failure.message ?: "The credentials could not be stored.")
        }
    }

    /**
     * Removes both, because `service photos-cli` is one concept: removing half leaves an install
     * that is neither working nor clean. Nothing in the zone is touched and nothing cached is
     * deleted — logging out is about this device's credentials and nothing else.
     */
    public fun logOut() {
        keyring.remove(PASSWORD)
        keyring.remove(ENDPOINT)
    }

    private fun parse(endpoint: String, password: String, fromEnvironment: Boolean): AccountState =
        try {
            AccountState.Configured(endpoint.asStorageUrl(), password, fromEnvironment)
        } catch (failure: StorageUrlFailure) {
            // A stored value that will not parse is not "not set up": setup would overwrite it
            // silently and the person would never learn what was wrong with what they typed.
            AccountState.Unavailable(failure.message)
        }

    private fun injected(raw: String?): String? = raw?.trim()?.ifEmpty { null }

    private companion object {
        val PASSWORD = Credentials.Field.PASSWORD.attribute
        val ENDPOINT = Credentials.Field.ENDPOINT.attribute
    }
}

/** The three answers, and §1 turns on the difference between the last two. */
public sealed interface AccountState {

    public data class Configured(
        val storage: StorageUrl,
        val password: String,
        /** True when §1's development override answered, which the UI says out loud. */
        val fromEnvironment: Boolean,
    ) : AccountState

    /** The store answered and holds nothing: show setup. */
    public data object Absent : AccountState

    /**
     * The store could not be asked — a locked collection, no session bus, a locked phone.
     *
     * Deliberately not the same as [Absent]: showing setup here would invite someone to retype
     * a password they have already stored, and storing it again would not fix a locked keyring.
     */
    public data class Unavailable(val reason: String) : AccountState
}

public sealed interface SaveOutcome {
    public data class Saved(val storage: StorageUrl, val password: String) : SaveOutcome
    public data class Rejected(val why: String) : SaveOutcome
}
