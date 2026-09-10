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
- **Ingest CLI** — Linux, one self-contained binary (`photos-cli`). Reconciles the zone with a
  local library root in one command, and pulls phone-uploaded albums back down.
- **iOS app** — browses the zone; can upload new albums.

Both are Kotlin Multiplatform, over one shared domain (§7). The app's UI also runs as a Linux
desktop harness, which is how it is developed and reviewed (§6).

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

**The laptop is different.** The systemd unit needs unattended write access, and takes it from
the same keyring — which is why a run before the first login defers rather than fails. It runs
`photos-cli` directly, not under `secrets-env`: the development override exists for a terminal,
not for a timer.

**In production both credentials live in the keyring and nowhere else** — two items under one
service, told apart by a `field` attribute, put there and taken away by the tool itself:

```sh
photos-cli login     # prompts for the endpoint and the password, stores both
photos-cli logout    # removes both
```

The keyring is read and written **in-process, over D-Bus** — the `Keyring` port's Linux adapter,
over cinterop'd libdbus-1 — not by exec'ing `secret-tool`. That is what makes the shipped binary literally self-sufficient
(§7), and it is why `login` exists at all: reading in-process while setup still needed
libsecret's tools on `PATH` would have moved the dependency rather than removed it. The items
carry the attributes `secret-tool` wrote, so anything stored before the client existed is
found unchanged.

`logout` removes **both** items, because `service photos-cli` is one concept: removing half
leaves an install that is neither working nor clean. Removing nothing is not an error — the
gesture means *make sure they are gone*, and afterwards they are — and there is no
confirmation step, because §7 refused ceremony where the stakes are photographs and a keyring
item is recoverable in seconds. Guarding the reversible action while `rm -rf` on an album
needs no guard would be exactly backwards.

> **Three verbs, and the rule is intact.** "One verb, and nothing else" (§7) is about the
> *library* being the only way to say anything about photographs — no `delete`, no `--prune`,
> no pending state. `login` and `logout` touch neither the library nor the zone. They are
> credential plumbing, and keeping them out would only mean the binary could run somewhere it
> could not be set up.

The endpoint is not a secret — it is a URL — but it *is* configuration the run cannot do
without, so keeping it beside the password makes a working install one concept rather than a
keyring entry plus an exported variable somebody has to remember. Neither value is then in a
dotfile, in the environment, or inherited by child processes.

**The library root is the working directory**, or `--library-path`. There is no variable for
it, and the default pairs with the marker rule below: `photos-cli sync` typed in the wrong
directory finds no `.photosignore` and refuses, rather than concluding the library is empty
and deleting 292 albums. The convenient default is also the safe one.

**In development `PHOTOS_PASSWORD` and `PHOTOS_ENDPOINT` override the keyring**, because both
are already in Proton Pass and `secrets-env` — which reads `.secrets.yaml` and execs with the
entries injected — is how every other command in this repository reaches them:

```sh
secrets-env photos-cli sync --dry-run
```

Both resolve the same way — flag, then environment, then keyring — so there is one rule to
remember rather than one per credential. The environment wins when it is set, which is what an
override means, and a run that takes that path **names the variables on stderr**: a stale
value silently outranking the keyring is exactly the kind of thing that costs an hour, so it
is not silent. An *empty* variable is treated as unset — `secrets-env` that cannot resolve an
entry leaves the name defined and blank, and a blank secret would otherwise reach the signer
and come back as an opaque 403.

libsecret is still not linked — see §7's dependency note — and the accepted cost on the
production path is unchanged: **an unattended run before the first graphical login finds the
keyring locked**, because gnome-keyring unlocks through PAM at login. That is a deferral
rather than a failure. The run prints one line and exits **75** (`EX_TEMPFAIL`), and the unit
declares `SuccessExitStatus=75` so `OnFailure=` stays quiet; the next hourly run succeeds once
you have logged in. **Locked items are never unlocked**: `Unlock` needs a graphical prompter,
which is exactly what an hourly timer does not have.

The three outcomes are now distinguished by the protocol rather than guessed at. Under
`secret-tool` the only signal was whether the subprocess had written to stderr; over D-Bus,
no bus address, a failed connect, `NoReply` and `ServiceUnknown` are **75**, and a
`SearchItems` that matches nothing is a real error and says so (**3**). A reply the spec does
not allow is also 3 — waiting an hour will not change it.

The session is opened with the `plain` algorithm. The secret crosses a
peer-credential-authenticated `AF_UNIX` socket inside the caller's own `$XDG_RUNTIME_DIR`;
negotiating the encrypted algorithm would put a DH exchange and an AES-CBC decrypt on the
credential path, where a wrong decrypt surfaces as precisely the opaque 403 this section
forbids. The threat `plain` does not stop — a process already running as this uid — can simply
ask the keyring itself.

> **One key here too.** An earlier draft gave the CLI a read-only/read-write split, on the
> grounds that the hourly systemd unit should not hold a key that can delete the library. It
> was dropped: bunny.net issues one Secret Access Key per zone, so the split would have to be
> maintained by hand for a single unattended process. One key, one concept, everywhere.

No encryption beyond TLS. The zone is private (AWS4-signed requests only, no public pull zone).
Accepted: anyone with the read key, and bunny.net itself, can read the photos.

---

## 2. Storage layout

All traffic goes **directly to the S3 endpoint**. No CDN pull zone — bunny.net's pricing page
states **free API egress**, so the direct path is both the simplest and the cheapest.
Consequence: no edge caching, so on-device caching matters.

### Keys

The zone holds exactly two prefixes.

```
meta/<album-uuid>.db         per-album catalog shard
blob/<sha256>                every derivative and thumbnail pack
blob/<uuid>                  transient: an upload the phone cannot hash yet
```

There is no path in any key, no extension on any blob, and nothing in the zone names a photo,
an album or a folder. A shard says what its objects are; the objects say nothing about
themselves.

**Why not paths.** Keys mirroring the library reads well and makes ingest obviously
idempotent, but it welds three unrelated things together: where a file sits on disk, what
identifies it forever, and what has to be copied when either changes. Renaming an album then
means copying every object under it — 1,755 photos for `Neuseeland` — and album names have to
stay globally unique because they *are* the namespace. With opaque keys, a rename or a
re-parent is a metadata write; nothing moves.

**Why blobs are content-addressed, and why that was once impossible.** An earlier draft
rejected hashing on one concrete ground: **on iOS a hash forces reading the whole asset before
the first byte can be uploaded**, which is exactly what §8's background upload exists to avoid.
That ground is gone. Since §5 stopped keeping originals, every blob that survives is written by
the *laptop* — the phone's uploads are pulled down, re-derived and replaced (§7) — and the
laptop has the bytes in hand. **The phone never hashes anything.** It names its transient
uploads with a UUID, and which shape a key has says who wrote it: a hash is content, a UUID is
a placeholder awaiting its re-upload. An `encoded` album references hash keys exclusively.

> **Dedup is not the reason, and the measurement says so.** Across all 34,434 library files:
> 48 distinct contents have more than one copy, worth ~2.5% of the zone — which §9's $1/month
> minimum absorbs whole. The reasons are that a blob's name now *proves* its content, and that
> writes became idempotent.

**What idempotence buys.** A retried upload derives the same bytes, computes the same key, and
skips — instead of minting a fresh UUID and leaving the loser as debris. One `blob/` listing at
the start of a run answers "does the zone already hold this?" for every object, so **a crashed
import resumes** rather than re-uploading what it finished, and **a profile bump sends no
thumbnail packs at all**: thumbnails do not change when the image profile does, both encoders
are byte-deterministic (measured), and identical bytes hash identically. That listing is taken
after the early return described in §7, so a run that changes nothing still costs one request.

**The cost, paid explicitly: a blob can have more than one referent.** "This album stopped
pointing at it" is no longer "nobody wants it", so **nothing deletes blobs eagerly any more**.
Answering that question per album would mean re-reading every shard per album — 292 albums
squared, on a profile bump. §7's sweep answers it once for the whole zone at the end of the
run, and since it no longer waits on an age floor it collects the same objects in the same run.
There is no reference counting: the catalog is small enough to ask directly, once.

**Blobs are immutable, and the key now enforces it rather than promising it.** A blob's content
never changes once written, because a change in content is a change of key. Re-encoding a
derivative mints a *new* id, points the shard at it, and the old object is collected. This is
what makes
§6's cache correct for free: the cache keeps browsed content indefinitely and evicts nothing,
so a key whose content could change would need revalidating on every hit. Instead, a changed
`image_id` in the shard *is* the invalidation signal.

