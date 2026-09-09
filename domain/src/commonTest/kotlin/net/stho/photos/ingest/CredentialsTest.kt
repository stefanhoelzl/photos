package net.stho.photos.ingest

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import net.stho.photos.CredentialFailure
import net.stho.photos.catalog.deleteTemporaryDirectories
import net.stho.photos.catalog.temporaryDirectory
import net.stho.photos.ports.KeyringRead

/**
 * Which source answers, and what happens when none does.
 *
 * The precedence is a safety property, not a convenience: production takes both credentials from
 * the keyring, development overrides them from the environment under `secrets-env`, and the two
 * must not be able to quietly swap places.
 */
class CredentialsTest {

    @AfterTest
    fun cleanUp(): Unit = deleteTemporaryDirectories()

    private fun keyring(vararg answers: Pair<String, String>): FakeKeyring =
        FakeKeyring(answers.associate { (field, secret) -> field to KeyringRead.Found(secret) })

    @Test
    fun theEnvironmentPasswordWinsAndSaysWhereItCameFrom() {
        val keyring = keyring("password" to "from-keyring")
        val credentials = Credentials(mapOf("PHOTOS_PASSWORD" to "from-secrets-env"), keyring)

        val password = credentials.password()

        assertEquals("from-secrets-env", password.value)
        assertEquals(Credentials.Source.ENVIRONMENT, password.source)
        assertTrue(keyring.asked.isEmpty(), "the keyring must not be consulted")
    }

    @Test
    fun withNoVariableSetTheKeyringAnswers() {
        val credentials = Credentials(emptyMap(), keyring("password" to "from-keyring"))

        val password = credentials.password()

        assertEquals("from-keyring", password.value)
        assertEquals(Credentials.Source.KEYRING, password.source)
    }

    /**
     * Two items live under one service, so a lookup that named only the service could get either
     * one. Each credential must ask for its own field by name.
     */
    @Test
    fun thePasswordAndTheEndpointAskTheKeyringForDifferentFields() {
        val keyring = keyring(
            "password" to "secret",
            "endpoint" to "https://de-s3.storage.bunnycdn.com/my-photos",
        )
        val credentials = Credentials(emptyMap(), keyring)

        credentials.password()
        credentials.storage()

        assertEquals(listOf("password", "endpoint"), keyring.asked)
    }

    /**
     * `secrets-env` that cannot resolve an entry leaves the name defined and empty. Treating that
     * as a password would send a blank secret to the signer and get back an opaque 403 — the one
     * thing §1 says a credential failure must never be.
     */
    @Test
    fun anEmptyVariableIsNotAPasswordAndFallsThroughToTheKeyring() {
        for (blank in listOf("", "   ", "\n")) {
            val credentials =
                Credentials(mapOf("PHOTOS_PASSWORD" to blank), keyring("password" to "from-keyring"))

            assertEquals(Credentials.Source.KEYRING, credentials.password().source)
        }
    }

    /** A keyring that cannot be reached is a deferral (75), not a failure — §7's exit codes. */
    @Test
    fun aKeyringThatIsLockedDefersRatherThanFailing() {
        val keyring = FakeKeyring(
            mapOf("password" to KeyringRead.Unavailable("the keyring is locked; log in and it will unlock")),
        )
        val credentials = Credentials(emptyMap(), keyring)

        val failure = assertFailsWith<CredentialFailure.KeyringUnavailable> { credentials.password() }

        assertTrue(failure.message.contains("locked"))
    }

    /** A keyring that answered and holds nothing is a real error (3), and says what to do. */
    @Test
    fun aKeyringWithNoSuchItemIsARealError() {
        val credentials = Credentials(emptyMap(), FakeKeyring())

        val failure = assertFailsWith<CredentialFailure.NoSuchItem> { credentials.password() }

        assertEquals("password", failure.field)
        assertTrue(failure.message.contains("photos-cli login"))
    }

    @Test
    fun theEndpointResolvesFlagThenEnvironmentThenKeyring() {
        val keyring = keyring("endpoint" to "https://de-s3.storage.bunnycdn.com/from-keyring")

        val fromKeyring = Credentials(emptyMap(), keyring).storage()
        assertEquals("from-keyring", fromKeyring.value.zone)
        assertEquals(Credentials.Source.KEYRING, fromKeyring.source)

        val environment =
            mapOf("PHOTOS_ENDPOINT" to "https://de-s3.storage.bunnycdn.com/my-photos")
        val fromEnvironment = Credentials(environment, keyring).storage()
        assertEquals("my-photos", fromEnvironment.value.zone)
        assertEquals("de", fromEnvironment.value.region)
        assertEquals(Credentials.Source.ENVIRONMENT, fromEnvironment.source)

        val fromFlag = Credentials(environment, keyring)
            .storage(override = "https://uk-s3.storage.bunnycdn.com/other")
        assertEquals("other", fromFlag.value.zone)
    }

    /**
     * The default pairs with the marker guard: typed in the wrong directory it finds no
     * `.photosignore` and refuses, rather than deciding the library is empty.
     */
    @Test
    fun theLibraryRootDefaultsToTheWorkingDirectory() {
        val working = temporaryDirectory("ingest-cwd")
        val credentials = Credentials(emptyMap(), FakeKeyring())

        assertEquals(working, credentials.libraryRoot(workingDirectory = working))
    }

    @Test
    fun theLibraryPathExpandsATildeAndMustNameADirectoryThatExists() {
        val home = temporaryDirectory("ingest-home")
        val credentials = Credentials(mapOf("HOME" to home.toString()), FakeKeyring())

        val expanded = credentials.libraryRoot(workingDirectory = Path("/"), override = "~")
        assertEquals(home, expanded)
        assertFalse(expanded.toString().startsWith("~"))

        assertFailsWith<CredentialFailure.MissingLibraryRoot> {
            credentials.libraryRoot(workingDirectory = Path("/"), override = "/definitely/not/here")
        }
    }
}
