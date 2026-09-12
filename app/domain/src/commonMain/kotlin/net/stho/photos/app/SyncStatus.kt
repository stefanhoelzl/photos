package net.stho.photos.app

import kotlin.time.Instant

/**
 * What the app knows about the last sync.
 *
 * Held apart from [Notice] on purpose: the toast is the moment, this is the record. §6's
 * Settings row reads from here, and that is what makes a missed toast harmless.
 */
public sealed interface SyncStatus {
    public data object Never : SyncStatus

    /** Blocking only on a first run, when there is no catalog to show yet (§4's cold start). */
    public data class Running(val fetched: Int, val total: Int) : SyncStatus

    public data class Succeeded(val at: Instant, val albums: Int, val photos: Int) : SyncStatus

    public data class Failed(val at: Instant, val notice: Notice) : SyncStatus
}
