@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import net.stho.photos.ports.Paths
import platform.posix.getpwuid
import platform.posix.getuid

/**
 * §7's `Paths`, as the XDG base directory specification defines them.
 *
 * `$XDG_CACHE_HOME/photos` and `$XDG_CONFIG_HOME/photos`, falling back to `~/.cache/photos` and
 * `~/.config/photos`. §4's on-device layout — `sync_state.db`, `shards/`, `merged.db` and
 * `blobs/` — lives under the first.
 *
 * A relative value is treated as unset, which the specification requires and which matters
 * here: resolving the cache root against whatever directory a timer happened to start in is how
 * a second copy of a 39-hour import appears.
 *
 * Both roots are resolved once, at construction, because a run whose cache root moved halfway
 * through would be writing shards into two places.
 */
public class XdgPaths(
    /** Injected so a test can answer without touching the process it runs in. */
    environment: (String) -> String? = ::systemEnvironment,
) : Paths {

    override val cacheRoot: String = xdgRoot(environment, "XDG_CACHE_HOME", ".cache")
    override val configRoot: String = xdgRoot(environment, "XDG_CONFIG_HOME", ".config")
}

/** The one directory name this application owns under either root. */
private const val APPLICATION = "photos"

private fun xdgRoot(
    environment: (String) -> String?,
    variable: String,
    fallback: String,
): String {
    val base = environment(variable).orNullIfBlank()?.takeIf(::isAbsolute)
        ?: "${home(environment)}/$fallback"
    return "$base/$APPLICATION"
}

/**
 * `$HOME`, or the passwd entry when there is none.
 *
 * A systemd user unit has `$HOME`; a `su` without `-` may not, and quietly caching into the
 * current directory instead would be the same defect the absolute-path rule exists to stop.
 */
private fun home(environment: (String) -> String?): String =
    environment("HOME").orNullIfBlank()?.takeIf(::isAbsolute)
        ?: getpwuid(getuid())?.pointed?.pw_dir?.toKString()
        ?: error("no \$HOME and no passwd entry for uid ${getuid()}")

private fun isAbsolute(path: String): Boolean = path.startsWith('/')
