package net.stho.photos.cli

import com.github.ajalt.clikt.core.NoSuchOption
import com.github.ajalt.clikt.core.NoSuchSubcommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.stho.photos.ingest.Credentials
import net.stho.photos.ingest.ExitCode
import net.stho.photos.ports.Keyring
import net.stho.photos.ports.KeyringRead

/** A keyring that records rather than one that answers. Nothing here needs a session bus. */
private class RecordingKeyring : Keyring {
    val written = mutableListOf<Pair<String, String>>()
    val removed = mutableListOf<String>()

    override fun read(field: String): KeyringRead = KeyringRead.Absent

    override fun write(field: String, secret: String) {
        written += field to secret
    }

    override fun remove(field: String) {
        removed += field
    }
}

/**
 * `login` and `logout` — the two verbs that decide nothing at all (§7).
 *
 * They reach the `Keyring` port directly, so what is worth pinning down is the order they do things
 * in and the fact that both items move together.
 */
class SessionTest {

    /**
     * §1 refuses opaque errors, and a mistyped URL that only fails on the next sync is exactly one.
     * The parse happens before the store, so nothing reaches the keyring — and nothing prompts for
     * a password either, which is what makes this assertable without a terminal.
     */
    @Test
    fun loginRefusesAnEndpointThatIsNotAStorageUrlBeforeStoringAnything() {
        val keyring = RecordingKeyring()
        val recorder = Recorder()

        val result = assertFailsWith<ProgramResult> {
            LoginCommand(keyring, recorder.console)
                .parse(arrayOf("--endpoint", "de-s3.storage.bunnycdn.com/my-photos"))
        }

        assertEquals(ExitCode.USAGE, result.statusCode)
        assertTrue(keyring.written.isEmpty(), "reached the keyring: ${keyring.written}")
        assertEquals(
            listOf("photos-cli: missing scheme — the URL must start with https://"),
            recorder.err,
        )
    }

    /**
     * `service photos-cli` is one concept: removing half of it leaves an install that is neither
     * working nor clean.
     */
    @Test
    fun logoutRemovesBothItems() {
        val keyring = RecordingKeyring()
        val recorder = Recorder()

        LogoutCommand(keyring, recorder.console).parse(emptyArray())

        assertEquals(
            Credentials.Field.entries.map { it.attribute }.sorted(),
            keyring.removed.sorted(),
        )
        assertEquals(listOf("password and endpoint are gone from service photos-cli"), recorder.out)
    }

    // ------------------------------------------------------------------------ the command tree

    /**
     * That `--help` renders at all is worth an assertion: the `clikt` umbrella duplicates a symbol
     * on Kotlin/Native and fails the link, so this suite is where the wrong artifact would be
     * caught rather than at the end of a release build.
     */
    @Test
    fun everyVerbRendersItsOwnHelp() {
        val pages = listOf(arrayOf("--help"), arrayOf("sync", "--help"), arrayOf("login", "--help"))
        for (argv in pages) {
            assertFailsWith<PrintHelpMessage>(argv.joinToString(" ")) { photosCli().parse(argv) }
        }
    }

    @Test
    fun aVerbThatDoesNotExistIsUsage() {
        val error = assertFailsWith<NoSuchSubcommand> { photosCli().parse(arrayOf("prune")) }
        assertEquals(ExitCode.USAGE, exitCodeFor(error))
    }

    @Test
    fun anOptionSyncDoesNotHaveIsUsage() {
        val argv = arrayOf("sync", "--confirm")
        val error = assertFailsWith<NoSuchOption> { photosCli().parse(argv) }
        assertEquals(ExitCode.USAGE, exitCodeFor(error))
    }

    private fun photosCli() =
        PhotosCli().subcommands(SyncCommand(), LoginCommand(), LogoutCommand())
}
