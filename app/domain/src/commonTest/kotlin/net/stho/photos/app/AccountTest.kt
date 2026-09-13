package net.stho.photos.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.stho.photos.ports.Keyring
import net.stho.photos.ports.KeyringRead

/**
 * §1's rule that the app is either set up or it is not, and the three answers that decide it.
 *
 * The distinction these turn on is *absent* versus *unavailable*: one means show setup, the
 * other means say so and stop. Conflating them would invite someone to retype a password they
 * have already stored, into a keyring that cannot accept it.
 */
class AccountTest {

    @Test
    fun aStoredPairIsConfigured() {
        val account = Account(FakeKeyring(ENDPOINT to URL, PASSWORD to "hunter2"))
        val state = assertIs<AccountState.Configured>(account.current())
        assertEquals("my-photos", state.storage.zone)
        assertEquals("de", state.storage.region)
        assertEquals("hunter2", state.password)
        assertTrue(!state.fromEnvironment)
    }

    @Test
    fun anEmptyKeyringIsAbsent() {
        assertEquals(AccountState.Absent, Account(FakeKeyring()).current())
    }

    @Test
    fun halfAnInstallIsAbsentNotConfigured() {
        // Setup writes both, so there is no legitimate way to hold one. Reporting *absent*
        // sends the person back through the only path that produces a valid state.
        assertEquals(AccountState.Absent, Account(FakeKeyring(ENDPOINT to URL)).current())
        assertEquals(AccountState.Absent, Account(FakeKeyring(PASSWORD to "hunter2")).current())
    }

    @Test
    fun aKeyringThatCannotBeAskedIsNotAbsent() {
        val account = Account(FakeKeyring(unavailable = "the keyring is locked"))
        assertEquals(AccountState.Unavailable("the keyring is locked"), account.current())
    }

    @Test
    fun theEnvironmentBeatsTheStore() {
        val account = Account(
            FakeKeyring(ENDPOINT to URL, PASSWORD to "stored"),
            environment = mapOf("PHOTOS_ENDPOINT" to OTHER_URL, "PHOTOS_PASSWORD" to "injected"),
        )
        val state = assertIs<AccountState.Configured>(account.current())
        assertEquals("other-zone", state.storage.zone)
        assertEquals("injected", state.password)
        // Said out loud, because a stale variable silently outranking the keyring is exactly
        // the thing §1 refuses to let happen quietly.
        assertTrue(state.fromEnvironment)
    }

    @Test
    fun anEmptyVariableIsAnUnsetOne() {
        // `secrets-env` that cannot resolve an entry leaves the name defined and blank, and a
        // blank secret would otherwise reach the signer and come back as an opaque 403.
        val account = Account(
            FakeKeyring(ENDPOINT to URL, PASSWORD to "stored"),
            environment = mapOf("PHOTOS_ENDPOINT" to "  ", "PHOTOS_PASSWORD" to ""),
        )
        val state = assertIs<AccountState.Configured>(account.current())
        assertEquals("stored", state.password)
    }

    @Test
    fun savingWritesBothAndReturnsTheParsedUrl() {
        val keyring = FakeKeyring()
        val outcome = Account(keyring).save(URL, "hunter2")
        val saved = assertIs<SaveOutcome.Saved>(outcome)
        assertEquals("my-photos", saved.storage.zone)
        assertEquals(URL, keyring.stored[ENDPOINT])
        assertEquals("hunter2", keyring.stored[PASSWORD])
    }

    @Test
    fun anUnparseableUrlIsRejectedAndNothingIsWritten() {
        val keyring = FakeKeyring()
        val outcome = Account(keyring).save("de-s3.storage.bunnycdn.com/my-photos", "hunter2")
        assertIs<SaveOutcome.Rejected>(outcome)
        // A URL is a parse rather than a question for the network, so it is the one thing setup
        // can check -- and a half-written pair is what this must not leave behind.
        assertTrue(keyring.stored.isEmpty(), "nothing should be stored: ${keyring.stored}")
    }

    @Test
    fun aBlankPasswordIsRejected() {
        assertIs<SaveOutcome.Rejected>(Account(FakeKeyring()).save(URL, "   "))
    }

    @Test
    fun aStoredUrlThatWillNotParseIsUnavailableNotAbsent() {
        // Showing setup here would silently overwrite it, and the person would never learn what
        // was wrong with what they typed the first time.
        val account = Account(FakeKeyring(ENDPOINT to "nonsense", PASSWORD to "hunter2"))
        assertIs<AccountState.Unavailable>(account.current())
    }

    @Test
    fun loggingOutRemovesBoth() {
        val keyring = FakeKeyring(ENDPOINT to URL, PASSWORD to "hunter2")
        Account(keyring).logOut()
        // `service photos-cli` is one concept: removing half leaves an install that is neither
        // working nor clean.
        assertTrue(keyring.stored.isEmpty(), "both items should be gone: ${keyring.stored}")
    }

    private companion object {
        const val ENDPOINT = "endpoint"
        const val PASSWORD = "password"
        const val URL = "https://de-s3.storage.bunnycdn.com/my-photos"
        const val OTHER_URL = "https://de-s3.storage.bunnycdn.com/other-zone"
    }
}

internal class FakeKeyring(
    vararg items: Pair<String, String>,
    private val unavailable: String? = null,
) : Keyring {
    val stored = items.toMap().toMutableMap()

    override fun read(field: String): KeyringRead = when {
        unavailable != null -> KeyringRead.Unavailable(unavailable)
        else -> stored[field]?.let(KeyringRead::Found) ?: KeyringRead.Absent
    }

    override fun write(field: String, secret: String) {
        stored[field] = secret
    }

    override fun remove(field: String) {
        stored -= field
    }
}
