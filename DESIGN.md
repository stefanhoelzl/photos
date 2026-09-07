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

```
meta/<album>.db                     per-album metadata shard
thumbs/<album>.db                   per-album 256px thumbnails
originals/<album>/<filename>        the photo/video as uploaded
preview/<album>/<filename>.heic     2048px preview
video/<album>/<filename>.mp4        1080p H.264 transcode
```

`<album>` is the folder path relative to `$LIBRARY_ROOT`, so keys mirror the library and
re-running ingest is obviously idempotent. Nested albums keep their `/` —
e.g. `meta/Trips/Iceland.db`.

**Keys are NFC-normalised raw UTF-8.** Every writer normalises before building a key
(`unicodedata.normalize('NFC', s)` / `precomposedStringWithCanonicalMapping`); percent-encoding
happens only at the HTTP layer. This matters because Linux stores whatever bytes it was given
while iOS emits NFD, and any name with a diacritic (`Grün`, `café`) is a *different key* in
NFC than in NFD.

> Keys may contain Private Use Area characters or codepage mojibake if the source
> library is unclean. Ingest does **not** sanitise: it is the library owner's job to
> repair names on disk, so keys and folder names stay identical (see `INGEST.md`).

**`ListObjectsV2` must pass `encoding-type=url`.** LIST responses are XML, and C1 control
characters are illegal or discouraged in XML. Since LIST is the entire sync mechanism, a single
such key could corrupt the parse. Verified: an NFC key with umlauts round-trips through
PUT/GET/LIST correctly.

**bunny.net creates implicit directory-marker objects, and LIST returns them.** Writing
`meta/Trips/Iceland.db` also produces a zero-byte key `meta/Trips/`. Verified: it
appears in a no-delimiter LIST with `Size 0`, and `HEAD` on it returns 200 with **no
Content-Length and no ETag**. **The sync diff must skip keys ending in `/`** — otherwise
`Trips/` is treated as a shard and the diff breaks. (With `delimiter=/` they arrive as
`CommonPrefixes` instead, but we do not use a delimiter.)

### Album identity and hierarchy

- Album key = folder path. Renaming an album means copying its objects; names must stay unique.
- Albums form a **real hierarchy** with an optional parent.
- **An album has sub-albums XOR photos, never both. Ingest rejects a mixed folder by name.**
  This makes the data model and the album screen each lose a branch.

### Write-conflict model

- **One metadata shard per album, single owner.** `meta/<album>.db` is rewritten wholesale by
  whichever device writes it. Merge is concatenation; delete is a rewrite of that one shard.
- Accepted risk: simultaneous writes to one album from both devices are last-writer-wins.
  Safe in practice because every device holds the full merged metadata and can therefore rewrite
  any shard correctly, and one person with two devices does not write concurrently.
  **`If-Match` on PUT is honoured — verified against the live zone**: a PUT with a stale ETag
  returns **412**, a PUT with the current ETag succeeds. So the single-owner rule is *guarded*,
  not merely unlikely to break: every shard rewrite carries `If-Match: <etag last read>` and a
  412 means "someone else wrote it — re-read and retry".

---

## 3. Catalog

### Per-album shard — `meta/<album>.db`

```sql
PRAGMA page_size = 4096;

CREATE TABLE album_info (
  key   TEXT PRIMARY KEY,
  value                       -- name, source_path, parent, lat, lon, loc_source,
);                            -- cover_photo_id, schema_version, added_at

CREATE TABLE photo (
  id           TEXT PRIMARY KEY,   -- stable, derived from filename
  filename     TEXT NOT NULL,
  ext          TEXT NOT NULL,
  taken_at     INTEGER,            -- epoch seconds; NULL when no EXIF date
  lat          REAL,
  lon          REAL,
  width        INTEGER,
  height       INTEGER,
  bytes        INTEGER,
  media_type   INTEGER NOT NULL,   -- 0 photo · 1 video · 2 live photo
  live_video   TEXT,               -- paired MOV filename, when media_type = 2
  orientation  INTEGER,
  sort_key     INTEGER NOT NULL,
  etag         TEXT
);
CREATE INDEX ix_photo_taken ON photo(taken_at);
```

**Measured sizes** (metadata only): 2 photos → 24 KB · 104 photos → **32 KB** ·
500 → 88 KB · 1,755 → 232 KB · 4,759 → 576 KB. A full first sync is therefore roughly
**one small request per album** — see `INGEST.md` for this library's total.

### Per-album thumbnails — `thumbs/<album>.db`

```sql
CREATE TABLE thumb (
  id    TEXT PRIMARY KEY,          -- = photo.id
  jpeg  BLOB NOT NULL              -- 256px JPEG q75, ~8.7 KB
);
```

At ~9 KB per thumbnail a 100-photo album is ~0.9 MB and a 1,750-photo album ~15 MB, so **one
request opens an album's entire grid, offline**. Shard the thumb DB only if an album exceeds a
few thousand photos.

### Merged local database (on device)

Rebuilt from the shards; never uploaded.

