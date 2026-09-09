package net.stho.photos.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import net.stho.photos.adapter.linux.DbusKeyring
import net.stho.photos.ingest.Credentials
import net.stho.photos.ingest.ExitCode
import net.stho.photos.ports.Keyring
import net.stho.photos.storage.asStorageUrl

/**
 * Puts both credentials in the desktop keyring.
 *
 * §7's "one verb, and nothing else" is about the *library* being the only way to say anything
 * about photos — no `delete`, no `--prune`, no pending state. This verb and its counterpart never
 * touch the library or the zone; they exist because reading the keyring in-process would otherwise
 * leave setup still needing `secret-tool` on PATH, which is the dependency the client was written
 * to remove.
 *
 * They reach the [Keyring] port directly rather than through [Credentials], which resolves and
 * nothing else. Storing is the one thing a run never does, so it is not part of the domain's
 * contract.
 */
internal class LoginCommand(
    private val keyring: Keyring = DbusKeyring(),
    private val console: Console = Console(),
) : CoreCliktCommand("login") {

    override fun help(context: Context): String =
        "Store the endpoint and password in the desktop keyring."

    override fun helpEpilog(context: Context): String =
        "Both values are stored under service ${Credentials.SERVICE}, told apart by a `field` " +
            "attribute, and replace whatever is already there. Nothing is written to disk, to " +
            "the environment, or to your shell history."

    private val endpoint by option(
        "--endpoint",
        help = "Storage URL. Prompted for when not given.",
    )

    override fun run(): Unit = console.translatingFailures {
        val given = endpoint
            ?: console.ask("Storage URL (e.g. https://de-s3.storage.bunnycdn.com/my-photos): ")
        if (given.isNullOrEmpty()) {
            console.error("no endpoint given")
            throw ProgramResult(ExitCode.USAGE)
        }

        // Parsed before it is stored: a mistyped URL that only fails on the next sync is exactly
        // the opaque error §1 refuses, and this is the one moment it is cheap to catch.
        val storage = given.asStorageUrl()

        val password = console.ask("Password: ", secret = true)
        if (password.isNullOrEmpty()) {
            console.error("no password given")
            throw ProgramResult(ExitCode.USAGE)
        }

        keyring.write(Credentials.Field.PASSWORD.attribute, password)
        keyring.write(Credentials.Field.ENDPOINT.attribute, given)
        console.line("stored the endpoint and password for zone ${storage.zone}")
    }
}

/** Takes both credentials back out again. */
internal class LogoutCommand(
    private val keyring: Keyring = DbusKeyring(),
    private val console: Console = Console(),
) : CoreCliktCommand("logout") {

    override fun help(context: Context): String =
        "Remove the endpoint and password from the desktop keyring."

    override fun helpEpilog(context: Context): String =
        "Removes both items, because they are one concept: half an install is neither working " +
            "nor clean. Removing nothing is not an error — the gesture means make sure they are " +
            "gone, and afterwards they are."

    override fun run(): Unit = console.translatingFailures {
        // Both, always. `service photos-cli` is one concept, and removing half of it leaves an
        // install that is neither working nor clean.
        for (field in Credentials.Field.entries) keyring.remove(field.attribute)

        // A statement about the end state rather than about what was there a moment ago: the port
        // returns nothing, and the gesture means *make sure they are gone*. Saying "nothing stored"
        // when it removed nothing would need `remove` to report what it deleted —
        // `Keyring.remove` deliberately does not.
        console.line(
            Credentials.Field.entries.joinToString(" and ") { it.attribute } +
                " are gone from service ${Credentials.SERVICE}",
        )
    }
}
