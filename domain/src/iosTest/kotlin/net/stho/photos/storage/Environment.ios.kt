@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package net.stho.photos.storage

import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * The same POSIX call the linuxX64 actual makes.
 *
 * It is spelled twice rather than shared because the two test source sets have no common
 * ancestor below `commonTest`, which is the source set the `expect` lives in.
 */
internal actual fun environmentVariable(name: String): String? = getenv(name)?.toKString()
