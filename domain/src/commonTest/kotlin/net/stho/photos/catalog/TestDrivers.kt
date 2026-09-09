package net.stho.photos.catalog

import net.stho.photos.ports.SqlDrivers

/**
 * The `SqlDrivers` every database-touching test opens through.
 *
 * `expect`/`actual` rather than injection, and the one place the repo's single-mechanism rule
 * is relaxed — deliberately, and only here. `commonTest` is compiled once per target and may
 * name only what every target can see, so a shared test cannot reach either driver; the
 * alternatives were an abstract suite with a subclass per target per test class, or moving
 * these tests to one target and giving up ever running §3's catalog rules on iOS — which is
 * the only thing that could answer §10's open question about the platform SQLite there.
 *
 * These are *test* drivers, not the shipped adapters: `:domain` cannot depend on
 * `:adapter:linux`, which depends on it. The real `NativeSqlDrivers` and `JdbcSqlDrivers` are
 * exercised by that module's own tests.
 */
internal expect val testDrivers: SqlDrivers
