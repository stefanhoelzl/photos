# Photo album viewer over S3-compatible storage

A personal iOS app that browses photo albums held in a bunny.net storage zone, plus a Linux
CLI that populates and maintains it. No backend service.

Derived from a design interview; every decision below was made explicitly, and the platform
behaviour is verified against a live storage zone rather than assumed.

Specifics of the library being ingested — inventory, derivative sizing, geocoding data, the
first-run plan — live in **`INGEST.md`**, which is not committed.

**Mockups** are the visual companion to §6 and §8 — 17 screens, self-contained, open directly
with no server:

| file | contents | committed |
|---|---|---|
| `mockups/placeholder.html` | synthetic tiles, generic album names | **yes** — 140 KB |
| `mockups/index.html` | the real library's thumbnails and names | no — personal data |

Both render identically; only the content differs.

---

## 1. Overview and constraints

**Components**
- **Storage** — one bunny.net storage zone (Frankfurt), S3-compatible API.
- **Ingest CLI** — Linux, single static binary. Populates the zone from a local library root,
  and pulls phone-uploaded albums back down.
- **iOS app** — browses the zone; can upload new albums.

**Hard constraints**
- **No backend.** Every derivative is pre-generated at ingest; nothing is resized on demand.
- **Multi-writer.** Albums are written from the laptop *and* the phone. Write conflicts must be
  structurally impossible, not resolved.
- **Single user**, one or two devices. No accounts, no sharing.
- The local library (`$LIBRARY_ROOT`, e.g. `~/Pictures/Albums`) is the master copy.

### bunny.net constraints that shape everything

Supported: GET with **byte ranges**, HEAD, conditional GET (**ETag / If-None-Match**),
ListObjectsV1+V2 with **prefix and delimiter**, presigned URLs (1 s–7 d), multipart upload.

**Not** supported: **object versioning**, lifecycle policies, batch `DeleteObjects`,
**custom `x-amz-meta-*` metadata**, SSE, object lock, tagging, ACLs.

Limits: 500 req/s, 1 Gbps, 1000 keys per list page, 10 000 multipart parts.
S3 compatibility can only be enabled **when the zone is created**.

Two consequences carried throughout:
- **No undelete.** Any delete is permanent. This is why the app never issues a `DELETE`.
- **No object metadata**, so all EXIF and GPS must live in the catalog.

### Credentials and first-run setup

**One credential, not two.** A single storage password is used for syncing, browsing and
uploading alike. **First launch shows a setup screen** collecting two things:

| field | notes |
|---|---|
| **storage URL** | e.g. `https://de-s3.storage.bunnycdn.com/my-photos` |
| **password** | the zone's **Secret Access Key** |

**One URL carries everything**, so there is no separate endpoint or zone field:

| parsed from | gives |
|---|---|
| host | the S3 endpoint |
| host's `<region>-s3` prefix | the **signing region** |
| first path segment | the zone name — which on bunny.net **is** the Access Key ID |

Verified live: the zone name works as both the path segment and the `Credential=` access key.

> Bunny.net-specific. Where the access key ID is unrelated to the bucket name, the URL no longer
> determines it and a separate field returns.

**Key handling.** The password lives in the Keychain under **biometric access control**
(`kSecAccessControlBiometryCurrentSet`). It is unlocked with Face ID **once per app launch**,
held in memory for that session, and wiped on termination. Background uploads are unaffected:
they run against **pre-signed URLs** (bunny.net presigned URLs are valid 1 s–7 days) generated
at upload time, so a force-quit and relaunch resumes without needing the key again.

> **Why one key and not a read-only/write pair.** The two-key split limited the blast radius of
> an extracted key — read-only could not destroy anything. But it required the read-only key to
> be stored *always available* in order to browse without a prompt, which meant anyone with the
> unlocked device could read the whole library. A single biometric-gated key inverts that: the
> library is unreadable without your face, at the cost of a larger blast radius if the key is
> ever extracted while unlocked. Given the laptop holds the master copy, and the app **never
> issues a DELETE** (§7), that trade was judged worthwhile — and it removes a concept,
> a Settings row and a password-manager entry.

**Settings › Account is read-only.** It shows the storage URL and a masked password, and offers
**Log out**. Nothing there is editable and nothing carries a disclosure arrow.

**Credentials are entered exactly once, at setup.** Changing either the URL or the password
means logging out and setting up again. There is therefore no edit sheet, no in-place
replacement, and no second path to a configured state — the app is either set up or it is not.
The password row shows a masked value with its protection (Face ID) as secondary text, never a
storage location.

**Credentials are not validated with a test request when saved.** Accepted cost: a mistyped
password is stored happily and only surfaces on the next request. **Compensating requirement:
sync and upload failures must report the HTTP status and name the cause** — e.g.
*"Sync failed: 403 Forbidden — log out and check the password"* — never an opaque error.

**Password AutoFill.** The app declares an Associated Domain (`webcredentials:<your-domain>`)
and that domain serves `/.well-known/apple-app-site-association` over HTTPS with no redirect.
Without it, iOS shows only a generic key icon and the entry must be found by hand. Password
fields use `textContentType = .password` alongside a `.username` field.

