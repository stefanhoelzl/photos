package net.stho.photos.adapter.linux

/** Empty is unset (§1): `secrets-env` leaves an unresolved entry defined and blank. */
internal fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
