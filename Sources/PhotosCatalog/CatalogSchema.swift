import Foundation

/// The DDL, in one place, matching DESIGN §3.
///
/// Every SELECT in this module names its columns. That is not style: `schema_version`
/// promises that a reader keeps working when a *newer* writer adds a column, and
/// `SELECT *` would break that promise by shifting every index.
public enum CatalogSchema {

    /// Bumped whenever a shard's layout changes in a way an older reader cannot handle.
    ///
    /// A reader reads any shard at or below this and **skips anything above it**, naming it
    /// in the sync report. A device always reads what it wrote, so a skip only ever affects
    /// whichever device is behind (§3).
    public static let version = 1

    // MARK: - Shard: meta/<album-uuid>.db

    public static let shardDDL = """
        PRAGMA page_size = 4096;

        CREATE TABLE album_info (
          id             INTEGER PRIMARY KEY CHECK (id = 1),
          album_id       TEXT NOT NULL,
          name           TEXT NOT NULL,
          parent         TEXT,
          source_path    TEXT,
          cover_photo_id TEXT,
          thumbs_id      TEXT,
          added_at       INTEGER NOT NULL,
          schema_version INTEGER NOT NULL
        );

        CREATE TABLE photo (
          id            TEXT PRIMARY KEY,
          filename      TEXT NOT NULL,
          taken_at      INTEGER,
          lat           REAL,
          lon           REAL,
          width         INTEGER,
          height        INTEGER,
          bytes         INTEGER,
          media_type    INTEGER NOT NULL,
          original_id   TEXT,
          live_video_id TEXT,
          preview_id    TEXT,
          video_id      TEXT
        );
        CREATE INDEX ix_photo_taken ON photo(taken_at);
        """

    static let albumInfoColumns =
        "album_id, name, parent, source_path, cover_photo_id, thumbs_id, added_at, schema_version"

    static let photoColumns =
        "id, filename, taken_at, lat, lon, width, height, bytes, media_type, "
        + "original_id, live_video_id, preview_id, video_id"

    // MARK: - Thumbnail pack: one blob per album

    public static let thumbDDL = """
        PRAGMA page_size = 4096;

        CREATE TABLE thumb (
          id   TEXT PRIMARY KEY,
          jpeg BLOB NOT NULL
        );
        """

    // MARK: - Merged database: on-device, derived, never uploaded

    /// `ix_photo_album` is an expression index over §3's ordering rule, so the grid query
    /// reads in order with no sort step. The index and the query must state the rule
    /// identically; if they ever drift the planner simply stops using the index, which
    /// costs speed rather than correctness.
    public static let mergedDDL = """
        CREATE TABLE IF NOT EXISTS album (
          album_id       TEXT PRIMARY KEY,
          name           TEXT NOT NULL,
          name_folded    TEXT NOT NULL,
          parent         TEXT,
          photo_count    INTEGER NOT NULL,
          date_min       INTEGER,
          date_max       INTEGER,
          lat            REAL,
          lon            REAL,
          cover_photo_id TEXT,
          thumbs_id      TEXT
        );
        CREATE INDEX IF NOT EXISTS ix_album_parent ON album(parent);
        CREATE INDEX IF NOT EXISTS ix_album_folded ON album(name_folded);

        CREATE TABLE IF NOT EXISTS photo (
          id            TEXT NOT NULL,
          album_id      TEXT NOT NULL,
          filename      TEXT NOT NULL,
          taken_at      INTEGER,
          lat           REAL,
          lon           REAL,
          width         INTEGER,
          height        INTEGER,
          bytes         INTEGER,
          media_type    INTEGER NOT NULL,
          original_id   TEXT,
          live_video_id TEXT,
          preview_id    TEXT,
          video_id      TEXT,
          PRIMARY KEY (album_id, id)
        );
        CREATE INDEX IF NOT EXISTS ix_photo_album
          ON photo(album_id, taken_at IS NULL, taken_at, filename);
        CREATE INDEX IF NOT EXISTS ix_photo_taken ON photo(taken_at);
        CREATE INDEX IF NOT EXISTS ix_photo_geo ON photo(lat, lon) WHERE lat IS NOT NULL;
        """

    /// §3's sort order, as one expression. Used by the grid query and by the index above.
    ///
    /// `filename` compares under BINARY collation — a UTF-8 byte compare — so the order is
    /// identical on iOS and Linux without depending on either platform's collation tables.
    public static let photoOrder = "taken_at IS NULL, taken_at, filename"

    // MARK: - Sync state: on-device, beside the shards

    /// Kept out of the merged database on purpose. If the ETags lived there, losing the
    /// merged DB would mean re-fetching all 288 shards; here it can be deleted and rebuilt
    /// with no network at all (§4).
    public static let syncStateDDL = """
        CREATE TABLE IF NOT EXISTS shard_state (
          album_id   TEXT PRIMARY KEY,
          etag       TEXT NOT NULL,
          fetched_at INTEGER NOT NULL
        );
        """
}