**The laptop is different.** The systemd unit needs unattended write access, so its copy of the
password is encrypted with `systemd-creds encrypt --with-key=host` and loaded via
`LoadCredential=`. TPM sealing may be unavailable, in which case it is host-key encrypted rather
than hardware-bound.

Local credential mapping for the CLI lives in `.proton.yaml`:

| env var | contents |
|---|---|
| `PHOTOS_ENDPOINT` | the full **storage URL** — host and zone in one value |
| `PHOTOS_PASSWORD` | the zone's Secret Access Key |

> **One key here too.** An earlier draft gave the CLI a read-only/read-write split, on the
> grounds that the hourly systemd unit should not hold a key that can delete the library. It
> was dropped: bunny.net issues one Secret Access Key per zone, so the split would have to be
> maintained by hand for a single unattended process whose blast radius is already bounded by
> `--prune` never running unattended (§7). One key, one concept, everywhere.

No encryption beyond TLS. The zone is private (AWS4-signed requests only, no public pull zone).
Accepted: anyone with the read key, and bunny.net itself, can read the photos.

---

## 2. Storage layout

All traffic goes **directly to the S3 endpoint**. No CDN pull zone — bunny.net's pricing page
states **free API egress**, so the direct path is both the simplest and the cheapest.
Consequence: no edge caching, so on-device caching matters.

### Keys

The zone holds exactly two prefixes. Every key is a UUID.

```
meta/<album-uuid>.db         per-album catalog shard
blob/<object-uuid>           every original, derivative and thumbnail pack
```

There is no path in any key, no extension on any blob, and nothing in the zone names a photo,
an album or a folder. A shard says what its objects are; the objects say nothing about
themselves.

**Why UUIDs and not paths.** Keys mirroring the library reads well and makes ingest obviously
idempotent, but it welds three unrelated things together: where a file sits on disk, what
identifies it forever, and what has to be copied when either changes. Renaming an album then
means copying every object under it — 1,755 photos for `Neuseeland` — and album names have to
stay globally unique because they *are* the namespace. With UUID keys, a rename or a re-parent
is a metadata write; nothing moves.

**Why not content-addressed.** Hashing the content would give the same stability plus dedup,
and it was rejected on one concrete ground: **on iOS a hash forces reading the whole asset
before the first byte can be uploaded**, which is exactly what §8's background upload exists to
avoid. It is the same reasoning that made `UNSIGNED-PAYLOAD` the default in §10 — the platform
punishes an extra full pass over every file. Skipping dedup also removes the cost that comes
with it: with one referent per blob, **deleting an album is deleting the objects its shard
lists** — bounded, no reference counting, no garbage collector in the normal path.

**Blobs are immutable.** A blob's content never changes once written. Re-encoding a derivative
mints a *new* UUID, points the shard at it, and deletes the old object. This is what makes
§6's cache correct for free: the cache keeps browsed content indefinitely and evicts nothing,
so a key whose content could change would need revalidating on every hit. Instead, a changed
`preview_id` in the shard *is* the invalidation signal.

**What UUID keys buy beyond renames.** Every key becomes flat ASCII, which removes three
hazards at once:

- bunny.net's implicit **directory markers** can no longer nest. Writing `meta/<uuid>.db`
  still produces the single zero-byte key `meta/`, which a prefix LIST returns with `Size 0`
  and no ETag, so **the diff still skips keys ending in `/`** — but there is exactly one such
  key per prefix now, rather than one per folder level, and none that could be mistaken for a
  shard.
- **NFC normalisation** stops being a wire concern. It still governs text *stored in* the
  catalog and the matching of a local folder to its shard, but no key can ever differ by
  composition.
- Keys can no longer carry **Private Use Area characters or codepage mojibake** from an
  unclean library, so `encoding-type=url` on LIST protects the ~292 `meta/` keys rather than
  ~34,000.

Costs, stated plainly: **the zone is no longer legible.** `blob/9f2c1ab7-…` tells you nothing
without the catalog, and diagnosing storage means reading a shard first. And two devices that
independently create an album called `Sommer` no longer collide on one key, so §2's write
guard does not apply to album *creation* — see below.

### Album identity and hierarchy

- **Album identity is its UUID**, minted once when the album is first ingested. Renaming,
  re-parenting and moving photos between albums are all metadata writes.
- `album_info.source_path` records the folder the album came from; the CLI uses it to
  reconnect a local directory to its shard. It is a hint, not an identity.
- Albums form a **real hierarchy**: `parent` holds the parent album's UUID, or NULL at the
  root. A `parent` that resolves to no shard is not an error — the album surfaces at top
  level and the sync reports it, so no album can become unreachable because one object failed
  to arrive.
- **An album has sub-albums XOR photos, never both. Ingest rejects a mixed folder by name.**
  This makes the data model and the album screen each lose a branch.
- Album names need not be unique. Two shards claiming the same name under the same parent are
  **both shown, never merged**, and named in the sync report. Merging is a destructive guess
  about intent, and this system does not resolve conflicts.

### Write-conflict model

- **One shard per album, single owner.** `meta/<album-uuid>.db` is rewritten wholesale by
  whichever device writes it. Merge is concatenation; delete is a rewrite of that one shard.