**What UUID keys buy beyond renames.** Every key becomes flat ASCII, which removes three
hazards at once:

- **Blob writes became conflict-free by construction.** §2's write guard is `If-Match` on
  shards; blobs never needed one because each had a single writer. Two devices deriving the
  same photograph now write *identical bytes to the same key*, so the property survives for a
  better reason than it held before.
- bunny.net's implicit **directory markers** can no longer nest. Writing `meta/<uuid>.db`
  still produces the single zero-byte key `meta/`, which a prefix LIST returns with `Size 0`
  and no ETag, so **the diff still skips keys ending in `/`** — but there is exactly one such
  key per prefix now, rather than one per folder level, and none that could be mistaken for a
  shard.
- **NFC normalisation is gone entirely.** It stopped being a wire concern the moment keys
  became UUIDs, and it was measured out of the rest: of 34,729 entries in the library, 51 have
  non-ASCII names and **none is decomposed**. Every normalisation call was a no-op on the only
  data it has ever seen, and preserving it meant a native Unicode library on Linux, a second
  implementation on iOS, and a Unicode-version skew between them — to convert text that is
  already in the target form. Names are stored exactly as given.

  > **The accepted risk, stated plainly.** A decomposed name arriving later — realistically
  > from an import off an HFS+ Mac — would be a *different string* from its composed twin, so
  > the folder would not match its shard and would be uploaded again as a second album. Nothing
  > detects this. The mitigating facts are that no such name exists today, iOS keyboards emit
  > composed text, and the one import path that produces them is a deliberate act rather than
  > something that happens by itself.
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
  state            TEXT NOT NULL,    -- 'uploading' | 'uploaded' | 'encoded'  (§7)
  encoding_version INTEGER NOT NULL, -- 0 = as uploaded; profiles number from 1 (§5)
  added_at       INTEGER NOT NULL,
  schema_version INTEGER NOT NULL,
  CHECK (state IN ('uploading', 'uploaded', 'encoded')),
  CHECK ((state = 'encoded') = (encoding_version > 0))
);

CREATE TABLE photo (
  id              TEXT PRIMARY KEY,  -- row identity; survives re-encoding
  filename        TEXT NOT NULL,     -- with extension; the name *in the zone*
  source_filename TEXT,              -- the name on disk, when it differs. NULL usually
  taken_at      INTEGER,             -- epoch seconds; NULL when no EXIF date
  lat           REAL,
  lon           REAL,
  width         INTEGER,             -- display dimensions, already rotated
  height        INTEGER,
  bytes         INTEGER,           -- the size of the blob a tap fetches, not the file's
  source_bytes  INTEGER,             -- the size of the file on disk; what §7 checks
  original_hash TEXT,                -- SHA-256 of the file on disk; forensic only (§7)
  media_type    INTEGER NOT NULL,    -- 0 photo · 1 video · 2 live photo
  image_id      TEXT,                -- blob: 3200px HEIC, the one image you view
  live_still_id TEXT,                -- blob: untouched source still, Live Photos only (§5)
  live_video_id TEXT,                -- blob: paired MOV, when media_type = 2
  video_id      TEXT                 -- blob: 1080p-ceiling HEVC transcode
);
CREATE INDEX ix_photo_taken ON photo(taken_at);
```

**The row describes what is in the zone, not what is on disk.** `bytes` is the size of the
blob a tap actually fetches, and `filename` carries the extension its bytes really have. For a
video that means the transcode, for every still the 3200px HEIC, and `source_filename` keeps
the camera's own name so ingest can still find the file on disk: `IMG_1234.CR2` beside
`filename = IMG_1234.heic`, `VID_0001.MOV` beside `VID_0001.mp4`.

**`original_hash` is named for which content it means.** Since §2 the blob key is *itself* a
content hash, so an unqualified `content_hash` would say nothing about which content. The two
answer different questions and neither substitutes for the other: the key is the integrity of a
*derivative*, which the laptop can always rebuild from the library; `original_hash` is the
integrity of the *original*, which is the one copy nothing can reconstruct.

**`source_bytes` is what §7's change assertion compares against the directory entry**, and it
is free for the same reason its predecessor was: the scan reads the entry anyway. The
predecessor rode on `bytes`, which now describes a derivative and equals nothing on disk — and
it could only ever cover rows whose blob happened to *be* the file, which excluded video and
carved RAW. Describing the source instead covers every row.

**`image_id` never changes meaning across states.** While an album is `uploading` or
`uploaded` it points at the phone's full-quality upload, with `encoding_version` at 0; once
`encoded` it points at the 3200px HEIC. A reader that knows nothing about `state` still renders
the album correctly — it merely fetches something heavier. That is what keeps the version rule
below a promise rather than a hope.

**`album_id` and `source_path` are permanently stable.** Never removed, never retyped,
whatever a later `schema_version` does. That is what lets any reader learn which folder a
shard claims without understanding the rest of it — so a shard too new to read is still
*identifiable*, and the CLI can leave its directory alone instead of uploading it a second
time. Everything else in the schema is free to change.

**A typed single row, not a key/value bag.** `album_info` holds real columns with real types,
so the schema documents itself and a mistyped field fails when the statement is prepared
rather than reading back NULL. The price is that adding a field is a migration, which
`schema_version` handles.

**Roles live in the schema, not in prefixes.** A photo row states exactly which objects it
owns through four nullable id columns, rather than the reader inferring them from `media_type`
and a naming convention. A still has `image_id`; a Live Photo adds `live_still_id` and
`live_video_id`; a video has `video_id` and an `image_id` poster. There is no `original_id`,
because §5 keeps no originals here at all.

**`photo.id` is its own UUID**, separate from every object id. It is what `cover_photo_id`
points at and what the thumbnail pack keys by, so it has to survive a derivative being
re-encoded — which mints a new `image_id` but leaves the photo the same photo. §5's profile
bump re-encodes the whole library; nothing about a photograph's identity moves when it does.

**No `ext`, no `orientation`, no `sort_key`.** Each was removable once something else carried
its meaning. `filename` holds the extension, so `ext` only duplicated its tail and would have
needed reassembly rules for extensionless names, `IMG.2013.07.jpg` and `.JPG` case.
`width`/`height` are stored **already rotated**, so `orientation` had no consumer: C bakes
orientation into thumbs and viewing images, and iOS applies the EXIF tag itself when decoding an
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
  jpeg  BLOB NOT NULL              -- 256px JPEG q75, ~13 KB
);
```

At ~13.1 KiB per thumbnail a 100-photo album is ~1.3 MiB and a 1,750-photo album ~22.4 MiB, so
**one request opens an album's entire grid, offline** — about 3.0 s for the largest album at
the measured 7.5 MB/s. Shard the pack only if an album exceeds a few
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
  filename, source_filename, taken_at, lat, lon, width, height, bytes,
  source_bytes, original_hash, media_type, image_id, live_still_id, live_video_id, video_id,
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

### Which SQLite

**Nothing here needs a particular one.** The newest SQL feature used anywhere is
`ON CONFLICT … DO UPDATE`, which is SQLite 3.24 (2018), and everything else — partial indexes,
expression indexes, `CHECK`, the PRAGMAs above — is older still. There are no STRICT tables, no
`RETURNING`, no window functions, no JSON functions, no FTS and no R-Tree. iOS 18 ships far
newer than that floor, and so does every desktop distribution. **On iOS the platform's SQLite
is therefore used as-is.**

**The Linux CLI links its own anyway, and the reason is the link, not the SQL.** A distro's
`libsqlite3.so` is compiled against that distro's glibc — Ubuntu 24.04's needs `GLIBC_2.38` —
while the rest of the binary links against a glibc 2.19 sysroot to get §7's floor. Those cannot
meet, so taking the platform's library would raise the shipped binary's floor to whatever the
build host happened to have, making portability a property of the machine rather than of the
build. It is one pinned tarball beside the imaging stack (`Scripts/PROVENANCE.md`), committed
nowhere.

> The two builds can differ freely, because the floor above says they cannot disagree about
> anything this project asks of them.

**That floor is enforced, not merely asserted.** The query layer's SQL dialect is pinned to
SQLite 3.24, so SQL that needs anything newer fails the build rather than failing on a device
that turns out to have an older SQLite than the one it was written against. The claim in the
paragraph above is therefore checked every time the project compiles.

The one place platforms genuinely differ is collation, and the schema already avoids depending
on it: `filename` sorts under BINARY — a UTF-8 byte compare — so the grid order is identical on
both devices without trusting either one's collation tables, and `name_folded` exists only in
the merged DB, which is per-device and never uploaded.

