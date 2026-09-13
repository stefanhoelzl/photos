package net.stho.photos.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.stho.photos.catalog.Album
import net.stho.photos.storage.StorageUrl

/**
 * §1 allows two states and one transition in each direction. This is that, and the one property
 * a log-out must have: the old session is *closed*.
 *
 * It holds the password in memory, an HTTP client and a cache of open databases, so a log-out
 * that left it running would be a log-out in name only — and nothing about the screen you end
 * up on would show it.
 */
class LauncherTest {

    @Test
    fun aConfiguredAccountOpensASessionAndStartsIt() {
        val sessions = Sessions()
        val launcher = Launcher(Account(FakeKeyring(ENDPOINT to URL, PASSWORD to "hunter2")), sessions::open)
        launcher.start()
        val running = assertIs<Launch.Running>(launcher.state.value)
        assertEquals("my-photos", running.storage.zone)
        assertEquals(1, sessions.opened.size)
        assertTrue(sessions.opened.single().started, "the model must be started, or nothing syncs")
    }

    @Test
    fun anEmptyKeyringShowsSetupAndBuildsNothing() {
        val sessions = Sessions()
        Launcher(Account(FakeKeyring()), sessions::open).start()
        assertTrue(sessions.opened.isEmpty(), "no credential, no S3 client")
    }

    @Test
    fun anUnreachableStoreBlocksRatherThanShowingSetup() {
        val launcher = Launcher(Account(FakeKeyring(unavailable = "locked")), Sessions()::open)
        launcher.start()
        assertEquals(Launch.Blocked("locked"), launcher.state.value)
    }

    @Test
    fun savingFromSetupOpensTheSessionItJustStored() {
        val keyring = FakeKeyring()
        val sessions = Sessions()
        val launcher = Launcher(Account(keyring), sessions::open)
        launcher.start()
        assertEquals(Launch.Setup, launcher.state.value)

        assertIs<SaveOutcome.Saved>(launcher.save(URL, "hunter2"))
        val running = assertIs<Launch.Running>(launcher.state.value)
        assertEquals("my-photos", running.storage.zone)
        assertEquals("hunter2", keyring.stored[PASSWORD])
    }

    @Test
    fun aRejectedSaveLeavesSetupUp() {
        val launcher = Launcher(Account(FakeKeyring()), Sessions()::open)
        launcher.start()
        assertIs<SaveOutcome.Rejected>(launcher.save("not-a-url", "hunter2"))
        assertEquals(Launch.Setup, launcher.state.value)
    }

    @Test
    fun loggingOutClosesTheSessionAndForgetsBothItems() {
        val keyring = FakeKeyring(ENDPOINT to URL, PASSWORD to "hunter2")
        val sessions = Sessions()
        val launcher = Launcher(Account(keyring), sessions::open)
        launcher.start()

        launcher.logOut()

        assertEquals(Launch.Setup, launcher.state.value)
        assertTrue(sessions.opened.single().closed, "the session holds the password in memory")
        assertTrue(keyring.stored.isEmpty())
    }

    @Test
    fun settingUpAgainAfterALogOutOpensASecondSession() {
        val sessions = Sessions()
        val launcher = Launcher(Account(FakeKeyring(ENDPOINT to URL, PASSWORD to "hunter2")), sessions::open)
        launcher.start()
        launcher.logOut()
        launcher.save(URL, "different")

        assertEquals(2, sessions.opened.size)
        assertTrue(sessions.opened.first().closed)
        assertTrue(!sessions.opened.last().closed)
    }

    private class Sessions {
        val opened = mutableListOf<FakeSession>()
        fun open(storage: StorageUrl, password: String): Session =
            FakeSession().also { opened += it }
    }

    private class FakeSession : Session {
        var closed = false
        var started = false
        // Never read by anything under test: `Launch.Running` carries the session, and only a
        // composable would reach through it.
        override val model: AppModel get() = throw UnsupportedOperationException()
        override val thumbnails: Thumbnails get() = throw UnsupportedOperationException()

        override fun start() {
            started = true
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val ENDPOINT = "endpoint"
        const val PASSWORD = "password"
        const val URL = "https://de-s3.storage.bunnycdn.com/my-photos"
    }
}