- Accepted risk: simultaneous writes to one album from both devices are last-writer-wins.
  Safe in practice because every device holds the full merged metadata and can therefore
  rewrite any shard correctly, and one person with two devices does not write concurrently.
  **`If-Match` on PUT is honoured — verified against the live zone**: a PUT with a stale ETag
  returns **412**, a PUT with the current ETag succeeds. So the single-owner rule is *guarded*,
  not merely unlikely to break: every shard rewrite carries `If-Match: <etag last read>` and a
  412 means "someone else wrote it — re-read and retry".
- The guard covers *modification*, not *creation*: a brand-new album has no prior ETag to
  match against, which is why duplicate albums are surfaced rather than prevented.

---

## 3. Catalog

### Per-album shard — `meta/<album-uuid>.db`

```sql
PRAGMA page_size = 4096;

CREATE TABLE album_info (
  id             INTEGER PRIMARY KEY CHECK (id = 1),
  album_id       TEXT NOT NULL,      -- this album's uuid; equals its key
  name           TEXT NOT NULL,
  parent         TEXT,               -- parent album's uuid, NULL at the root
  source_path    TEXT,               -- folder this came from; a hint, not identity
  cover_photo_id TEXT,               -- overrides the default cover
  thumbs_id      TEXT,               -- blob holding this album's packed thumbnails
  added_at       INTEGER NOT NULL,
  schema_version INTEGER NOT NULL
);

CREATE TABLE photo (
  id            TEXT PRIMARY KEY,    -- row identity; survives re-encoding
  filename      TEXT NOT NULL,       -- NFC, with extension, exactly as on disk
  taken_at      INTEGER,             -- epoch seconds; NULL when no EXIF date
  lat           REAL,
  lon           REAL,
  width         INTEGER,             -- display dimensions, already rotated
  height        INTEGER,
  bytes         INTEGER,
  media_type    INTEGER NOT NULL,    -- 0 photo · 1 video · 2 live photo
  original_id   TEXT,                -- blob: the original as uploaded
  live_video_id TEXT,                -- blob: paired MOV, when media_type = 2
  preview_id    TEXT,                -- blob: 2048px HEIC
  video_id      TEXT                 -- blob: 1080p H.264 transcode
);
CREATE INDEX ix_photo_taken ON photo(taken_at);
```

**A typed single row, not a key/value bag.** `album_info` holds real columns with real types,
so the schema documents itself and a mistyped field fails when the statement is prepared
rather than reading back NULL. The price is that adding a field is a migration, which
`schema_version` handles.

**Roles live in the schema, not in prefixes.** A photo row states exactly which objects it
owns through four nullable id columns, rather than the reader inferring them from
`media_type` and a naming convention. A still photo has `original_id` and `preview_id`; a
Live Photo adds `live_video_id`; a video has `video_id` and a `preview_id` poster but no
`original_id`, since §5 keeps video originals on the laptop; a developed CR2 has an
`original_id` pointing at the JPEG, not the RAW.

**`photo.id` is its own UUID**, separate from every object id. It is what `cover_photo_id`
points at and what the thumbnail pack keys by, so it has to survive a derivative being
re-encoded — which mints a new `preview_id` but leaves the photo the same photo.

**No `ext`, no `orientation`, no `sort_key`.** Each was removable once something else carried
its meaning. `filename` holds the extension, so `ext` only duplicated its tail and would have
needed reassembly rules for extensionless names, `IMG.2013.07.jpg` and `.JPG` case.
`width`/`height` are stored **already rotated**, so `orientation` had no consumer: C bakes
orientation into thumbs and previews, and iOS applies the EXIF tag itself when decoding an
original. `sort_key` is replaced by an expression index — see *Dates, locations, sorting*.

**Measured sizes** (metadata only): 2 photos → 24 KB · 104 photos → **32 KB** ·
500 → 88 KB · 1,755 → 232 KB · 4,759 → 576 KB. A full first sync is therefore roughly
**one small request per album** — see `INGEST.md` for this library's total.

### Per-album thumbnails — a packed blob

Thumbnails are a single SQLite file per album, stored as an ordinary immutable blob and
referenced from the shard by `album_info.thumbs_id`:

```sql
CREATE TABLE thumb (
  id    TEXT PRIMARY KEY,          -- = photo.id
  jpeg  BLOB NOT NULL              -- 256px JPEG q75, ~8.7 KB
);
```

At ~9 KB per thumbnail a 100-photo album is ~0.9 MB and a 1,750-photo album ~15 MB, so **one
request opens an album's entire grid, offline**. Shard the pack only if an album exceeds a few
thousand photos.

**Why a pack rather than a blob per thumbnail.** One blob each would be uniform with
everything else and would make adding a photo a 9 KB write instead of a 15 MB repack. It
would also cost 1,755 round trips to open `Neuseeland` — roughly 12 s cold against one 15 MB
GET at ~2 s. bunny.net charges no per-request fee, so this is latency, not money; but §6's
promise is that a grid opens in one request, offline, and that promise is what the pack buys.