**A shard is read as a file, not as a byte array.** §4's on-device layout already keeps
`shards/<uuid>.db` — it is the source of truth for a rebuild — so opening one is opening a file
the design wanted anyway, and the same holds for thumbnail packs, which §6 keeps permanently.
Only the CLI *writing* a shard for upload needs a scratch file, and it can use its own cache
rather than a temporary directory. This is what makes the platform's SQLite sufficient:
nothing has to serialise a database in or out of memory.

> The cost to watch is §4's 1–3 s rebuild across 288 shards, which §10 treats as fixed. Opening
> files rather than memory images is the one decision here that spends against it.

### Dates, locations, sorting

- **Dates come from EXIF `DateTimeOriginal` only.** No inference from album names, no file
  mtime. 99.5% of photos have one; the rest are undated and absent from date filters.
- **A GPS block whose `GPSStatus` is `V` is ignored.** `V` is EXIF for "measurement void" —
  the camera wrote a position block while having no fix. 1,028 photos here carry one, all with
  latitude byte-identical to longitude. They are rejected on those grounds rather than because
  the numbers happen to be absurd, since a void block holding plausible coordinates would
  otherwise place a photo somewhere it has never been — and an album's pin is the centroid of
  its photos. Like every other reading of a tag, this rule is `ExifMapper`'s and therefore
  shared: §7's point is that the phone and the laptop must not disagree about what a tag means.
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
>
> Which is why the two columns above are stable for ever: a skipped shard is still *probed* for
> `album_id` and `source_path`, so the CLI knows which directory it claims and leaves that
> directory entirely alone — not uploaded, not deleted, not re-minted. A shard whose probe also
> fails is the one genuinely unidentifiable case, and it aborts the run.

Version **2** is current: it added `source_filename` and redefined `bytes` as the size of the
blob rather than of the file on disk.

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
  lock               flock; holds the running sync's pid (§7)
  sync_state.db      album_id → etag, fetched_at
  shards/<uuid>.db   the shards, ~12 MB — source of truth for a rebuild
  merged.db          derived; deletable at any moment
  blobs/             browse-to-cache (§6)
  work/              staging for the run in progress; emptied at both ends
```

**`work/` is here rather than in `$TMPDIR`, and that is not a tidiness choice.** `/tmp` is
tmpfs on an ordinary Linux desktop — 16 GB of RAM on the development machine — so an ingest
staging there holds every video transcode in memory until its upload returns. A run big enough
to matter is a run big enough to be killed for it, and the kill is `SIGKILL`, which skips the
`finally` that removes the directory: the staging then stays resident until the machine reboots.
Both halves of that are fixed by the location. Peak use is bounded — each transcode and each
thumbnail pack is deleted the moment its upload returns, so the high-water mark is `--jobs`
transcodes plus one pack, not the run's total throughput — but bounded gigabytes of RAM is still
the wrong place for them, and gigabytes of disk beside the shards is the right one.

**The run lock is what makes the cleanup rule trivial.** `lock` and `work/` are in the same
directory, so a run holding the lock is the only run that can be using the staging: everything
found there at startup is debris, with no pid to read and no age to guess at. The reclaim happens
before anything else a run does, including the marker guard and including `--dry-run`, and it says
what it removed — the only thing in the journal that reports a previous run was killed. Removing
it *is* what a `finally` cannot do, so both exist: the `finally` for every ordinary ending, the
startup reclaim for `SIGKILL`.

> `O_TMPFILE` would remove even that window — an unlinked inode is freed by the kernel however
> the process dies — and it is rejected because everything downstream of staging wants a *name*:
> the transcode shim takes an output path, a thumbnail pack is a SQLite database opened by path
> (with its own WAL sidecars derived from that name), and uploads read by path. It would mean
> `/proc/self/fd/N` threaded through the C shim, the SQL driver and the uploader, to close a
> window that now costs disk rather than memory.

`sync_state.db` exists so that the merged DB stays purely derived. Putting the ETags inside
`merged.db` would make losing it mean re-fetching all 288 shards; keeping them beside the
shards means `merged.db` can be deleted and rebuilt with no network at all. Deleting
`sync_state.db` on its own forces a full re-fetch, which is a free repair path.

**Reported, not resolved.** A sync returns what it could not make sense of rather than guessing:
albums whose `parent` resolved to nothing (surfaced at top level), shards too new to read
(skipped, and their directory left alone), duplicate names under one parent (both shown), a
directory holding both files and sub-albums, loose files at the library root, a file the
pipeline could not decode, and an ignore rule that matched nothing. Each is a condition the
design deliberately does not auto-correct, and each is named on **every** run until a person
deals with it — which is the only thing that distinguishes a typo from a rule not yet needed.

---

## 5. Derivatives

Two tiers, and neither is an original:

| tier | typical size | where | purpose |
|---|---|---|---|
| **256×256 q75 JPEG** | ~13.1 KiB | packed per album, one blob | the grid |
| **3200px HEIC q45** | ~424 KiB | one blob per photo | everything else |

**The zone is not a backup, and stopped pretending to be one.** The laptop library holds every
original; §7's pull is what keeps that true for photographs that arrive from the phone. Once the
archive lives somewhere else, the largest thing the zone has to store is the largest thing a
screen will ever display — which is a far smaller number than what a camera captures.

Measured against the real library: the median original is **3264px** on the long edge, 69%
exceed 3200px, and those hold 88% of the bytes. A 3200px cap is 2.4× pinch zoom on a 3× iPhone
and a 1.2× upscale on a 4K desktop panel. It takes the still-image tier from **~100 GiB to
~14 GiB**, and §9's first import from **19.5 h to about 3**.

> Rejected: 4096px (10.2 h, native on a 4K panel, but HEIC's advantage over JPEG collapses to
> −6% at that fidelity); 2560px (7.0 h, but only 1.25× the linear pixels of the tier it
> replaced); and keeping originals byte-for-byte, which costs 19.5 h and 3.07 MiB per photo of
> never-evicted device cache to store pixels nothing displays.

**Why one viewing tier and not two.** The old design had a 2048px preview *because* the tier
above it was a 3.3 MB camera original — far too heavy to swipe through, so something had to sit
between the grid and the archive. At 424 KiB that reason is gone: one image serves the swipe and
the deep zoom, the viewer loses its two states, and there is no escalation to get wrong. The
rungs now sit 13 KiB → 424 KiB, which is the order-of-magnitude spacing a two-rung ladder wants;
inserting a preview would have put one rung 2.9× below another.

**Thumbnails are a square centre crop, not a fitted image.** Every consumer in §6 is a square
`object-fit: cover` box — the grid, the 54pt album-list cover, the 38pt map pin and search row,
the 30pt filmstrip, the 26pt set-cover dialog. The only surface that uses `contain` is the
fullscreen viewer, and it reads the viewing image. So an aspect-preserved thumbnail stores pixels
nothing ever displays, *and* starves the one thing that does: fitting a 3:2 photo into 256×256
leaves it 256×171, and a 4-column grid tile on a 3× iPhone is 287 device pixels — a 1.68×
upscale. Filling the box is 1.12×. The crop is not irreversible: originals remain on the laptop,
and re-thumbnailing an album mints a new `thumbs_id` (§2).

> Measured across 16,181 photos in the live zone: **13.1 KiB per thumbnail**, per-album median
> 14.0 KB with p10 10.8 and p90 16.9. An earlier figure of 11.59 KB came from a 120-photo
> sample, which the spread was wide enough to miss.

Rejected: 320px and 512px thumbs (a 512px tier is 3.7× larger, and a 1,645-photo pack would
reach ~77 MiB, breaking §3's one-request-per-album property).

> The 2-column pinch density cannot be served sharply by *any* thumbnail tier — a 580px tile at
> 3× would need ~576px thumbs. It now fetches viewing images for its tiles; that is §6's
> decision, not this one's.

For this library's actual totals see `INGEST.md`.

### Format: JPEG thumbs, HEIC images

At thumbnail size the container overhead swamps any codec advantage, so **JPEG wins outright**
— a PSNR-matched benchmark put 256px HEIC at 279% of JPEG and AVIF at 145%.

At viewing size **HEIC wins, but by less than it looks.** Measured at matched PSNR on identical
lossless references, its advantage decays as fidelity rises:

| matched at | 2560px | 3200px | 4096px |
|---|---|---|---|
| JPEG q85's PSNR | −23% | −24% | −22% |
| JPEG q90's PSNR | −18% | −18% | −6% |
| JPEG q92's PSNR | −12% | −4% | **+1%** |

HEVC intra is built for low bitrate; push it to high fidelity and it stops paying. At 4096px
q92-equivalent it is *larger* than JPEG. The tier sits where the advantage is real, and HEIC is
hardware-decoded on every supported device, which is the argument that also picked it over AVIF.

**Accepted cost: reach.** This is the tier most likely to be opened outside the iOS app, and
HEIC is unsupported in Chrome and Firefox, on Windows without a codec pack, and in §6's Linux
desktop harness without libheif. Taken knowingly, for one imaging path and no new dependency —
x265 is already linked.

### Quality: q45, chosen by eye

**PSNR picked the tier's size; it did not pick its quality.** HEVC intra artifacts are
structured rather than noise-like, and PSNR does not see them the way a person does. The ladder
was judged by eye on 1:1 crops, twice: first on already-delivered 2000px files — faces and hair,
compressed once already by the photographer, which is the hardest case for visible loss — and
then confirmed on camera originals downscaled to 3200px, which is the harder case for fine
texture. Both passed at q45.

```
    q30    62 KiB   16% of source   33.8 dB
    q45   168 KiB   44%             37.6 dB   ←  chosen
    q60   302 KiB   78%             40.4 dB
    q70   397 KiB  103%             41.2 dB