```sql
CREATE TABLE album (
  path          TEXT PRIMARY KEY,  -- 'Trips/Iceland'
  name          TEXT NOT NULL,
  name_folded   TEXT NOT NULL,     -- lowercased, diacritics stripped (ö→o, ü→u, ß→ss)
  parent        TEXT REFERENCES album(path),
  photo_count   INTEGER NOT NULL,
  date_min      INTEGER,
  date_max      INTEGER,
  lat           REAL,
  lon           REAL,
  loc_source    TEXT,              -- 'gps' | 'named' | 'inherited'
  cover_photo_id TEXT,
  shard_etag    TEXT NOT NULL
);
CREATE INDEX ix_album_parent ON album(parent);
CREATE INDEX ix_album_folded ON album(name_folded);

CREATE TABLE photo (
  id, album_path TEXT NOT NULL REFERENCES album(path),
  filename, ext, taken_at, lat, lon, width, height, bytes,
  media_type, live_video, orientation, sort_key, etag,
  PRIMARY KEY (album_path, id)
);
CREATE INDEX ix_photo_album ON photo(album_path, sort_key);
CREATE INDEX ix_photo_taken ON photo(taken_at);
CREATE INDEX ix_photo_geo   ON photo(lat, lon) WHERE lat IS NOT NULL;
```

A prototype with 337 albums and 34,607 photo rows plus these indexes was **4.53 MB**
(2.52 MB gzipped), so even a large library is small enough to hold entirely and query locally.

**Why a merged DB rather than attaching the shards:** SQLite's `SQLITE_MAX_ATTACHED` defaults to
10 and cannot exceed 125, so 288 shards cannot be attached. More fundamentally, ATTACH gives no
shared index — the map and the date filter would scan 288 tables.

### Dates, locations, sorting

- **Dates come from EXIF `DateTimeOriginal` only.** No inference from album names, no file
  mtime. 99.5% of photos have one; the rest are undated and absent from date filters.
- **Album location precedence:** GPS centroid of its tagged photos → its own entry in
  `album-locations.tsv` → **nearest ancestor's location** → none, not on the map.
  Setting a container that *is* a place therefore places all its descendants at once; a
  container that is an *event* is left blank so nothing cascades from it.
- **Sort within an album:** EXIF date, falling back to filename for undated photos.
- **Album list default sort: date, newest first**, ascending/descending toggleable, or by name.
  Albums with no dated photos at all collect at one end.
- **Search:** album names, substring, case- and diacritic-insensitive via `name_folded`.
  No fuzzy matching (too noisy on short names), no filename search.
- **Cover photo:** first photo in sort order, overridable per album. A container's cover is
  resolved by descending into children until a photo is found — *unless* one has been set
  explicitly, so `cover_photo_id` is storable on containers too.

---

## 4. Sync algorithm

Discovery is a **single `ListObjectsV2` on the flat `meta/` prefix**. The response carries the
ETag and size of every shard, so that one request *is* the sync plan. There is no manifest
object and therefore no shared mutable state to conflict on.

```
1. LIST meta/  (encoding-type=url, no delimiter → nested keys come back flat)
   → discard keys ending in '/' (bunny.net directory markers, Size 0, no ETag)
2. diff returned ETags against the local cache
     changed / new ETag  → download that shard
     key absent          → album deleted → drop its rows
3. if anything changed: DROP and rebuild the merged DB by replaying all shards (~1–3 s)
4. store the new ETags
```

The merged DB is **rebuilt wholesale** rather than spliced incrementally. There is no partial
update path, so stale rows are impossible by construction — which is also why there is no
"rebuild index" button in the UI: nothing could ever need repairing by hand.

Shards are kept on disk (~12 MB) as the source of truth for the rebuild.

---

## 5. Derivatives

Two tiers only:

| tier | typical size | where | purpose |
|---|---|---|---|
| **256px q75 JPEG** | ~9 KB | in `thumbs/<album>.db` | the grid |
| **2048px HEIC** | ~370 KB | `preview/…` | fullscreen |

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
catalog: the shard schema, key construction and NFC normalisation, EXIF mapping, the S3 client
and signer, and the LIST-diff sync algorithm. Encoders stay platform-native behind an
`ImageBackend` protocol — **ImageIO/AVFoundation on iOS and macOS**, **libheif/ffmpeg on Linux**.

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
  tool is self-correcting and survives losing laptop state. Costs a full LIST each run, and
  in-place edits (same key, changed content) are not detected without hashing.
- **Parallelism:** one worker per core for encoding. Measured **0.79 s/photo** for decode +
  2048px HEIC + 256px JPEG on a 16-thread laptop, i.e. ~40–60 min for ~34k photos, plus video.
- **Deletion is laptop-only, explicit, dry-run first.** `--prune --dry-run` prints what it would
  remove; `--confirm` acts. **The app never deletes.** `delete` exists on the shared S3 client
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

**B · Catalog** *(no deps)*
DDL, shard writer/reader, merged-DB rebuild, LIST-diff, NFC keys, EXIF mapping, hierarchy and
cascade rules. Verified with synthetic fixtures, no network.

**C · Derivative pipeline** *(no deps)*
Thumbs, previews, video transcode, CR2 develop, Live-Photo pairing, junk filtering.
Verified by running over the real library and checking output against previously measured
sizes and counts (see `INGEST.md`) — any large deviation means the pipeline is wrong.

**D · Ingest CLI** = A+B+C — first real data in the bucket.
*Ingest one album end-to-end before the bulk import.*

**E · iOS read-only app** = B+A — first point the project is useful. Can start on fixtures.

**F · Map** = B — parallel with E.

**G · iOS upload** = A+B.

**H · Laptop pull + systemd** = D.

```
A ∥ B ∥ C  →  D  →  (E ∥ F)  and  (G ∥ H)
critical path: A → D → E
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
| Header-auth PUT with `UNSIGNED-PAYLOAD` | **not yet probed** — the client signs real payloads until it is |

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