**Why the pack is referenced rather than living under its own prefix.** Because the reference
*is* the change signal. A `thumbs/` prefix would need either a second LIST every sync or an
ordering rule between two writes; a `thumbs_id` that changed means the meta shard changed,
which §4's single LIST already sees. The cost is repacking a whole album to add one photo.

### Merged local database (on device)

Rebuilt from the shards; never uploaded.

```sql
CREATE TABLE album (
  album_id      TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  name_folded   TEXT NOT NULL,     -- lowercased, diacritics stripped (ö→o, ü→u, ß→ss)
  parent        TEXT REFERENCES album(album_id),
  photo_count   INTEGER NOT NULL,
  date_min      INTEGER,
  date_max      INTEGER,
  lat           REAL,              -- computed at rebuild, not stored in the shard
  lon           REAL,
  cover_photo_id TEXT,
  thumbs_id     TEXT
);
CREATE INDEX ix_album_parent ON album(parent);
CREATE INDEX ix_album_folded ON album(name_folded);

CREATE TABLE photo (
  id, album_id TEXT NOT NULL REFERENCES album(album_id),
  filename, taken_at, lat, lon, width, height, bytes,
  media_type, original_id, live_video_id, preview_id, video_id,
  PRIMARY KEY (album_id, id)
);
CREATE INDEX ix_photo_album ON photo(album_id, taken_at IS NULL, taken_at, filename);
CREATE INDEX ix_photo_taken ON photo(taken_at);
CREATE INDEX ix_photo_geo   ON photo(lat, lon) WHERE lat IS NOT NULL;
```

**Measured at this library's scale** — 337 albums, 34,607 photo rows, all indexes present:
**13.1 MB**, rebuilt in **0.39 s**. An earlier path-keyed prototype of the same shape was
4.53 MB; the difference is UUID keys, which add six 36-character identifier columns to every
photo row — about 8 MB across 34,607 of them. That is the price of §2's addressing, paid
here, and the conclusion is unchanged: a large library is still small enough to hold entirely
and query locally.

> Storing those identifiers as 16-byte blobs rather than 36-character text would recover
> roughly a third of the file. Not done: 13 MB is not a problem on either platform, and
> readable ids in `sqlite3` are worth more than the megabytes while the system is young.

**Why a merged DB rather than attaching the shards:** SQLite's `SQLITE_MAX_ATTACHED` defaults to
10 and cannot exceed 125, so 288 shards cannot be attached. More fundamentally, ATTACH gives no
shared index — the map and the date filter would scan 288 tables.

**Access is one writer, many readers, in WAL mode.** The rebuild holds a single write
transaction for 1–3 s (§4) while the album list may be on screen; WAL is what lets readers see
the pre-transaction snapshot for its whole duration and switch at commit, rather than blocking.

### Dates, locations, sorting

- **Dates come from EXIF `DateTimeOriginal` only.** No inference from album names, no file
  mtime. 99.5% of photos have one; the rest are undated and absent from date filters.
- **Locations come from EXIF GPS only.** An album's pin is the centroid of its own tagged
  photos; a **container's is the centroid of all its descendants'**, since a container owns no
  photos of its own. Both are computed during the rebuild, so nothing about location is stored
  in a shard and no stored pin can drift from the photos beneath it. `lat IS NULL` is the whole
  "not on the map" test.
  > Albums whose photos carry no GPS are placed by a **one-time throwaway script that writes
  > coordinates into the photo files themselves**, before ingest ever sees them. The ingest
  > tool therefore has no location logic, no geocoder and no `album-locations.tsv` — and the
  > coordinates outlive this project, because they live in the library.
- **Sort within an album: oldest first** — EXIF date ascending, falling back to filename for
  undated photos, which collect at the end. There is no stored sort column: the rule lives in
  the query, and `ix_photo_album` is an expression index over the same rule so reads stay
  index-ordered with no sort step.
  ```sql
  SELECT … FROM photo WHERE album_id = ? ORDER BY taken_at IS NULL, taken_at, filename;
  ```
  `filename` compares under BINARY collation — a UTF-8 byte compare — so the ordering is
  identical on iOS and Linux without depending on either platform's collation tables.
- **Album list default sort: date, newest first**, ascending/descending toggleable, or by name.
  Albums with no dated photos at all collect at one end.
- **Search:** album names, substring, case- and diacritic-insensitive via `name_folded`.
  No fuzzy matching (too noisy on short names), no filename search.
- **Cover photo:** first photo in sort order — the album's earliest — overridable per album.
  A container's cover is resolved by descending into children until a photo is found, *unless*
  one has been set explicitly, so `cover_photo_id` is storable on containers too.

### Schema versioning

`schema_version` is a single integer. A reader reads any shard at or below the version it
knows and **skips anything newer**, naming it in the sync report. A device always reads what
it wrote, so a skip only ever affects whichever device is behind, and it is visible and
self-correcting once that device updates.

> **The CLI must treat a skipped shard as *unreadable*, not *absent*.** §7 reconciles the local
> library against the bucket, and a skipped album that reads as "not there" would be re-uploaded
> as a new one — with UUID keys there is no key collision to stop it, so the result is a silent
> duplicate.

---

## 4. Sync algorithm

