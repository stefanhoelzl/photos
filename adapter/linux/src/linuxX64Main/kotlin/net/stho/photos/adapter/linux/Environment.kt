@file:OptIn(ExperimentalForeignApi::class)

package net.stho.photos.adapter.linux

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * One variable out of the process environment.
 *
 * A function rather than a map, because Kotlin/Native has no portable way to enumerate the
 * environment and nothing here wants to: the keyring reads two names and the XDG paths read
 * three. Both take it as a parameter so a test can answer differently without touching the
 * process it runs in — which is what lets `DbusKeyring` be pointed at a private bus.
 */
internal fun systemEnvironment(name: String): String? = getenv(name)?.toKString()
