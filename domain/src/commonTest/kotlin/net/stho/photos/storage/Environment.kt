package net.stho.photos.storage

/**
 * One environment variable.
 *
 * Kotlin/Native bundles no test resources, so the SigV4 vector suite — 382 files that stay
 * beside the Swift tests — arrives as a path the build hands over. Reading an environment
 * variable is the one thing that has no common-code answer, so it is the only thing that has to
 * be per-platform here.
 */
internal expect fun environmentVariable(name: String): String?