Discovery is a **single `ListObjectsV2` on the flat `meta/` prefix**. The response carries the
ETag and size of every shard, so that one request *is* the sync plan. There is no manifest
object and therefore no shared mutable state to conflict on.

```
1. LIST meta/  (encoding-type=url)
2. diff returned ETags against sync_state.db
     changed / new ETag  → download that shard
     key absent          → album deleted → drop its rows
3. if anything changed: rebuild the merged DB by replaying all shards (~1–3 s)
4. store the new ETags
```

**One LIST covers thumbnails too**, because a thumbnail pack is an ordinary blob referenced by
`album_info.thumbs_id` (§3): if the pack changed, the shard that points at it changed, and step
2 already saw that. The same holds for every derivative — nothing in the zone can change
without some shard's ETag moving.

The merged DB is **rebuilt wholesale** rather than spliced incrementally. There is no partial
update path, so stale rows are impossible by construction — which is also why there is no
"rebuild index" button in the UI: nothing could ever need repairing by hand.

The rebuild happens **in place, inside one transaction** — `BEGIN IMMEDIATE`, delete, replay,
`COMMIT` — rather than building a temp file and renaming over it. There is only ever one
merged DB on disk, and SQLite's guarantee that readers see the old contents until commit is
what makes the swap atomic. **WAL mode** is required, not optional: without it the 1–3 s write
transaction blocks the album list that is on screen while it runs.

### On-device layout

```
<cache>/
  sync_state.db      album_id → etag, fetched_at
  shards/<uuid>.db   the shards, ~12 MB — source of truth for a rebuild
  merged.db          derived; deletable at any moment
  blobs/             browse-to-cache (§6)
```

`sync_state.db` exists so that the merged DB stays purely derived. Putting the ETags inside
`merged.db` would make losing it mean re-fetching all 288 shards; keeping them beside the
shards means `merged.db` can be deleted and rebuilt with no network at all. Deleting
`sync_state.db` on its own forces a full re-fetch, which is a free repair path.

**Reported, not resolved.** A sync returns what it could not make sense of rather than guessing:
albums whose `parent` resolved to nothing (surfaced at top level), shards too new to read
(skipped), and duplicate names under one parent (both shown). Each is a condition the design
deliberately does not auto-correct.

---

## 5. Derivatives

Two tiers only:

| tier | typical size | where | purpose |
|---|---|---|---|
| **256px q75 JPEG** | ~9 KB | packed per album, one blob | the grid |
| **2048px HEIC** | ~370 KB | one blob per photo | fullscreen |

Rejected: 320px and 512px thumbs (a 512px tier is 3.7× larger and breaks the
one-request-per-album property); 1280px previews (visibly soft on a 3× display).

For this library's actual totals see `INGEST.md`.

### Format: JPEG thumbs, HEIC previews

A PSNR-matched benchmark (HEIC/AVIF quality raised until they matched the JPEG baseline):

| tier | JPEG | HEIC | AVIF |
|---|---|---|---|
| 256px thumb | **0.32 GB** | 0.90 GB (279%) | 0.47 GB (145%) |
| 2048px preview | 14.57 GB | **12.35 GB (85%)** | 10.57 GB (72%) |

At thumbnail size the container overhead swamps any codec advantage, so **JPEG wins outright**.
At preview size AVIF is smallest but decodes in software; **HEIC is 15% smaller than JPEG and
still hardware-decoded** via the HEVC block, so swiping stays fast.

### Media handling

- **Live Photos** — the preview is a plain still and needs no identifier. Long-press fetches the
  **original HEIC + original MOV**, whose `content.identifier` values already match,
  and hands both to `PHLivePhotoView`. No maker-note surgery at ingest.
- **Video** — everything is transcoded to a uniform **1080p H.264/AAC MP4, faststart**. This
  covers formats iOS cannot play at all (AVI, MPG, 3GP) and tames large camera MP4s.
  **Originals stay on the laptop.**
- **RAW** — CR2 is developed to a full-resolution JPEG at ingest and only the derivative is
  uploaded; the RAW stays on the laptop. Where a RAW has no JPEG sibling the laptop copy is that
  photo's sole archive, so the bucket is explicitly *not* a backup for it.

---

## 6. iOS app

> See **`mockups/placeholder.html`** for all 17 screens rendered at device size — album list,
> container, grid, pinch density, both viewer states, both map representations, set-cover
> dialog, settings, log out, first-run setup, and the five upload steps.

**Minimum iOS 18.** SwiftUI throughout, except three components where UIKit is required:

| component | why SwiftUI cannot do it |
|---|---|
| photo grid | no **prefetch API** — `LazyVGrid` fires `onAppear` when a cell is already visible, far too late for a 385 KB fetch; no **interactive layout transition** for pinch-to-density; no **drag-to-select** range gesture |
| fullscreen pager | zoom + paging + drag-to-dismiss composition |
| map | no annotation clustering (`MKMarkerAnnotationView.clusteringIdentifier`) |

`PHLivePhotoView` is a small `UIViewRepresentable` either way.

### Navigation and chrome

