package net.stho.photos.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.stho.photos.storage.StorageUrl

/**
 * A running app, for as long as one credential is good for.
 *
 * Everything `AppModel` needs is built from the zone URL and the password — the S3 client, the
 * sync loop, the queue, the caches — so a session is the unit that a log-out discards and a
 * set-up creates. The composition root supplies the factory, because building any of that means
 * naming a SQL driver and an HTTP engine, which is the one thing `:app:domain` must not do.
 */
public interface Session : AutoCloseable {
    public val model: AppModel
    public val thumbnails: Thumbnails

    /** §8's upload, or null on a root that has no gallery to upload from. */
    public val uploads: UploadModel? get() = null

    /**
     * Begins syncing.
     *
     * On the interface rather than left to the caller as `model.start()`, because what starting
     * means is the session's business: today it is the model, and the background sweep the roots
     * launch alongside it wants the same switch.
     */
    public fun start()
}

/**
 * What the UI is looking at: the setup screen, or a running app.
 *
 * §1 allows exactly two states and one transition in each direction, which is why this is a
 * sealed hierarchy of three and not a pile of booleans. [Blocked] is the third because a store
 * that cannot be asked is neither — sending someone to setup would invite them to retype a
 * password they have already stored.
 */
public sealed interface Launch {
    public data object Setup : Launch
    public data class Running(val session: Session, val storage: StorageUrl) : Launch
    public data class Blocked(val reason: String) : Launch
}

/**
 * Reads the account at startup, opens a session when there is one, and owns the two transitions.
 *
 * It is here rather than in a root because both roots do exactly this and neither has a reason
 * to do it differently — and because a test can drive it with a fake keyring and a fake session
 * factory, which is the only way "log out returns to setup, and the old session is closed"
 * becomes a thing that is checked rather than a thing that is hoped.
 */
public class Launcher(
    private val account: Account,
    private val open: (StorageUrl, String) -> Session,
) {
    private val _state = MutableStateFlow<Launch>(Launch.Setup)
    public val state: StateFlow<Launch> = _state.asStateFlow()

    /** Resolves the account and opens a session if it is already set up. */
    public fun start() {
        _state.value = when (val current = account.current()) {
            is AccountState.Configured -> running(current.storage, current.password)
            AccountState.Absent -> Launch.Setup
            is AccountState.Unavailable -> Launch.Blocked(current.reason)
        }
    }

    /** The setup screen's only action. Stores both values, then opens the session it just made. */
    public fun save(storageUrl: String, password: String): SaveOutcome =
        account.save(storageUrl, password).also { outcome ->
            if (outcome is SaveOutcome.Saved) {
                _state.value = running(outcome.storage, outcome.password)
            }
        }

    /**
     * Forgets the credentials and returns to setup.
     *
     * The session is closed first and unconditionally: it holds the password in memory, an HTTP
     * client and a cache of open databases, and a log-out that left those running would be a
     * log-out in name only.
     */
    public fun logOut() {
        (_state.value as? Launch.Running)?.session?.close()
        account.logOut()
        _state.value = Launch.Setup
    }

    private fun running(storage: StorageUrl, password: String): Launch.Running {
        val session = open(storage, password)
        session.start()
        return Launch.Running(session, storage)
    }
}
