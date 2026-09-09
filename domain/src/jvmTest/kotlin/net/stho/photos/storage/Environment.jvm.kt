package net.stho.photos.storage

internal actual fun environmentVariable(name: String): String? = System.getenv(name)