```

Two things that ladder shows. Above roughly q60 the encoder spends bytes reproducing the source
JPEG's own artifacts, and **PSNR plateaus at ~41 dB** because the reference is itself lossy — so
a PSNR-matched choice would have bought quality that does not exist. And q70 is *larger* than
the file it came from.

Over the representative 150-file sample, q45 stores **13.7% of source bytes, 424 KiB per photo**.
If fullscreen ever looks soft, 50 is the conservative step; bumping `ENCODING_VERSION` alongside
it is what makes the library follow (§7).

### Re-encoding, and how a profile change reaches the library

`DerivativeSpec.ENCODING_VERSION` names the profile that produced an album's images, and every
shard records the version it was written at. `sync` re-derives any album below the current one:
mint new blobs, repoint the shard, delete the old objects (§2). Bump the constant and the whole
library drains to the new profile over as many runs as it takes, one album per commit.

That is the entire migration mechanism. There is no separate pass, no second verb, and nothing
to remember to run — which is what makes a quality decision reversible rather than permanent.

### Media handling

- **Live Photos** — the one place an untouched original survives in the zone. `PHLivePhotoView`
  pairs a still to its MOV by Apple's `content.identifier`, which re-encoding strips, so the
  **187 pairs keep their source still** in `live_still_id` alongside the ordinary viewing image.
  That is ~0.5 GiB, and it is cheaper than the alternative: carrying the identifier across a
  re-encode is exactly the maker-note surgery this design set out to avoid, and it would have to
  work identically in two encoders forever.
- **Video** — everything is transcoded to a uniform **HEVC/AAC MP4, faststart, `hvc1`**, with
  **1080p as a ceiling rather than a target**. This covers formats iOS cannot play at all
  (AVI, MPG, 3GP) and tames large camera MP4s. **Originals stay on the laptop.** A video row
  has a `video_id` and an `image_id` poster.
  - **HEVC, not H.264.** 223 of the library's 550 videos are already HEVC, iOS 18
    hardware-decodes it on every supported device, and x265 is linked for the HEIC tier
    regardless — so it is the encoder that was already there, and choosing it drops libx264
    from the build entirely. It is also the only choice that does not *inflate* the 132 files
    already at 1080p.
  - **A ceiling, not a target.** 137 files are 640×480 or smaller; scaling those up would be
    20× the pixels for no added detail. Only the 14 files above 1080p are scaled at all.
  - **Rotation is baked into the pixels** and the display matrix cleared — 118 files carry a
    90° matrix and 15 carry 180°, and a transcode that ignores it plays sideways.
  - Deinterlacing runs only when the decoder reports interlaced frames. The 33 `.mpg` files are
    MPEG-1 and progressive, so it should never fire here.
- **RAW** — every CR2 carries a **full-resolution JPEG at IFD0** — the camera's own rendering,
  ~2.5 MB — so "developing" it is a byte-range extraction, not a demosaic. No LibRaw, no
  development parameters. The carved JPEG is then encoded to the viewing tier like any other
  still, with an EXIF APP1 grafted in from the CR2's own IFDs so it carries date, GPS and
  orientation. The RAW stays on the laptop, and for a RAW with no JPEG sibling the laptop copy
  is that photograph's sole archive.

---

## 6. iOS app

> See **`mockups/placeholder.html`** for all 17 screens rendered at device size — album list,
> container, grid, pinch density, both viewer states, both map representations, set-cover
> dialog, settings, log out, first-run setup, and the five upload steps. They fix layout and
> content, not chrome: they were drawn before this section settled on Compose, so a button in
> them is an iOS button and in the build it is Material 3.

**Minimum iOS 18. Compose Multiplatform**, entered through `ComposeUIViewController` — one UI
in `commonMain` that renders identically on the phone and on a Linux desktop harness. That
second target is not a nicety: it is how the app is developed and driven at all, including by
an agent over HTTP, without Apple hardware in the loop.

Compose draws its own widgets through Skia rather than composing UIKit views, so the platform's
own controls are reached deliberately, through `UIKitView` interop, and only where the platform
is the thing being used:

| component | why it needs interop |
|---|---|
| `PHLivePhotoView` | Live Photo playback is a system view; there is nothing to reimplement |
| PhotoKit picker (§8) | `PHAssetCollection` browsing, and deletion after upload |

Everything else is drawn, including the three hardest screens. `LazyVerticalGrid` exposes
`layoutInfo`, so the grid's prefetch is driven from visible-item state rather than from a
callback that fires once a cell is already on screen — far too late for the 424 KiB fetch the
2-column pinch density needs (§5).
Drag-to-select is a `pointerInput` gesture. The fullscreen viewer's zoom + page +
drag-to-dismiss is an ordinary composition of `HorizontalPager` and `transformable`.

Two consequences are accepted rather than solved. **Pinch-to-density needs a custom animated
layout**, because no interactive layout transition is provided; and it **cannot be exercised on
the desktop harness**, which has no pinch gesture. The grid's behaviour at scale is therefore
the one part of §6 that only a device can confirm.

### Navigation and chrome

**No tab bar.** The album list is the root; everything else is reached from its nav bar.
On first launch the setup screen (§1) is shown instead, and Settings › Account can change the
endpoint, zone and key, or switch zones entirely, at any time.

The nav bar is **two rows** — back button and actions on top, **large title 34pt bold** beneath
(17pt semibold when scrolled). Fixed bar buttons are **circular icon buttons**, with content
scrolling beneath them.

**The design system is Material 3, skinned.** The colour scheme is pinned in full — every
container and outline token supplied — so no component can fall back to Material's stock
baseline palette and introduce a hue the design never chose. The app does not imitate iOS
chrome: a drawn approximation of a system material is worse than a coherent drawn design, and
the whole point of one UI is that it looks the same in the harness as on the device.

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

**The map is drawn, not embedded.** A raster tile layer on a Compose canvas, with pan, zoom and
pins over it — the same code on the phone and in the harness. MapKit would give a better
basemap, but it would only be giving the basemap: clustering is our own algorithm either way,
and §5's pin is a 38pt square thumbnail, which is a custom annotation view in MapKit too. An
embedded native map would also make the one screen that cannot be developed or reviewed without
a device.

**Clustering, the album→pin projection and pin selection live in the shared tier**, not in the
renderer, so the part that can be wrong is the part that is unit-tested.

**Tiles come from the public VersaTiles server.** Three consequences are accepted rather than
mitigated: the map is **the one surface that is not available offline**, where §6 otherwise
promises the catalog and every thumbnail always are; it depends on a third party this project
does not control; and tile requests disclose roughly where the library's photographs were
taken. If any of those bite, the alternative is already available and needs no new mechanism —
a regional `.versatiles` extract stored in the zone as an ordinary blob, read with the range
GETs §2 already relies on, cached like everything else.

**Colour carries meaning; it is never decoration.** Standard bar buttons are **monochrome** —
the glyph takes the on-surface colour. Blue-for-tappable is not used: if every affordance is
tinted, tint says nothing, and the four colours below have to keep meaning something.

| context | glyph | background |
|---|---|---|
| nav bars | on-surface | surface |
| over a photo | white | **dimming scrim** |
| destructive (clear cache) | **error red** | surface |
| active state (photo is the cover) | **gold** | surface |
| progress fill | tinted | — |

### Fullscreen viewer

One screen, two states. Chrome is identical in both — back, then gear/share/set-cover, a
filmstrip, and a bottom line carrying the date. A **LIVE badge appears once**, top-left, and is
the only thing that differs.

Swiping loads the 3200px image, and deep zoom needs nothing further: at 424 KiB one blob serves
both, so the viewer has no escalation step and no second loading state.

> The `Original …` badge is gone. It answered "is this the real file?", and since §5 the answer
> is uniformly no — a badge that always says the same thing carries no information. What the
> laptop holds is a property of the archive, not of the photograph on screen.

**Set as album cover** is a star: outlined when the photo is not the cover, **filled and gold
when it is**. Setting a cover from inside a sub-album opens a dialog to choose whether it covers
the sub-album or its parent container.

> The star means *favourite* in Photos. There is no favourites feature here, so nothing
> collides — but if one is ever added, the star is taken and set-as-cover must move.

### Caching and storage

**Browse-to-cache. Nothing is downloaded ahead of time, and nothing is auto-evicted.**
Whatever you view is kept until you clear it. The catalog and all thumbnail DBs (~0.5 GB) are
always kept, so every grid opens instantly and offline.

> Never evicting is what makes §5's cap a phone decision rather than a bill. At 424 KiB per
> viewing image, browsing a thousand photos keeps 0.42 GB permanently; byte-for-byte originals
> would have kept 3.1 GB for the same browsing, and the tier that used to sit between them
> existed largely to avoid exactly that.

Settings is **one screen**: storage totals, credentials, sync, and the album list last — each
row with **one button, a clear button, shown only on albums that have something cached**.

> Consequence, stated plainly: an album can only become available offline by browsing it first.
> There is no way to deliberately prepare for a flight.

iOS offers no system-level per-app cache clear (only Offload/Delete App), and a
`Settings.bundle` cannot help — it is a static plist with no buttons, no dynamic rows and no
images — so this screen is the only route.

---

## 7. Ingest CLI

**One Kotlin Multiplatform `domain` module** holds everything where a laptop/phone disagreement
would corrupt the catalog: the shard schema, the mapping from EXIF tags to catalog rows, the
S3 client and signer, and the LIST-diff sync algorithm. The CLI and the app
are two entry points onto it, not two implementations of it.

Encoders stay platform-native behind an `ImageBackend` port — **ImageIO/AVFoundation on
iOS and macOS**, **libjpeg-turbo/libheif/x265/ffmpeg/lcms2/libexif on Linux**, behind a small
C shim (libjpeg reports errors by `longjmp`, which no managed runtime can safely be on the far
end of). **`ImageBackend` also extracts raw EXIF tags**,
since both platforms already have a library that reads them and neither would gain from a
hand-written container parser. What the domain owns is the *interpretation*:
`"2013:07:04 18:22:11"` → epoch, `GPSLatitudeRef 'S'` → a negative latitude, rationals →
degrees, orientation → swapped dimensions. Backends also normalise Apple's maker-note content
identifier to `AppleContentIdentifier`, so Live-Photo pairing (§5) is a shared decision rather
than a platform one. That is where a disagreement would corrupt the
catalog, and it is the same code on both platforms. The port itself is declared in the
domain, because a contract belongs with the other contracts.

### Ports and adapters

The domain declares what it needs from the world; adapters supply it; `app/cli` wires them
together by hand in one function. No container — the graph is small enough to read, and a
missing edge should fail at compile time rather than at start.

**A seam becomes a port for one of three reasons, and for no other.** Purity is not a reason:

1. it needs a fake to be testable,
2. its implementation differs by platform, or
3. the domain would otherwise reach for it *statically* rather than receive it.

The third is the one that catches the most. `Date()` and `UUID()` scattered through ingest are
the same defect as a run lock acquired from a static factory: the object is fine, the reach is
not, and it is what makes "this run was refused because another holds the lock" indistinguishable
in a test from "this run could not write to its cache directory" — two outcomes with different
exit codes.

| port | why | Linux adapter |
|---|---|---|
| `ImageBackend` | (1) (2) | the C shim over libjpeg-turbo/libheif/x265/ffmpeg |
| `Keyring` | (1) (2) | libdbus-1, in-process (§1) |
| `Clock` | (1) (3) | system clock |
| `Ids` | (1) (3) | random UUIDs |
| `Paths` | (1) (2) | XDG cache/config directories |
| `RunLock` | (1) (3) | `flock`, unchanged |
| `Reporter` | (1) | console output |

**Deliberately not ports.** The SQL driver is not one — SQLDelight is already that abstraction
and ships in-memory drivers for tests. Neither is HTTP: Ktor abstracts its engines per platform
*and* ships `MockEngine`, so wrapping it would only add a layer that hides mistakes in how Ktor
itself is used — which is the exact class of blind spot that let a truncated `ListBucketResult`
parse as success (§10).

The one HTTP path that *will* need a port is §8's background upload, because Ktor's Darwin
engine cannot use a background `URLSession`. That port arrives with the upload feature, not
before it.

### Distribution

**The requirement is one binary that runs on every major modern desktop distribution** — not,
as an earlier draft had it, a binary with no dynamic dependencies at all. Those are different
things, and the second is unreachable here: Kotlin/Native has no musl target, and `-static` in
its linker options is accepted and then silently ignored.

What delivers the requirement instead is the **glibc floor**. Kotlin/Native links `linuxX64`
against its own bundled crosstool-NG toolchain — gcc 8.3.0, glibc 2.19 — so the imaging stack
is built with *that same toolchain* rather than the host's, and the result names no symbol
newer than **GLIBC_2.17**. That is CentOS 7 vintage: older than any desktop distribution still
in use. **26.7 MiB stripped** — against 80.4 MiB for the statically linked predecessor, which
is what dynamic libc and a smaller runtime buy. x86-64 only: `linuxX64` is the one native
target declared, so no ARM64 binary has been produced and none is claimed.

**libheif, x265, ffmpeg, libcurl, OpenSSL, SQLite and libstdc++ are all statically linked in.** What
remains dynamic is base-system only — libc, libm, libpthread, libdl, librt, libz, libgcc_s.
The link passes `--as-needed`, without which Kotlin/Native records a `DT_NEEDED` for every
library on its default link line whether a symbol is taken from it or not. Four were spurious,
and one of them mattered: glibc moved `crypt` to libxcrypt, so a current Fedora ships
`libcrypt.so.2` and has `libcrypt.so.1` only with `libxcrypt-compat` installed. The binary
linked cleanly, passed every test, and then refused to start — the loader is the only thing
that reads that list, so nothing before it could have caught this. Nothing is shelled out: the keyring is reached in-process through a statically linked libdbus-1 (§1),
which is what closed the last gap. An earlier draft exec'd `secret-tool` for the password, on
the grounds that reaching the keyring in-process meant linking glib. It does not — that is true
of *libsecret*, not of libdbus, which has no glib dependency at all. The client costs
**204 KB** in the shipped binary.

> Building against an old toolchain is what makes the floor low, and it has one recurring cost:
> anything compiled against a *newer* glibc will not link. Before glibc 2.34, `pthread_create`
> and the `sem_*` family live in libpthread rather than libc, so a `.pc` file that omits
> `-lpthread` fails at link time rather than at configure time.

> **x265 is GPLv2.** A statically linked binary inherits GPL terms *if distributed*.
> Irrelevant for personal use; relevant the day it goes on GitHub with release artifacts.

macOS builds need no external tools at all — they reuse the iOS `ImageBackend`.
Windows is unblocked but untargeted.

### Dependencies

**Ktor + SQLDelight + KotlinCrypto + clikt. No glib, no AWS SDK.**

Ktor is both the platform abstraction and the test seam (see *Ports and adapters*): its curl
engine on Linux, its Darwin engine on iOS, `MockEngine` in tests. `HttpRequestRetry` covers
the backoff policy. SQLDelight generates the query layer; it supplies no SQLite of its own, so
which SQLite is linked is a separate decision (§3). clikt gives subcommands, `--help`,
validation and exit codes, which are most of what a CLI is.

**glib stays out, which means no libsecret.** Reaching the desktop keyring through libsecret
would drag meson, libffi, PCRE2, proxy-libintl, libgcrypt and libgpg-error into the build
prefix for about 8 MB — and glib `dlopen`s its GIO modules, which is a stub that always fails
in a statically linked binary. The same reasoning already kept libvips out
(`Scripts/PROVENANCE.md`).

**libdbus-1 comes in, and it is not glib.** The Secret Service is a D-Bus protocol, and
speaking it needs a D-Bus library, not a keyring library. libdbus-1 is the reference
implementation, depends on nothing but libc, links statically, and costs 204 KB in the
shipped binary. Its `configure` requires an XML parser even to build the client library, so
expat comes with it — 377 KB of archive, no dependencies of its own, and nothing in our code
includes it. Both are pinned in `build-native.sh`; dbus at 1.14.10 because 1.16 dropped
autotools for meson.

**There is no keyring library to reach for.** Nothing in Kotlin speaks the Secret Service, on
any target. The JVM options either exec `secret-tool` — which the paragraph above rules out —
or bind libsecret through JNA, which drags glib back in. So the D-Bus client is ours, over
cinterop'd libdbus-1, and the iOS side gets `SecItem*` free through Apple framework interop.
That is the same split as every other port here: one contract, two adapters, neither of them a
dependency.

### S3 layer

**A hand-written AWS4-HMAC-SHA256 signer over Ktor.**

The signing surface here is small — one service (`s3`), one region, static credentials, no STS
or session tokens, path-style URLs — roughly 150 lines, and it is validated against AWS's
published test vectors. An AWS SDK would bring a request/response stack, a credential-provider
chain and a retry policy this design has already decided differently about, to sign one kind of
request against one endpoint.

**Validate against AWS's published SigV4 test vectors.** A subtle signing bug fails *every*
request, so this is the one place that needs real test coverage.

### One verb

**`photos-cli sync`, and nothing else.** The library is the master copy and the only way to say
anything: adding a folder adds an album, deleting a file deletes its photo, `rm -rf` on an
album deletes the album. There is no `delete` command, no `--prune`, no `--confirm` and no
pending state to remember — **`--dry-run` is the one place to look before it happens.**

`login` and `logout` (§1) do not qualify the rule, because the rule is about the *library*:
they say nothing about photographs, touch neither the library nor the zone, and exist only so
the binary can be set up on a machine that has none of libsecret's tools. One verb decides
what the zone contains; the other two decide nothing at all.

> An earlier draft split this into an additive `sync` and a destructive `prune --dry-run
> --confirm`, and then into a `sync` that *restored* anything missing plus a `delete` that
> removed the local folder too. Both were attempts to stop an unattended hourly run treating
> "file not present" as "delete it from the zone" — a real hazard, since an unmounted disk, a
> half-finished copy and a broadened ignore rule all look exactly like that. The guards below
> address it structurally instead, which is what let the second command go. The one thing not
> recovered is ceremony: deletion is irreversible and now happens without a confirmation step.

### Behaviour

- **Idempotency: LIST the bucket and compare against the library.** No local state beyond a
  cache of the shards themselves, so the tool is self-correcting and survives losing laptop
  state. Under UUID keys the comparison runs through the shards: `source_path` reconnects a
  local directory to its album, and filenames within it are matched against `photo` rows —
  against `source_filename` too, so an album still reconciles after a pull has replaced a CR2
  with its carved JPEG.
- **Existence is a `stat`, never the walk.** Whether a row still has a file is answered by
  stat-ing `source_path/filename` directly. `.photosignore` decides what may be *uploaded* and
  nothing else, so broadening a rule can only ever stop an upload — it can never make a
  photograph look deleted. This is the rule the whole deletion model rests on.
- **Images are asserted never to change on disk**, and the assertion is checked. Ingest
  compares the shard's `source_bytes` against the directory entry — free, since the scan reads
  it anyway — and **aborts the run on a mismatch** rather than re-ingesting. A changed file
  means the library broke its contract, so nothing is written that run and `OnFailure=`
  surfaces it. There is no mtime column and no re-ingest path. Because `source_bytes` describes
  the *file* rather than the upload, the check now covers every row, including the video and
  carved-RAW rows its predecessor had to exempt.

  `original_hash` is recorded beside it, at ingest — the file is read once more to digest it,
  which is a real cost on first import and buys the one integrity record nothing else can
  reconstruct. It is **never verified on a schedule**: a full pass is ~100 GiB of reads against
  a timer that fires hourly. A forensic record for a file already suspected of having changed,
  not a monitor.
- **Parallelism:** one worker per core for encoding. Measured during milestone C on real
  18 MP photos, when the tier was 2048px: **2.7 s/photo serial**, **0.43 s/photo** wall with
  16 workers — so roughly **4 h for ~34k photos**, plus video. The HEIC encode was ~1.5 s of
  the 2.7 s and does not come down without trading image quality (x265 `superfast` saves 13%;
  `ultrafast` is 3.3× faster and 8% larger).

  > **§5's cap inverts the conclusion this figure used to support, and the figure itself is
  > stale.** A 3200px HEIC is 2.4× the pixels of a 2048px one; measured single-threaded,
  > libheif takes 0.80 s at 2048px against 1.59 s at 3200px. Scaling the serial number by that
  > ratio puts encoding somewhere near **6 h**, against an upload that §5 brought down to
  > **~3 h**. So encoding is no longer overlapped by a much longer upload — it is now the
  > longer half, and the first import is bounded by CPU rather than by the link.
  >
  > That is an inference from a component measurement, not an end-to-end one. The number to
  > trust is a re-measured wall time at the shipped profile, and nothing here has produced one
  > yet. It changes no decision in this design — the import is a one-off, and §9's cost
  > argument never rested on it — but it does mean the "encoding is nowhere near the
  > bottleneck" claim this note used to make is no longer true.
- **Deletion is what the library says it is.** A row whose file is gone is dropped and the
  album's thumbnail pack repacked, in the same run; the blobs it owned are collected by the
  sweep at the end of that same run, because §2 no longer lets an album delete a blob on its
  own authority. A **directory that is gone**
  deletes the album — shard first, so the album stops existing before its objects do and the
  catalog never names a blob that is not there. A directory that still exists but has lost every
  file is not a special case at all: every row drops and the album survives with **zero photos**,
  because `rm album/*` is not how an album is deleted.
- **The one structural guard: `$LIBRARY_ROOT/.photosignore` must exist and be readable**, or the
  run aborts before writing anything. The file doubles as the marker saying *this directory is a
  library root*, which is what makes unattended deletion defensible: an unmounted disk is a bare
  mount point with no marker, a mistyped root (`~/Pictures` for `~/Pictures/Albums`) has no
  marker, and a missing root has none either. One rule covers all three, with no notion of "too
  many deletions" to tune.
- **The orphan sweep runs inside `sync`**, on the same pass, and **there is no age floor on
  garbage.** §8 names every blob an upload will write *before* it writes any of them, so an
  unreferenced blob cannot belong to something in flight — it is garbage the moment it is
  unreferenced, however new. The sweep stands down entirely if any shard is too new to read,
  since the referenced set would then be missing whatever that album owns.

  It reuses the `blob/` listing §2 takes at the start of the run rather than taking a second
  one. That is not only thrift: a fresh listing could lag behind a shard this very run
  committed and read its blobs as unreferenced, where the in-memory set cannot.

  **The seven-day floor survives for one job**: deciding when an album still `uploading` has
  been abandoned. Presigned URLs live at most 7 days (§1) and §8's background uploads run
  against them, so past the floor the upload provably cannot finish, and the album is deleted
  shard-first like any other. Garbage and liveness stop sharing a knob.
- **The app never deletes.** `delete` exists on the shared S3 client because the CLI needs it,
  but no iOS code path calls it — a convention, not a compiler-enforced boundary. With no
  versioning underneath, deletion is the single irreversible operation in the system.
- **Pull is folded into `sync`, and it is what makes the laptop the archive.** An album at
  `uploaded` is one the phone finished and nothing has encoded. `sync` **claims it first** —
  one `If-Match` write setting `source_path`, leaving the state alone — then downloads it into
  `$LIBRARY_ROOT`, derives it, and writes `encoded` at the current profile, which deletes the
  phone's full-quality blobs.

  The order is the point. Claiming first records where the album will land *before any file
  exists*, so a run interrupted mid-download resumes into the same directory instead of
  choosing a fresh name beside it and fetching everything a second time. That is only safe
  because **the deletion rule is gated on `encoded`**: a directory holding half its files would
  otherwise read as photos someone deleted. And the full-quality blobs — the only copy until
  the library copy is on disk — are dropped last, in the same commit that replaces them.

  An album at `uploading` is skipped entirely: it is still in flight, and its shard is what
  keeps its blobs safe from the sweep.

  A pulled Live Photo's MOV has no name of its own in the catalog and is written as
  `<still-stem>.MOV`, the convention all 187 pairs already follow; pairing is by content
  identifier, so the walker re-pairs it either way. Pulls write unconditionally — the ignore
  rules govern what goes up.
- **A laptop-owned album is never restored.** `sync` reads the library and writes the zone; it
  does not put files back. Deleting a folder is a deletion, not a divergence to repair — and
  since ownership is now a column rather than an inference from `source_path`, there is no
  state in which a deletion could be mistaken for an album that was never pulled.

- **Re-encoding is an ordinary sync outcome.** An `encoded` album whose `encoding_version` is
  below §5's current profile has every file re-derived and every old row dropped, which deletes
  the blobs it owned through the path that already deletes blobs. Bump the constant and the
  library drains to the new profile over as many runs as it takes, one album per commit. No
  separate pass, no second verb, nothing to remember to run.

  **`photo.id` is carried across the re-derive**, matched by the file each row came from.
  Without that the drop-and-re-upload shape would mint fresh identities, and §3's rule that a
  re-encoded photograph is the same photograph would be false — `cover_photo_id` would resolve
  to nothing and every custom cover in the library would clear on the first profile bump.

  > At `ENCODING_VERSION = 1` this path is dormant for laptop-owned albums: the schema's second
  > CHECK forbids an `encoded` album at version 0, so no legal shard can sit below the current
  > profile yet. What exercises the same code today is the pull, which re-derives a phone album
  > at version 0 on its way to `encoded`. The first bump to 2 is what wakes it for everything
  > else.
- **One sync at a time**, enforced by an `flock` on a file in the cache directory. The first
  import is several hours and the timer fires hourly, so without it the two overlap repeatedly:
  both derive and upload the same files, and the loser's blobs sit in the zone with nothing
  pointing at them until the sweep collects them a week later. The margin narrowed when §5 cut
  the upload, but it did not close — one run still outlasts the interval by hours. A second run
  exits **75** rather than failing — the sync is happening, just not that one. The lock is
  advisory and process-scoped, so the kernel releases it however the run ends, including
  `kill -9`; there is no stale lock file to explain to anyone.
- **The counter measures its own ratio rather than carrying a constant.** How much a
  photograph shrinks when derived is a property of §5's profile *and* of this library, and it
  is not knowable before deriving — so the plan line says what will be **read**, which is real
  work and knowable, and the counter projects what will be **sent** from the ratio it observes
  as it goes. It shows no total until it has watched enough items that one panorama cannot set
  the projection, and says `~` when it does. A hard-coded ratio would have been one more number
  to keep true the next time the profile moved.

- **A run says what it intends before it does it**, then a line per album as each commits, so
  a 39-hour job is legible in the journal while it is still going rather than at the end. On a
  terminal a redrawing counter on stderr adds files, bytes, rate and an estimate; the journal
  never sees it.
- **What to exclude comes from the library, not the tool: `$LIBRARY_ROOT/.photosignore`.**
  The CLI carries no built-in exclusions — no extension list, no filename list, not even a
  dot-file rule. The single hardcoded rule is that `.photosignore` excludes itself. What
  counts as junk is a fact about a particular library, and baking one library's photo-manager
  artefacts into a tool this document presents as reusable is the mixing of concerns
  `INGEST.md` exists to prevent.
  - **Syntax**: `fnmatch` globs (`*`, `?`, `[abc]`); a pattern with no `/` matches a file's
    name at any depth, one containing `/` is anchored to the root; a trailing `/` means a
    directory, which is not descended into; `#` comments. No negation and no `**`, so order
    never matters — a partial gitignore that *looks* like gitignore is worse than one that
    plainly is not. Matching ignores case and Unicode composition (§2's hazard).
  - **Missing or unreadable aborts the run**, because the file is also the marker that says
    this directory is the library (see the guard above). A library that genuinely wants no
    exclusions writes an empty one — which is a sentence of explanation in exchange for
    making an unmounted disk structurally unable to look like a library whose every album was
    deleted.
  - Applied by the walker and nowhere else, and it governs **uploads only**. Whether a row
    still has a file is a separate question answered by `stat`, so an ignored file and an
    absent one are *not* the same thing: broadening a rule stops photos going up, and can
    never mark uploaded ones for removal.
  - The run reports how many files each rule excluded, and names any rule that matched
    nothing — the only way a typo is distinguishable from a rule not yet needed.
  - `photosignore.example` in the repository is a commented starting point, and — since the
    file is now required — the thing to copy in before the first run.

### Unattended sync (systemd user units)

- `OnCalendar=hourly`, `Persistent=true`, `RandomizedDelaySec=5m`. A no-op run is one LIST —
  which is exactly what the ETag cache beside the shards buys.
- `SuccessExitStatus=75`, which covers all three deferrals: a keyring still locked before the
  first login, an hourly firing that lands while a long run still holds the lock, and a zone
  that cannot be reached at all — no DNS, no route, a refused connection.

  > That third one is why the transport has to raise a failure this project owns. Ktor's curl
  > engine throws a plain `IllegalStateException`, which is outside §1's hierarchy, so an
  > offline run used to leave the exit-code contract entirely and die on Kotlin/Native's
  > uncaught handler — `SIGABRT`, a core dump, sixteen frames of stack. On this timer that is a
  > page every hour for a laptop that is merely asleep or travelling. The same type mismatch
  > silently disabled the retry that was written to cover DNS and TLS: it tested for
  > `IOException`, which curl never raises.
- The full reconciliation, deletions included. There is no second command to withhold: the
  marker guard is what makes that safe.
- No `.path` unit: `systemd.path` is not recursive (it would see a new album folder but not
  photos added inside an existing one) and is edge-triggered when the directory entry appears —
  i.e. *before* a copy finishes — so it would sync half-copied albums.
- The key comes from the keyring, never from the unit file, the environment, or `ps` — and
  now literally so: nothing is exec'd, so it is never an argument to a child process either.
- Guards: `ConditionACPower=true` (never transcode on battery), `Nice=19`,
  `IOSchedulingClass=idle`, a `CPUQuota=` ceiling, and an `OnFailure=` notification unit.
- `PrivateTmp=yes` as ordinary hardening, and for no other reason: derivatives stage in `work/`
  under the cache directory (§4), not in `/tmp`, so there is no `TMPDIR=` to set here and nothing
  about the unit's temporary directory that a long import depends on.
- A run stopped with `SIGTERM` unwinds rather than being killed: it empties its staging, releases
  the lock, and then dies *by the signal*, so what `systemd` records is a terminated process
  rather than one of §7's exit codes. Whether `SuccessExitStatus=` should therefore name `SIGTERM`
  is a question for the units themselves — an operator stopping a sync is not a failure, but an
  out-of-memory kill should still reach `OnFailure=`.
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

**The phone uploads full quality, and derives only thumbnails.** ImageIO produces the packed
thumbs on-device — 0.42% extra upload bytes — so the album is browsable in the grid the moment
it lands, without waiting up to an hour for the laptop. It does not produce viewing images: the
laptop makes those when it pulls, which keeps one encoder in the system and means a profile
version can never mean two different things.

> The full-quality upload is not an archive decision reversed. It is what §7's pull turns *into*
> the archive: the laptop takes it down, keeps it, and replaces it in the zone with a 3200px
> image. Until that happens the phone's upload is the only copy, which is why nothing deletes it
> before the library copy is on disk.

**Upload order — the shard FIRST, at state `uploading`, naming every object the upload will
write; then the objects; then the shard again at `uploaded`.** This is the opposite of what a
catalog usually wants: the zone briefly holds a shard pointing at objects that do not exist yet.
It is deliberate, and naming the *whole* set upfront rather than growing it is what makes it
worth doing.

The alternative — shard last — leaves blobs that no catalog references, indistinguishable from
debris, and that indistinguishability is the entire reason a sweep would need an age floor. A
shard at `uploading` names its blobs before any of them exist, so the referenced set is
authoritative at every instant: mid-upload, and while the laptop syncs concurrently. §7's sweep
therefore skips them on evidence rather than on age, and collects everything else at once.

Cost: the album does not appear complete on other devices until the second write, and an
`uploading` shard is skipped by every reader. The phone must also know its manifest before it
starts — which it does, because it derives the thumbnails first and names each upload with a
UUID rather than a hash (§2). **The phone never hashes anything**; that is precisely what makes
content addressing possible here at all.

> An album left at `uploading` past §7's seven-day floor is an upload that was abandoned. The
> presigned PUTs it was uploading through have expired by then, so it provably cannot still
> finish, and §7 deletes it shard-first like any other album.

**Transfer** uses a background `URLSession` with `allowsCellularAccess = true`, so it survives
the app being backgrounded, the phone locked, and app crashes. Progress is shown live in the
foreground and recomputed on return. Background uploads must come from files on disk
(`uploadTask(with:fromFile:)`), which suits us since derivatives are written out anyway.
Presented as a **bottom sheet, minimizable** to a progress pill.

This is the one HTTP path that does not go through Ktor, whose Darwin engine cannot drive a
background session — so it is a **`BackgroundUploader` port**, with a Kotlin/Native adapter
over `URLSession` on iOS and a plain foreground implementation everywhere else. The manifest
below is the port's own state, which is what makes reconciliation testable without a device.

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

> **Units, since this section is where they bite.** bunny's GB is decimal — 10⁹ bytes — and so
> is everything the CLI prints, precisely so a run reporting 19.2 GB uploaded and an invoice
> computed on 19.2 GB are the same number. Per-item measurements elsewhere in this document are
> written `KiB`/`MiB` because that is how they were taken; 424 KiB is 434 KB. The rule is that
> a unit here means what it says, rather than every figure sharing one base.

**The minimum is what actually bills.** This library at §5's tiers is ~17.9 GiB — thumbs 0.5,
viewing images 13.8, video 3.1, Live-Photo stills 0.5 — which is $0.19 of a $1.00 invoice.
Byte-for-byte originals would have been ~115 GiB and $1.23. So the whole storage dimension of
§5's redesign is worth **$0.23/month**, and every decision in it was made on other grounds:
upload hours, and the never-evicting device cache of §6.

> Stated plainly because it is easy to get backwards: the tier sizes in §5 matter, but not for
> what they cost to store. 424 KiB per photo instead of 3.07 MiB is 3.0 h of first import instead
> of 19.5, and 0.42 MB of permanent phone cache per photo viewed instead of 3.07.

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
DDL, shard writer/reader, thumbnail packs, merged-DB rebuild, the LIST-diff sync loop,
EXIF-tag mapping and hierarchy rules. Verified with synthetic fixtures and
`MockEngine`, no network — plus a **measured full-scale rebuild**: 288 shards, 337 albums,
34,607 rows, inside §4's 1–3 s budget, since that is the one number E inherits and cannot
renegotiate.

**C · Derivative pipeline** *(needs the domain's EXIF and derivative contracts, extracted from B)*
Thumbs, viewing images, video transcode, CR2 extraction, Live-Photo pairing, `.photosignore`
filtering, and the Linux `ImageBackend` adapter. Verified by unit tests over synthesised
images — every operation, every orientation, the CR2 carve, the colour conversions and the
Live-Photo pairing rule — and by `:tests:cli`, which drives the whole native stack through the
configuration that actually ships, by running the shipped binary over a synthetic library.

> **What that no longer covers, stated plainly.** An earlier draft added a dev-only harness
> that ran the pipeline over the real library and compared per-tier counts and sizes against
> the figures in `INGEST.md`. It is not part of this design. So the aggregate properties those
> figures describe — a thumbnail averaging ~13.1 KiB, a viewing image ~424 KiB across 34,607
> photos —
> are **not checked by anything automated**. A change that leaves every unit test green while
> shifting the output distribution, a quality constant or a resize path, would not be caught
> here; it would surface as an unexpected bill, or not at all. The figures in `INGEST.md`
> remain the reference, and comparing against them is a manual act.

> C is not dependency-free. `ImageBackend`, `ExifTags`, `MediaType` and `PhotoRow` are contracts
> both the catalog and the pipeline own, so they sit in the domain below both.
>
> Traversal is separate again — `$LIBRARY_ROOT` as something you walk, with its own
> `.photosignore`. It sits below D rather than inside the pipeline because the phone uploads
> from `PHAssetCollection` and has no library tree to walk.

**D · Ingest CLI** = A+B+C — first real data in the bucket.
The domain holds every rule that decides what the zone should contain — folder→album matching,
the per-album diff, container synthesis, the mixed-folder and too-new-shard rules, the pull,
the sweep — and `photos-cli` is argument parsing and wiring over it. That split is not
tidiness: this is the code that can lose photographs, and it has to be reachable from a test
with a temporary directory and nothing else.
*Ingest one album end-to-end before the bulk import.*

**E · iOS read-only app** = B — first point the project is useful. Can start on fixtures, and
runs on the Linux desktop harness (§6) long before it runs on a phone.
It must render an album with **zero photos**: emptying a directory leaves one (§7).

**F · Map** = B — parallel with E.

**G · iOS upload** = A+B.

**H · systemd units** = D. Pull is not part of it: `sync` already pulls and claims
phone-owned albums, so H is the units and nothing more.

```
A → B → C  →  D  →  (E ∥ F)  and  (G ∥ H)
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
| Header-auth PUT with `UNSIGNED-PAYLOAD` | **accepted** — so file uploads skip the signer's hashing pass |

> That last row is worth revisiting since §2. `UNSIGNED-PAYLOAD` exists to avoid hashing a file
> just to sign the request — but ingest now hashes every object anyway, to name it. For a simple
> PUT the signer's `x-amz-content-sha256` *is* the object's SHA-256, so the value is already in
> hand and the two passes could become one signed one. Not done: it trades a verified-working
> path for a saving that is already paid.

Verified on Linux, building the shipped configuration:

| check | result |
|---|---|
| SigV4 signer vs. AWS vector suite (38 cases, both auth modes) | **green at every stage** |
| the C shim bound from Kotlin/Native by cinterop | **no glue layer** — decode, resize, colour-convert, JPEG, HEIC and MP4 all drive from Kotlin |
| libheif, x265, libde265, ffmpeg built against the gcc 8.3 / glibc 2.19 toolchain | **all build**, C++ included |
| Ktor over statically linked libcurl + OpenSSL | **real HTTPS request, 200 with body** — DNS and TLS both work |
| SQLDelight over the platform SQLite | **binds cleanly**, no duplicate symbols |
| shipped `photos-cli` | **26.7 MiB stripped, floor GLIBC_2.17**, base-system libraries only |

> **A hazard worth keeping in mind.** An XML parser that accepts a *truncated* document is a
> catalog-destroying bug here, not a cosmetic one: LIST is the whole sync mechanism, and a
> missing key means "album deleted", so a connection dropped mid-LIST would parse as zero
> objects and drop everything. The parser must require a `<ListBucketResult>` that was both
> opened and closed — and the tests must exercise the real HTTP client rather than a stub, or
> this class of failure never surfaces.

**Egress is billed only when traffic goes through the CDN.** Direct reads from the storage
API are free — confirmed by the account owner. This fully validates §2's decision to skip the
pull zone: it is both the simplest path *and* the free one, so the library can be read back as
often as wanted at no bandwidth cost.

> The remaining trade-off is unchanged: no pull zone means no edge cache, so every re-view is a
> fresh 7.5 MB/s fetch from Frankfurt. Free, but not instant — which is exactly why on-device
> caching (§6) carries the weight it does.

### Remaining unknowns

Every storage-layer assumption above is verified against a live zone, and the CLI's whole
dependency stack is verified on Linux. Two things need Apple hardware:

- **whether a Compose lazy grid sustains §6's prefetch at scale on a device.** The tile is a
  13 KB thumbnail from the packed blob, which is the cheap case; what the harness cannot answer
  is the 2-column pinch density, where §5 now has the grid fetching **424 KiB viewing images**
  per tile at scroll speed.
- **whether the platform SQLite on iOS behaves as §3 assumes.** The SQL floor is 2018, so this
  is expected rather than doubted, but it is untested.

And two that do not, both left by §5's redesign:

- **the first import's wall time at the shipped profile.** §7 infers ~6 h of encoding against
  ~3 h of upload from a component measurement; no end-to-end run has confirmed it. The
  inference changes no decision, but it does mean the import is now CPU-bound rather than
  link-bound, which is the opposite of what this design assumed throughout.
- **why viewing-image size tracks source megapixels as strongly as it does.** Measured across
  16,181 photos in the live zone at the *old* tier: 0-1 MP → 68 KiB, 5-6 → 277, 8-9 → 438,
  12-20 → 408. A fixed-long-edge tier should vary with content rather than with source
  resolution, and the 8-9 MP bucket exceeding the 12-20 MP one is backwards either way. The
  same resize and encode path produces the current tier, so if it is a defect it is still
  there.

Library-specific open items (unlocated albums, deferred UI) are tracked in `INGEST.md`.