**No tab bar.** The album list is the root; everything else is reached from its nav bar.
On first launch the setup screen (§1) is shown instead, and Settings › Account can change the
endpoint, zone and key, or switch zones entirely, at any time.

Following the iOS 26 Liquid Glass HIG: the nav bar is **two rows** — back button and actions on
top, **large title 34pt bold** beneath (17pt semibold when scrolled). Fixed bar buttons render
as **Liquid Glass circles containing icons**, with content scrolling beneath them.

Every list/grid screen carries the same four trailing icons — **gear · the other representation
· sort · upload** — and the toggle always shows the view you switch *to*:

| screen | trailing icons |
|---|---|
| Albums (list) | gear, **map**, sort, upload |
| Albums (map) | gear, **list**, sort, upload |
| Container list | gear, **map**, sort, upload |
| Album grid | gear, **map**, sort, upload |
| Album map | gear, **grid**, sort, upload |
| Fullscreen viewer | gear, share, set-cover |
| Settings | — |

**There is no hidden "…" menu anywhere.** Every icon performs one visible action.

**The map is a representation of the album list, not a destination** — same title, subtitle and
icons, toggled rather than pushed. The same holds one level down: an album's grid and its map
are two views of one album.

**Colour carries meaning; it is never decoration.** Standard bar buttons are **monochrome** —
the glyph takes the label colour and the glass adapts to what is behind it. Blue-for-tappable is
the pre-iOS-26 idiom and is not used.

| context | glyph | glass |
|---|---|---|
| nav bars | label colour | `.regular` (adaptive) |
| over a photo | label colour (white) | `.clear` + **dimming scrim** |
| destructive (clear cache) | **red** | `.regular` |
| active state (photo is the cover) | **gold** | `.regular` |
| progress fill | tinted | — |

### Fullscreen viewer

One screen, two states. Chrome is identical in both — back, then gear/share/set-cover, a
filmstrip, and a bottom line of date and an `Original …` badge. A **LIVE badge appears once**,
top-left, and is the only thing that differs.

Swiping loads the 2048px preview; the original is fetched only on deep zoom or an explicit tap.

**Set as album cover** is a star: outlined when the photo is not the cover, **filled and gold
when it is**. Setting a cover from inside a sub-album opens a dialog to choose whether it covers
the sub-album or its parent container.

> The star means *favourite* in Photos. There is no favourites feature here, so nothing
> collides — but if one is ever added, the star is taken and set-as-cover must move.

### Caching and storage

**Browse-to-cache. Nothing is downloaded ahead of time, and nothing is auto-evicted.**
Whatever you view is kept until you clear it. The catalog and all thumbnail DBs (~0.3 GB) are
always kept, so every grid opens instantly and offline.

Settings is **one screen**: storage totals, credentials, sync, and the album list last — each
row with **one button, a clear button, shown only on albums that have something cached**.

> Consequence, stated plainly: an album can only become available offline by browsing it first.
> There is no way to deliberately prepare for a flight.

iOS offers no system-level per-app cache clear (only Offload/Delete App), and a
`Settings.bundle` cannot help — it is a static plist with no buttons, no dynamic rows and no
images — so this screen is the only route.

---

## 7. Ingest CLI

**A shared Swift package** holds everything where a laptop/phone disagreement would corrupt the
catalog: the shard schema, NFC normalisation, the mapping from EXIF tags to catalog rows, the
S3 client and signer, and the LIST-diff sync algorithm.

Encoders stay platform-native behind an `ImageBackend` protocol — **ImageIO/AVFoundation on
iOS and macOS**, **libheif/ffmpeg on Linux**. **`ImageBackend` also extracts raw EXIF tags**,
since both platforms already have a library that reads them and neither would gain from a
hand-written container parser. What the shared package owns is the *interpretation*:
`"2013:07:04 18:22:11"` → epoch, `GPSLatitudeRef 'S'` → a negative latitude, rationals →
degrees, orientation → swapped dimensions. That is where a disagreement would corrupt the
catalog, and it is the same code on both platforms. The protocol itself is declared in the
shared package, because a contract belongs with the other contracts.

### Distribution

**One fully self-contained static binary.** Swift's Static Linux SDK cross-compiles against
musl — `swift build --swift-sdk x86_64-swift-linux-musl` — producing a static ELF with no
dynamic linker, no shared libraries and no Swift installation on the target, for x86-64 and
ARM64. **libheif, x265 and ffmpeg are statically linked in**, not shelled out.

> **x265 is GPLv2.** A statically linked binary inherits GPL terms *if distributed*.
> Irrelevant for personal use; relevant the day it goes on GitHub with release artifacts.

macOS builds need no external tools at all — it reuses the iOS `ImageBackend`.
Windows is unblocked but untargeted.

### S3 layer

**URLSession + swift-crypto + a hand-written AWS4-HMAC-SHA256 signer. No Soto, no swift-nio.**

Soto pulls in swift-nio, which is large to link statically and does not support Windows.
The signing surface here is small — one service (`s3`), one region, static credentials, no STS
or session tokens, path-style URLs — roughly 150 lines. There is no production-ready standalone
SigV4 signer for Swift: `SotoSignerV4` lives in soto-core (NIO), and `aws-crt-swift` is
explicitly developer preview.

