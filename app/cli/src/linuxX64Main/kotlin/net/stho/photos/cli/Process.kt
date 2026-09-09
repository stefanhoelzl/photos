@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.cli

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.sysconf

/**
 * The few things the domain needs to know about the process it is running in.
 *
 * None of them is a port. §7's third reason — the domain would otherwise reach for it *statically*
 * — is about values a run needs to be able to *vary*, and these do not: they are read once, at
 * startup, and passed in as data. A port here would only be a second way to spell `getenv`.
 */

/**
 * The variables the domain asks about, and no others.
 *
 * A map rather than a live query, which is what `Credentials` documents wanting: it needs three
 * values once, not a window onto the environment. Kotlin/Native cannot enumerate `environ`
 * portably anyway, and naming the three here means the set a run depends on is readable in one
 * place rather than inferred from call sites.
 */
internal fun processEnvironment(): Map<String, String> = buildMap {
    for (name in listOf("HOME", "PHOTOS_PASSWORD", "PHOTOS_ENDPOINT")) {
        variable(name)?.let { put(name, it) }
    }
}

/**
 * The library root's default (§7).
 *
 * Defaulting to the working directory pairs with the `.photosignore` marker guard: `photos-cli
 * sync` typed in the wrong directory finds no marker and refuses, rather than deciding the library
 * is empty. So the convenient default is also the safe one.
 */
internal fun workingDirectory(): Path = memScoped {
    val buffer = allocArray<ByteVar>(PATH_LIMIT)
    val answer = getcwd(buffer, PATH_LIMIT.convert())
        ?: error("cannot read the working directory")
    Path(answer.toKString())
}

/**
 * Where a run stages derivatives before uploading them.
 *
 * `$TMPDIR` when a systemd unit set one — `PrivateTmp=` gives the unit its own, which is where a
 * 39-hour run's tens of gigabytes of intermediates should land — and `/tmp` otherwise.
 */
internal fun temporaryDirectory(): Path = Path(variable("TMPDIR") ?: "/tmp")

/**
 * Encoder workers, one per core.
 *
 * The default rather than a rule: §7 budgets ~400 MB per worker, so a machine with more cores than
 * memory is exactly what `--jobs` is for.
 */
internal fun availableProcessors(): Int = sysconf(_SC_NPROCESSORS_ONLN).toInt().coerceAtLeast(1)

/** Empty is unset (§1): `secrets-env` leaves an unresolved entry defined and blank. */
private fun variable(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

/** `PATH_MAX` on Linux. Spelled out because `getcwd` needs a buffer, not a promise. */
private const val PATH_LIMIT = 4096