**Validate against AWS's published SigV4 test vectors.** A subtle signing bug fails *every*
request, so this is the one place that needs real test coverage.

### Behaviour

- **Idempotency: LIST the bucket and compare against local files.** No local state file, so the
  tool is self-correcting and survives losing laptop state. Costs a full LIST each run. Under
  UUID keys the comparison runs through the shards: `source_path` reconnects a local folder to
  its album, and filenames within it are matched against `photo` rows.
- **Images are asserted never to change on disk**, and the assertion is checked. A file present
  in both places is untouched; local-only is new; a row with no file is deleted. Ingest also
  compares the shard's `bytes` against the directory entry — free, since the scan reads it
  anyway — and **aborts the run on a mismatch** rather than re-ingesting. A changed file means
  the library broke its contract, so nothing is written that run and `OnFailure=` surfaces it.
  There is no mtime column, no hashing pass and no re-ingest path.
- **Parallelism:** one worker per core for encoding. Measured **0.79 s/photo** for decode +
  2048px HEIC + 256px JPEG on a 16-thread laptop, i.e. ~40–60 min for ~34k photos, plus video.
- **Deletion is laptop-only, explicit, dry-run first.** `--prune --dry-run` prints what it would
  remove; `--confirm` acts. The same pass sweeps **unreferenced blobs** — objects whose shard
  write never landed, e.g. a crash mid-upload — since it is already computing the full set of
  referenced ids. One destructive command rather than two. **The app never deletes.** `delete` exists on the shared S3 client
  because the CLI needs it, but no iOS code path calls it — a convention, not a compiler-enforced
  boundary. With no versioning underneath, this is the single irreversible operation in the system.
- **Pull is archive-only.** Phone-uploaded albums are copied into `$LIBRARY_ROOT`; the
  laptop never rewrites a phone-owned album's objects.
- Excludes trash directories and non-photo strays (`.dtrashinfo`, `Thumbs.db`, `.doc`, `.psd`…).

### Unattended sync (systemd user units)

- `OnCalendar=hourly`, `Persistent=true`, `RandomizedDelaySec=5m`. A no-op run is one LIST.
- Pull + push of new content. **`--prune` is never run unattended.**
- No `.path` unit: `systemd.path` is not recursive (it would see a new album folder but not
  photos added inside an existing one) and is edge-triggered when the directory entry appears —
  i.e. *before* a copy finishes — so it would sync half-copied albums.
- Write key via `systemd-creds encrypt --with-key=host` + `LoadCredential=`; never in the unit
  file, the environment, or `ps`.
- Guards: `ConditionACPower=true` (never transcode on battery), `Nice=19`,
  `IOSchedulingClass=idle`, a `CPUQuota=` ceiling, and an `OnFailure=` notification unit.
- `Wants=`/`After=network-online.target`. Linger is already enabled.

---

## 8. Upload from iOS

**Full Photo Library access with a custom album browser over `PHAssetCollection`, not
`PHPickerViewController`.** PHPicker returns individual assets only: it cannot list or name
gallery albums (so no name prefill) and cannot delete (so no post-upload cleanup). Those two
requirements force a real permission grant.

**Flow.**

```
upload icon
  → gallery picker        album, or loose photos with drag-across-to-select
  → name dialog           name prefilled from the gallery album,
                          parent = the album you started from,
                          delete-from-gallery checkbox
  → [tap Upload]
  → pre-sign every PUT URL for this album
  → background upload
```

**No prompt appears during the upload flow.** The password was unlocked with Face ID when the
app launched (§1) and is already in memory; tapping Upload uses it to pre-sign every PUT for
this album, and the background session then runs against those URLs alone.

**The album you are in becomes the parent** — from the root this creates a top-level album, from
inside an album, a sub-album of it.

**The phone runs the full pipeline**: ImageIO plus the hardware HEVC encoder produce thumbs and
2048px previews on-device, and the shared schema code writes the shard.

**Upload order — originals, then previews, then thumbnails, then the shard LAST**, only after
every object is uploaded and verified. An interrupted upload therefore leaves **orphan objects
that no catalog references** — invisible, harmless, swept up by the next `--prune` — rather than
a catalog pointing at objects that do not exist. Cost: the album does not appear on other
devices until it is complete.

**Transfer** uses a background `URLSession` with `allowsCellularAccess = true`, so it survives
the app being backgrounded, the phone locked, and app crashes. Progress is shown live in the
foreground and recomputed on return. Background uploads must come from files on disk
(`uploadTask(with:fromFile:)`), which suits us since derivatives are written out anyway.
Presented as a **bottom sheet, minimizable** to a progress pill.

**Resume.** The app persists an upload manifest, re-creates the background session with the same
identifier on launch, diffs `getAllTasks` against the manifest and re-queues what is missing —
silently, no prompt.

> A background session survives *system* termination: iOS keeps transferring and relaunches the
> app in the background. But a **user force-quit from the app switcher cancels all background
> transfers and iOS will not relaunch the app.** Without manifest reconciliation that upload
> would be stranded permanently.

**Delete-from-gallery is a checkbox in the name dialog**, decided up front. iOS always shows its
own deletion confirmation — an app cannot delete library assets silently — so there are
necessarily two confirmations. Deletion runs only after the uploaded objects are read back and
verified.

> **Accepted risk.** Until the hourly laptop pull runs, the bucket copy is the *only* copy, and
> bunny.net has no versioning or undelete. Ticking that box leaves a single unversioned copy for
> up to an hour. Verification protects against corruption, not against deletion.

---

## 9. Cost

bunny.net storage: **$0.01/GB/month** (single-region HDD), no per-request fees, $1/month minimum.

**Egress is billed only through the CDN — direct reads from the storage API are free.** This is
why §2 skips the pull zone: it is both the simplest path and the free one. The trade-off is no
edge cache, so every re-view is a fresh fetch from the storage region — free, but not instant,
which is why on-device caching (§6) carries the weight it does.

Sizing for a specific library: see `INGEST.md`.

### Throughput characteristics

Measured on a real zone from a domestic connection: **uploads are limited by the client's
upstream link, not by bunny.net, and running more concurrent uploads makes throughput slightly
*worse*.** Downloads scale mildly with concurrency.

Design consequence: **the ingest tool's parallelism is for derivative generation (CPU-bound),
not for uploading.** Overlap encoding with a small number of upload connections; adding upload
workers buys nothing. Measure the actual link before planning a bulk import — see `INGEST.md`.

## 10. Milestones

Three foundations have **no dependency on one another** and can be built in parallel.

**A · Storage layer** *(no deps — do first, riskiest)*
AWS4 signer + S3 client: GET with ranges, PUT, HEAD, LIST v2 with `encoding-type=url`,
multipart. Verified by AWS SigV4 test vectors, then a round-trip against a local S3 server.

**B · Catalog** *(needs A's `S3Client`; buildable against it the day A lands)*
DDL, shard writer/reader, thumbnail packs, merged-DB rebuild, the LIST-diff sync loop, NFC
normalisation, EXIF-tag mapping and hierarchy rules. Verified with synthetic fixtures and a
stubbed transport, no network — plus a **measured full-scale rebuild**: 288 shards, 337
albums, 34,607 rows, inside §4's 1–3 s budget, since that is the one number E inherits and
cannot renegotiate.

**C · Derivative pipeline** *(no deps)*
Thumbs, previews, video transcode, CR2 develop, Live-Photo pairing, junk filtering.
Verified by running over the real library and checking output against previously measured
sizes and counts (see `INGEST.md`) — any large deviation means the pipeline is wrong.

**D · Ingest CLI** = A+B+C — first real data in the bucket.
*Ingest one album end-to-end before the bulk import.*

**E · iOS read-only app** = B — first point the project is useful. Can start on fixtures.

**F · Map** = B — parallel with E.

**G · iOS upload** = A+B.

**H · Laptop pull + systemd** = D.

```
A → B ;  C  →  D  →  (E ∥ F)  and  (G ∥ H)
critical path: A → B → E
```

### Verified against the live zone

A SigV4 client was written and run against the real storage zone. All of the following are
confirmed, which de-risks milestone A considerably:

| check | result |
|---|---|
| SigV4 signing, path-style, region `de` | **works** |
| PUT / GET / HEAD / DELETE | **works** |
| Conditional GET `If-None-Match` | **304 as expected** |
| Range GET | **206, correct bytes** |
| **`If-Match` on PUT** | **HONOURED — 412 on stale ETag** |
| NFC key with umlauts, PUT/GET/LIST | **round-trips correctly** |
| Directory markers in LIST | **present, must be filtered** |
| Header-auth PUT with `UNSIGNED-PAYLOAD` | **accepted** — so file uploads skip the hashing pass |

Verified during milestone A, on Linux with the Static Linux SDK:

| check | result |
|---|---|
| swift-crypto, FoundationXML, FoundationNetworking under musl | **all link statically**, 63 MB stripped |
| static ELF makes real HTTPS requests | **works** — no dynamic linker |
| SigV4 signer vs. AWS vector suite (38 cases, both auth modes) | **green at every stage** |

> **A hazard found while building A.** swift-corelibs-foundation's `XMLParser`
> returns `true` for a *truncated* document, merely setting `parserError`, and
> succeeds outright on an empty one. Since LIST is the whole sync mechanism and a
> missing key means "album deleted", a connection dropped mid-LIST would otherwise
> parse as zero objects and drop the entire catalog. The parser therefore requires
> a `<ListBucketResult>` element that was both opened and closed.

**Egress is billed only when traffic goes through the CDN.** Direct reads from the storage
API are free — confirmed by the account owner. This fully validates §2's decision to skip the
pull zone: it is both the simplest path *and* the free one, so the library can be read back as
often as wanted at no bandwidth cost.

> The remaining trade-off is unchanged: no pull zone means no edge cache, so every re-view is a
> fresh 7.5 MB/s fetch from Frankfurt. Free, but not instant — which is exactly why on-device
> caching (§6) carries the weight it does.

### Remaining unknowns

*None are technical — every storage-layer assumption above is verified against a live zone.*
Library-specific open items (unlocated albums, deferred UI) are tracked in `INGEST.md`.
