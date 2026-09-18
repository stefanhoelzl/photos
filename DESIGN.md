# Photo album viewer over S3-compatible storage

A personal iOS app that browses photo albums held in a bunny.net storage zone, plus a Linux
CLI that populates and maintains it. No backend service.

Derived from a design interview; every decision below was made explicitly, and the platform
behaviour is verified against a live storage zone rather than assumed.

Specifics of the library being ingested — inventory, derivative sizing, geocoding data, the
first-run plan — live in **`INGEST.md`**, which is not committed.

**Mockups** are the visual companion to §6 and §8 — 26 screens, self-contained, open directly
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
- **iOS app** — browses the zone; can upload new albums, and add photos to existing ones.

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

**Key handling.** The password and the storage URL live in the Keychain as
`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`: readable whenever the phone is unlocked, never
in a backup or iCloud Keychain, and **behind no prompt**. The password is read once per app
launch, held in memory for that session, and wiped on termination. Background uploads are
unaffected: they run against **pre-signed URLs** (bunny.net presigned URLs are valid 1 s–7 days)
generated at upload time, so a force-quit and relaunch resumes without needing the key again.

> **Why no biometric gate.** An earlier draft put the password under
> `kSecAccessControlBiometryCurrentSet`, so the library was unreadable without a face or finger.
> On a real phone that gate refuses to *store* anything unless a passcode is set and a
> fingerprint or face is enrolled — `errSecAuthFailed` (-25293), measured on an SE2 — so a phone
> without one could not be set up at all, and every launch cost a prompt. It was dropped. The
> accepted cost is stated plainly: **anyone holding the unlocked phone can browse the whole
> library.** The phone's own lock is the protection, as it is for the Photos app beside it.

> **Why one key and not a read-only/write pair.** The two-key split limited the blast radius of
> an extracted key — read-only could not destroy anything. It was dropped when the one key was
> biometric-gated, and without that gate the argument for one key is narrower: the laptop holds
> the master copy and the app **never issues a DELETE** (§7), so an unlocked phone can add to
> the library but not destroy it. One key removes a concept, a Settings row and a
> password-manager entry.

> Every Keychain call also needs the app to carry an `application-identifier`, which it gets
> from being signed with an entitlements file. Unsigned, all of them fail with -34018 while the
> rest of the app runs perfectly, which is a failure worth recognising by name.

**Settings › Account is read-only.** It shows the storage URL and a masked password, and offers
**Log out**. Nothing there is editable and nothing carries a disclosure arrow.

**Three ways in, and they agree.** The phone has one: the setup screen, or what it wrote to the
Keychain last time. The desktop harness has three, resolved in the CLI's order — the
environment (`secrets-env`), then the keyring, then the screen — so a laptop that has run
`photos-cli login` is already set up and a laptop that has not is asked once. The decision lives
in `Launcher`, above the model, because none of the app can be built until there is a zone to
talk to: the S3 client, the sync loop and the queue all take the credential at construction.
That is also what makes logging out simple — the session is discarded whole rather than asked
to forget things one at a time.

**Credentials are entered exactly once, at setup.** Changing either the URL or the password
means logging out and setting up again. There is therefore no edit sheet, no in-place
replacement, and no second path to a configured state — the app is either set up or it is not.
The password row shows a masked value, never a storage location.

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

The keyring is read and written **in-process, over D-Bus**, not by exec'ing `secret-tool`. That is what makes the shipped binary literally self-sufficient
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

**Two transports, one protocol.** The shipped CLI reaches the bus through cinterop'd libdbus-1;
the desktop app is a JVM and cannot reach a cinterop binding at all, so it reaches the same bus
through dbus-java. What sits *above* them is one piece of code, because the part that can be
wrong is not the marshalling — it is deciding which reply means a value, which means *absent*,
and which means *not now*, and those are what §7's exit codes turn on. So the transport is a
five-method port (`OpenSession`, `SearchItems`, `GetSecret`, `CreateItem`, `Delete`) and the
judgement lives once above it.

> Drawing the seam at the five operations rather than at "send a method call" is what keeps
> D-Bus's type system — variants, dict entries, object paths, the `(oayays)` secret struct — out
> of the boundary. Each transport marshals however its own library prefers, and neither gets an
> opinion about locked collections.

Both talk to the same items under `service photos-cli`, so a laptop where `photos-cli login` has
been run is a laptop where the desktop app already works.

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

The zone holds exactly three prefixes.

```
meta/<album-uuid>.db         per-album catalog shard
addition/<uuid>.db           transient: photos the phone uploaded into an album, new or existing (§8)
blob/<sha256>                every derivative and thumbnail pack
blob/<uuid>                  transient: an upload the phone cannot hash yet
```

**`addition/` is its own prefix rather than a folder under `meta/`**, because keys never nest
(below) and because what a shard *is* should be readable from the LIST. It costs a second LIST
per sync (§4). A build that predates it never lists the prefix — which is harmless for the app,
and **not** for the CLI: an older CLI's sweep would find an addition's blobs referenced by no
shard it knows and delete them. **The CLI is therefore updated before the app starts adding
photos**; that ordering is the whole of the compatibility story, since the phone has no way to
know what the laptop runs.

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
after the early return described in §7, so a run that changes nothing still costs only its two
shard LISTs.

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
- **Album names are unique among siblings, compared ignoring case** — albums and containers
  alike, as folders on a disk that may not tell case apart already are. That is what lets the
  phone name an album by its path (§8). Nothing merges two that clash: sibling folders named
  alike but for case are left alone, with everything beneath them, and named in the sync report
  until one is renamed; a new phone album whose name was taken after it was chosen waits in the
  zone, named the same way (§7). Merging is a destructive guess about intent, and this system
  does not resolve conflicts. A catalog that still holds two shards of one name under one parent
  shows both and reports them.

### Write-conflict model

- **One shard per album, single owner.** `meta/<album-uuid>.db` is rewritten wholesale by
  whichever device writes it. Merge is concatenation; delete is a rewrite of that one shard.
- **The phone never rewrites an album to add photos to it.** It writes an *addition* of its
  own (§8), and only the laptop folds that into the album's shard (§7). So the phone and the laptop
  never race on an album the laptop owns, and an addition has a single writer until it is merged.
- Accepted risk: simultaneous writes to one album from both devices are last-writer-wins.
  Safe in practice because every device holds the full merged metadata and can therefore
  rewrite any shard correctly, and one person with two devices does not write concurrently.
  **`If-Match` on PUT is honoured — verified against the live zone**: a PUT with a stale ETag
  returns **412**, a PUT with the current ETag succeeds. So the single-owner rule is *guarded*,
  not merely unlikely to break: every shard rewrite carries `If-Match: <etag last read>` and a
  412 means "someone else wrote it — re-read and retry".
- The guard covers *modification*, not *creation*: a brand-new album has no prior ETag to
  match against. The phone never creates one — a new album is an addition the laptop pulls (§8) —
  so every `meta/` shard is created by the one laptop, which checks the name first (§7).

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
  adds_to        TEXT,               -- an addition's album (§8); NULL for an album
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
  live_video_filename TEXT,          -- that MOV's name on disk; the file no row is named for
  video_id      TEXT                 -- blob: 1080p-ceiling HEVC transcode
);
CREATE INDEX ix_photo_taken ON photo(taken_at);
```

**The row describes what is in the zone, not what is on disk.** `bytes` is the size of the
blob a tap actually fetches, and `filename` carries the extension its bytes really have. For a
video that means the transcode, for every still the 3200px HEIC, and `source_filename` keeps
the camera's own name so ingest can still find the file on disk: `IMG_1234.CR2` beside
`filename = IMG_1234.heic`, `VID_0001.MOV` beside `VID_0001.mp4`.

**A Live Photo is two files and one row, so one of those files is named nowhere else.**
`live_video_filename` is that name. It is the same kind of hint as `source_filename` and it
exists for the same reason — §7 answers "has this file been ingested?" by name — but the case
is worse than a rename, because no amount of re-deriving produces a row for the MOV. Without
the column an album holding a Live Photo reports itself changed on every run for ever: the MOV
is planned as an upload, the pair turns out to be claimed already, nothing is produced, and the
shard is rewritten identically. Three albums, 187 photographs and 376 MB of "to read" that
never was, hourly, until schema 4.

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

**An addition is the same schema with `adds_to` set** (§8). It lives at `addition/<uuid>.db`, is
never `encoded`, and its `name` and `parent` are the album's as they were when it was uploaded —
so the one thing that differs between "photos for this album" and "a new album" is whether the
album it names is there yet.

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
which §4's LIST already sees. The cost is repacking a whole album to add one photo — which is why
the *phone* never does: photos it adds to an album bring a pack of their own (§8), and the laptop
repacks when it merges them.

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
  thumbs_id     TEXT,
  addition_packs TEXT NOT NULL      -- the packs of the additions folded in (§8), space-separated
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

**That they do not disagree is now run rather than argued.** §3's catalog suites — the shard
writer and reader, the thumbnail pack, the merged rebuild, the sync loop — compile for
`iosSimulatorArm64` and execute against **iOS's own libsqlite3** on every CI run, beside the
same suites running on Linux against the pinned one. 250 of them, and the platform SQLite
answers each the same way.

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
  "not on the map" test. The map itself places only albums that own photos (§6): a container's
  centroid lands between its albums, so it is kept but never drawn as a pin.
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
- **Album list default sort: date, newest first**, toggled with oldest first — two states, no sort
  by name.
  Albums with no dated photos at all collect at one end. Both date orders key on the album's
  **latest** date, so oldest first is exactly newest first reversed: keying ascending on the
  earliest date put an album spanning the whole library at the top of both orders, and the sort
  icon looked broken. Inside an album there is no sort to choose — photos are oldest first.
  A **container** owns no photos, so its date, like its pin and its cover, comes from its
  descendants: it sorts by the latest photo anywhere beneath it, and its header reads
  "2 albums · 132 photos". Read from its own empty shard it said "0 photos" and sorted as undated.
  Its sub-albums sort the same way among themselves, beneath it (§6).
  Its cover's thumbnail is in the pack of the descendant that holds that photo.
- **Search:** album names, substring, case- and diacritic-insensitive via `name_folded`.
  No fuzzy matching (too noisy on short names), no filename search. The list keeps each match
  under its containers' headers (§6); a matching container keeps everything beneath it.
- **Date filter:** the album list keeps the albums with **at least one photo taken in the range** —
  days, both ends included, never times. A photo's day is the UTC day of `taken_at`, which is the
  camera's own day, because EXIF's zoneless local time is stored as if it were UTC; no time zone is
  applied, so no photo changes day with the device reading it. **One filter at a time:** picking a
  range replaces the typed text, and typing replaces the range. Matches keep their containers'
  headers as a search's do, and every count becomes the photos in the range — "12 of 132 photos",
  a header "1 of 3 albums · 12 of 252 photos" — so the list and the calendar (§6) never disagree.
  A range with no photos is never applied. Both reads use `ix_photo_taken`: a range's matches, and
  one `GROUP BY` for the calendar's photos per day across the whole library.
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

Version **5** is current. **2** added `source_filename` and redefined `bytes` as the size of
the blob rather than of the file on disk; **3** was the two-tier rewrite, collapsing the
original and preview ids into one `image_id` and giving `album_info` its `state` and
`encoding_version`; **4** added `live_video_filename`; **5** added `adds_to`.

The merged database is the one file here that migrates rather than being skipped: it is ours,
derived, and rebuilt on every app sync, so a migration only has to make the columns exist
(`addition_packs` came in with the first one).

A column that is merely *added* still costs a bump, and the reason is the reader below rather
than the one above: a shard has no column it was not written with, and SQLite answers a
`SELECT` naming an absent one with an error rather than a null. So a reader keeps a statement
per era and picks by `schema_version` — which is also why the bump is not optional for a purely
additive column. An older writer would read a newer shard correctly and then write it back
without the column, quietly undoing the repair once per run; being skipped and reported is
recoverable, and that is not.

---

## 4. Sync algorithm

Discovery is **one `ListObjectsV2` on the flat `meta/` prefix, and one on `addition/`**. The
responses carry the ETag and size of every shard, so those two requests *are* the sync plan.
There is no manifest object and therefore no shared mutable state to conflict on.

```
1. LIST meta/ and addition/  (encoding-type=url)
2. diff returned ETags against sync_state.db
     changed / new ETag  → download that shard
     key absent          → album deleted → drop its rows
3. rebuild the merged DB by replaying all shards (~1–3 s) — the CLI only if anything changed,
   the app on every sync
4. store the new ETags
```

The app rebuilds even when the LIST moved nothing, because step 2 records a deletion before
step 3 runs: a rebuild that fails after it leaves a merged DB that no later diff would ever
touch again. That was measured — an album deleted from the zone stayed on a phone's list across
relaunches. The app syncs at launch and on a pull of the album list, so either repairs it.

**The LISTs cover thumbnails too**, because a thumbnail pack is an ordinary blob referenced by
`album_info.thumbs_id` (§3): if the pack changed, the shard that points at it changed, and step
2 already saw that. The same holds for every derivative — nothing in the zone can change
without some shard's ETag moving.

**An addition is folded into the album it adds to** at the rebuild (§8): its rows become that
album's, counted, dated and placed with the rest, and its pack is recorded beside the album's own
(`album.addition_packs`), so the grid reads thumbnails from both. One still `uploading` is shown by
no reader, like an album. Those whose album is not there — a new album the laptop has not pulled
yet, or one deleted before it merged them — are shown together *as* that album, under the id they
name, with the name and parent the earliest recorded, so a second upload into a new album is not a
second album, and photos the phone may already have deleted from its gallery stay in sight. A row an addition shares with its album, left by a merge the laptop committed but did not
finish (§7), counts once.

The merged DB is **rebuilt wholesale** rather than spliced incrementally. There is no partial
update path, so stale rows are impossible by construction — which is also why there is no
"rebuild index" button in the UI: pulling the album list down syncs, and every app sync rebuilds.

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
  blobs/             images and video; what a clear empties (§6)
  packs/             thumbnail packs; always kept, never evicted (§6)
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
the 48pt filmstrip, the 26pt set-cover dialog. The only surface that uses `contain` is the
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
> 3× would need ~576px thumbs. It draws the **cached viewing image where one is already on
> disk**, and the upscaled thumb otherwise: the grid never *asks* for a blob, so it stays a
> local read that works offline and needs no per-tile loading state. That is §6's decision, not
> this one's — and it only became available once §6 started downloading an opened album's images
> anyway.

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
  - **A soundtrack is kept, or the file fails.** One the build cannot decode is still dropped
    and the video kept, but once decoding works, anything that loses the sound fails the file.
    That rule exists because of profile 1: the audio graph needed `aformat`, the ffmpeg build
    left it out, and every transcode came out silent without a word. No fixture had a
    soundtrack, so the suite could not notice; the synthetic video can now carry one.
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

> See **`mockups/placeholder.html`** for all 25 screens rendered at device size — album list,
> container, grid, pinch density, the album row's swipe actions, an album downloading, both
> viewer states, both map representations, set-cover dialog, settings, log out, first-run setup,
> and the five upload steps. They fix layout and
> content, not chrome: they were drawn before this section settled on Compose, so a button in
> them is an iOS button and in the build it is Material 3.
>
> One exception in content: the mockup's album map draws each photo as a dot, and the build
> draws it as the same 38pt thumbnail pin the album list's map uses (see *What is placed*).

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
| `AVPlayerViewController` | scrubbing, system volume, AirPlay and Picture in Picture are what a phone video is expected to have, and each would otherwise be a control drawn and got subtly wrong |
| PhotoKit picker (§8) | `PHAssetCollection` browsing, and deletion after upload |

Everything else is drawn, including the three hardest screens. `LazyVerticalGrid` exposes
`layoutInfo`, so the grid's prefetch is driven from visible-item state rather than from a
callback that fires once a cell is already on screen — far too late for the 424 KiB fetch the
2-column pinch density needs (§5).
Drag-to-select is a `pointerInput` gesture. The fullscreen viewer's zoom + page is a
`HorizontalPager` with a hand-rolled pinch/pan beneath it rather than `transformable`, which
claims every drag: at 1× a one-finger drag pages, zoomed in it pans and the pager stands still.
Zoom is for stills — a video and a Live Photo play in native views a Compose layer cannot scale.
Drag-to-dismiss is not built.

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
· sort · upload** — and the toggle always shows the view you switch *to*. A map drops the sort:
it has no order, and an icon that changes nothing visible reads as broken.

The table reads **from the right edge**: gear outermost on every screen, then the toggle, then
sort. So no icon ever changes place — dropping sort on a map moves nothing, and the toggle stays
under the thumb that just tapped it. An earlier order put the toggle between gear and sort, and
it jumped a slot each time the map came and went.

| screen | trailing icons |
|---|---|
| Albums (list) | gear, **map**, sort, upload |
| Albums (map) | gear, **list**, upload |
| Container list | gear, **map**, sort, upload |
| Container map | gear, **list**, upload |
| Album grid | gear, **map**, upload — no sort: an album's photos have one order (§3) |
| Album map | gear, **grid**, upload |

**An album's own upload adds to it.** On a list the upload makes a new album whose parent is the
screen it started from (§8); an album holds photos or sub-albums, never both (§2), so from inside
an album of photos the only legal place for photos is that album.
| Fullscreen viewer | gear, share, set-cover |
| Settings | — |

**There is no hidden "…" menu anywhere.** Every icon performs one visible action.

**The album list shows every level.** A container is not a row that hides what it holds: it is
the **header** of its own group — its name in capitals, what it holds and its strip, no cover —
and its sub-albums follow as ordinary rows, **one indent (22pt) in per level**. A nested
container is a header one indent in, named on its own; the indent already says where it sits. A
**heavier line closes each group**, so the rows after it plainly belong to the level above.

- **Siblings keep one date order** (§3), albums and containers alike. Putting a level's own albums
  first would have kept a header from seeming to claim the rows after its group, but it pulls a
  recent album away from its date; the indent and the closing line do that job instead.
- **Headers are sticky, stacked by depth.** While a group is on screen its header stays pinned,
  beneath the headers of the containers it sits in, each keeping its indent. A pinned header is
  the header itself, not a picture of one: a tap opens its container, and its strip reveals its
  actions.
- **Tapping a header opens the container**, as tapping its row used to. That screen lists
  everything beneath it in the same layout, starting at the left edge — the large title names the
  container, so no header repeats it — and it keeps what a level carries: its own map, framed on
  its albums, and uploading into it. Upload from the root still makes a top-level album.
- **A search keeps each match under its containers' headers** and hides everything else; a
  matching container keeps its whole group. A header that only *holds* a match describes what the
  search kept, all three ways: its line reads "1 of 3 albums · 40 photos", its strip is those
  albums' bytes, and its actions act on them alone — a download from a search never fetches an
  album the search is hiding.
- **The date filter is the search field's trailing calendar icon**, not a fifth bar icon: it
  belongs to the search, and the bar's four are fixed. It opens a **full-screen calendar sheet** —
  months scrolling vertically from the first dated photo to the last, a strip of years to jump
  with, and on every day **how many photos were taken on it**, with a tint that grows with the
  count so a trip stands out while scrolling past. A centred dialog was drawn and dropped: its
  cells were too small to read a number in. **Tap and drag** picks a range in one gesture; **a tap**
  sets a start and waits for a second tap as the end, and Apply after one tap filters that single
  day. A drag is told from a scroll by the upload picker's rule (§8). On a finished range a tap
  starts again and dragging either end moves it; a range with no photos cannot be applied.
  **A whole month or year is one tap.** A month's title is drawn as a button with its total, and
  picks every day of it. Each year has a heading with its total and a *Pick year* mark, and the
  heading **stays pinned** above the months while any of that year's are on screen — the next
  year's pushes it away, as a container's header does on the list — so the whole year is one tap
  from anywhere inside it. Dragging from a title or heading to another picks both whole, and
  everything between. The year strip only jumps: a year with two targets that did different
  things would be one too many. Applied,
  the field shows the range read-only — "12 – 20 Mar 2024" — a tap reopens the sheet on it, and ✕
  clears it. The map follows a range as it follows a search.
- **The query and the range survive opening an album**, so coming back finds the list as it was
  left. A container's screen has no field, and neither of them narrows it.

**The map is a representation of the album list, not a destination** — same title, subtitle and
icons, toggled rather than pushed. The same holds one level down: an album's grid and its map
are two views of one album.

**The basemap is embedded; everything on it is drawn.** MapLibre Native, through MapLibre
Compose, draws VersaTiles' vector tiles — the public server serves no raster tiles, which an
earlier draft of this section assumed when it planned a raster layer on a Compose canvas. It
draws the tiles and nothing else. The pins and clusters are a Compose overlay placed by the
shared tier's own Web Mercator projection from the camera the basemap reports, so what can be
wrong about them is unit-tested and a headless frame still shows them. MapKit would have given
the same split with a second map stack to keep apart; MapLibre is one library on both targets.

> **The price is the harness.** MapLibre presents into a native surface that needs a window, so
> `ImageComposeScene` — `/screenshot` and `:tests:app` — cannot host it. The basemap is therefore
> a UI-interop port (`BaseMap`), exactly like the video surface: the desktop window and the phone
> install MapLibre from `:app:map`, and everything else draws `:ui`'s plain stand-in, which still
> pans and zooms. The iOS suite is the one automated run that shows tiles. Also accepted:
> MapLibre Compose is pre-1.0 and alpha on the desktop, and the overlay can trail the native map
> by a frame while panning — only a device says whether that shows. On the phone MapLibre arrives
> as a static archive inside its klib; the app target links the system libraries it needs.

**What is placed.** The album list's map is **flat**: every album that owns photos and has a
location, whatever level it is opened from. A container's centroid (§3) lands between its
albums, somewhere nobody went, so containers get no pin. A search narrows the pins as it narrows
the list. An album's map places its photos. Both use one pin, §5's 38pt square thumbnail from a
pack already on disk; a cluster is a count in a blue circle. The subtitle counts what is placed
against what could be — "212 of 239 albums on the map", "88 of 132 photos on the map" — and is
the only thing that says some have no location.

**Clustering, the album→pin projection and pin selection live in the shared tier**, not in the
renderer, so the part that can be wrong is the part that is unit-tested. Supercluster's greedy
radius merge — the rule MapLibre GL uses, 44dp — is worked out once for every whole zoom level,
off the main thread, when the points change. Each level merges the one below it, so zooming in
only ever splits clusters. Panning never reclusters: the map draws the level for `floor(zoom)`.

**Taps.** An album's pin opens that album **on its own map** — its photos where they were taken,
one level down, with the grid a toggle away — and Back returns to the album list's map as it was
left. A photo's pin opens the viewer at it. A cluster zooms until it splits. One that never
splits — several trips to one town — lists its albums in a sheet instead, each row opening its
album on its map as a pin would; or, for photos, opens the viewer at the earliest, since paging
walks through the rest.

**The camera** first frames the level's own albums: the library at the root, and a container's
albums on its map, with every other pin still around them. It is remembered per level, on that
level's entry in the back stack, in memory only — it survives opening an album and toggling to
the list, and is framed afresh once the level is left or the app relaunched. Zoom runs from the
whole world to street level; rotation and tilt are off.

**The scroll** is remembered the same way, for the album list, a container and an album's grid:
on the level's back-stack entry, in memory only, kept across opening something deeper and
toggling to the map, dropped once the level is left. It names the first row on screen by key, not
by position, so a sync that rebuilds the list while an album is open still comes back to the same
row — or, that row gone, to about where it was. A new order has no place to return to: a sort
forgets every list's scroll, since the sort is shared, and a search or a range forgets the album
list's; the grid keeps its own, photos having one order. Back from the viewer, the grid stands
where it was left and then scrolls the least that shows the photo the viewer was on, whole — its
row to the top edge from above, to the bottom edge from below — since a swipe can carry the viewer
far past the tiles that were on screen.

**Tiles come from the public VersaTiles server**, cached by MapLibre Native's own ambient cache
at its default size, with no Settings entry. Offline, an area looked at before still draws and
the rest is a plain ground; the pins, clusters and thumbnails always work, being local. Two
consequences are accepted rather than mitigated: it depends on a third party this project does
not control, and tile requests disclose roughly where the library's photographs were taken. If
either bites, the alternative needs no new mechanism — a regional `.versatiles` extract stored in
the zone as an ordinary blob, read with the range GETs §2 already relies on.

**Colour carries meaning; it is never decoration.** Standard bar buttons are **monochrome** —
the glyph takes the on-surface colour. Blue-for-tappable is not used: if every affordance is
tinted, tint says nothing, and the colours below have to keep meaning something.

| context | glyph | background |
|---|---|---|
| nav bars | on-surface | surface |
| over a photo | white | **dimming scrim** |
| start work (download an album) | **green** | surface |
| active state (this photo *is* the cover; pause) | **blue** | surface |
| destructive (clear an album) | **error red** | surface |
| progress fill | **blue**, green when complete | — |

> **Gold is retired.** It carried "active state" alone, on the filled set-as-cover star. When
> the cache controls arrived they needed a colour for *running*, and blue was already the
> progress fill — so gold and blue would have been two colours for one meaning, which is exactly
> the decoration this rule forbids. Blue took the job and the star turned blue with it.

### Building it for the phone

`:app:ios` is the composition root and `app/ios/Photos.xcodeproj` is a shell around it: one
Swift file presenting a `UIViewControllerRepresentable`, an `Info.plist`, an asset catalog, and
a Run Script phase that calls `embedAndSignAppleFrameworkForXcode`. Everything a person sees is
Kotlin, compiled from `:ui` and identical to what the Linux harness renders — so the project
file is not where the application lives and changes roughly never, which is why it is checked
in rather than generated.

The Kotlin framework exports **one object with one function**, `PhotosEntry.viewController()`.
Swift never learns that there is a model, a queue or a catalog.

Five facts about the build are decisions rather than defaults:

- **The framework is static**, so the `.app` embeds no dynamic framework and re-signs nothing.
  The consequence is that the final link is Xcode's rather than Kotlin's, which is why the app
  target — not the Gradle build — carries `-lsqlite3`. §3 says iOS uses the platform's SQLite;
  this is where the app says it, since SQLiter deliberately names no library of its own.
- **Only arm64 simulators are built.** `:app:ios` declares `iosArm64` and `iosSimulatorArm64`
  and no `iosX64`, so the Xcode project pins `ARCHS[sdk=iphonesimulator*]` to match. Otherwise
  a `generic/platform=iOS Simulator` destination asks for a slice that does not exist.
- **`CADisableMinimumFrameDurationOnPhone` is set.** Without it iOS caps the app at 60 fps and
  Compose refuses to start rather than allow that quietly — which matters here more than in most
  apps, because §10's remaining unknown is a question about frame rate.
- **A cached blob carries an extension on disk.** §2 names every blob by its hash alone, and
  AVFoundation and PhotoKit both decide what a file is from its extension before reading a byte.
  So `blobs/` holds `<id>.<ext>`, the extension sniffed from the bytes as the blob lands — a Live
  Photo's still is the camera's own file, HEIC or JPEG, and its MOV must stay a `.mov` — and the
  players are handed the blob itself. Everything that counts or clears the cache still reads the
  id as the name up to its first dot, and a blob cached bare by an earlier build is renamed on its
  first lookup. Measured: on a simulator `AVURLAsset.playable` is **false** for an MP4 under its
  bare hash; on a phone `PHLivePhoto` refuses a pair under bare hashes outright
  (`PHPhotosErrorDomain` 3303). An earlier build reached blobs through symbolic links,
  `playable/<id>.<ext>`, which a simulator accepts and **a phone does not**: the same pair,
  byte for byte, answered degraded-then-nothing through the links and played as real files. A
  video could instead be given its type (`AVURLAssetOverrideMIMETypeKey`, verified on the phone),
  but `PHLivePhoto` takes nothing but file URLs, so one naming rule serves both.

- **Debug and Release link different frameworks.** Both are named `PhotosKit`; Release's is
  `:app:ios`, and Debug's is `:app:ios-debug` — the same app plus `:app:control`, the control
  server. A Kotlin framework cannot vary its dependencies by build type, so the boundary is a
  module rather than a flag: a TestFlight binary carries no listener code at all. The Debug app
  starts the server only when launched with `PHOTOS_CONTROL_PORT`, binds it to loopback, and adds
  `NSAllowsLocalNetworking` to its processed `Info.plist` so it can reach a mock zone on the host;
  Release keeps App Transport Security whole.

`Scripts/ios-sim.sh` is the loop: build, boot a simulator, install, launch, screenshot. It is a
script rather than a paragraph because the incantation spans four tools, one of which has a rule
worth knowing — `simctl` forwards an environment variable only when it is named `SIMCTL_CHILD_*`
— and because the app's console is where an uncaught Kotlin exception ends up.

### Fullscreen viewer

One screen, two states. Chrome is identical in both — back, then gear/share/set-cover, a
filmstrip, and a bottom line carrying the date. A **Live or video mark appears once**, top-left,
and is the only thing that differs.

**A Live Photo or a video is marked by a glyph, not a word** — a ring (`MotionPhotosOn`) for Live, a
play triangle for video. On grid and picker tiles it is
16pt, white, bottom-left, with a soft shadow and no backdrop, as iOS Photos does; the same at
either grid density. The filmstrip carries none: at 48pt it crowds the tile, and the open photo is
marked already. In the viewer the mark sits at the screen's corner, not the photograph's, so it
takes the surface's text colour at 20pt and no shadow — a letterboxed photo leaves that corner on
the light ground, where white would vanish. A video's mark stands on its poster only and goes
once the player is mounted, whose own controls take that corner. Each mark tells VoiceOver "Live
Photo" or "Video".

**Swiping pages through the album**, and the filmstrip follows: 48pt tiles, the open photo
outlined and kept in view. Once the open photo is showing, the model decodes the photo either
side — one each way, since a decoded frame is tens of megabytes and a swipe drags in exactly one —
so a swipe draws a photograph rather than a placeholder. The ±3 blobs are still queued at tier 1.

**The viewer is the only screen that rotates.** Everywhere else is a phone column, so the app
is portrait until a photo opens and turns back when it closes. Sideways, the photograph has the
whole screen: no nav bar, no filmstrip, no date — only a back button and the mark. iOS asks
the *app delegate* which orientations are allowed, not the Compose view controller inside
SwiftUI's hosting controller, so that is the one line of Swift the rule costs.

**Motion plays in the platform's own views.** A video opens on its poster and hands over to
`AVPlayerViewController` once the transcode is on disk, playing as it appears and pausing when
a swipe leaves it. A Live Photo plays in `PHLivePhotoView` from the two files the queue already
fetched — the untouched still and its MOV — with Photos' own press-and-hold, and plays a brief
hint once when it arrives so the motion is discoverable before the mark has to announce it.

> **A pair iOS will not assemble still shows its still.** `PHLivePhoto` answers twice: a degraded
> photo built from the still, then the full one — or, when the pair cannot be assembled, *no
> photo*, not cancelled. Measured on a simulator, 167 ms apart. Assigning each answer replaced the
> degraded photo with nothing and left an empty native view over the viewer's own still, which is
> a blank screen; so the view is mounted only once a photo exists and an empty later answer never
> replaces one.
>
> **What makes a pair assemble was measured, not assumed.** On macOS, with Apple-written files:
> the MOV must be a **QuickTime** file whose `mdta` metadata box — handler `mdta`, a `keys` box
> naming `com.apple.quicktime.content.identifier` — sits **directly under `moov`, with no version
> and flags**. The same frames and identifier in an MP4 container come back as no Live Photo, and
> so does the same box where ffmpeg's mov muxer writes it, as an ISO full box inside `moov/udta`;
> the fixture writer reframes it. The still-image-time metadata track Apple also writes is not
> required. The still's identifier lives
> in Apple's maker note, which ImageIO reads only **big-endian**. The synthetic pair the app suites
> sync is written that way, and the iOS suite asserts the app's own view gets a full Live Photo
> from it and plays its hint — assembled is not playing, as the symbolic links above showed. A real library's MOV and still are uploaded as-is (§5), so genuine pairs keep both.
The desktop harness installs no Live Photo view: the still stays and the mark says what the
phone would do with it.

Swiping loads the 3200px image, and deep zoom needs nothing further: at 424 KiB one blob serves
both, so the viewer has no escalation step and no second loading state.

> The `Original …` badge is gone. It answered "is this the real file?", and since §5 the answer
> is uniformly no — a badge that always says the same thing carries no information. What the
> laptop holds is a property of the archive, not of the photograph on screen.

**Set as album cover** is a star: outlined when the photo is not the cover, **filled and blue
when it is** (see the colour table above — gold is retired). Setting a cover from inside a sub-album opens a dialog to choose whether it covers
the sub-album or its parent container.

> The star means *favourite* in Photos. There is no favourites feature here, so nothing
> collides — but if one is ever added, the star is taken and set-as-cover must move.

### Caching and storage

**Two triggers pull images, and only two: opening an album, or asking for it on the list.**
Nothing else fetches an image. **Leaving an album stops its pending downloads**, keeping
whatever landed; an explicit request does not stop, because that is the whole difference
between the two. **Nothing is ever auto-evicted** — what is downloaded is kept until it is
cleared, and the row controls are the only route.

An album's download covers **everything its photo rows reference** — `image_id`, `video_id`,
`live_still_id`, `live_video_id` — so "cached" means the album genuinely works offline rather
than working until the first video.

> This replaces browse-to-cache, and with it the line that said an album could only become
> available offline by browsing it first. Preparing for a flight is now one gesture.

**Thumbnail packs are unchanged and are not part of any of this.** Every album's pack is
fetched in the background at launch, unconditionally, never stopped by navigation and never
evicted (~0.5 GB) — that is what makes every grid open instantly and offline. Packs are
therefore **excluded from an album's cache state**: they arrive whether or not anyone asked,
so counting them would leave every row looking partly cached and "nothing held" would never be
true of anything.

> Never evicting is what makes §5's cap a phone decision rather than a bill. At 424 KiB per
> viewing image, a thousand photos keeps 0.42 GB permanently; byte-for-byte originals would
> have kept 3.1 GB, and the tier that used to sit between them existed largely to avoid that.

#### The download queue

One queue serves packs and images alike, ordered by what is on screen. **The ladder, highest
first:**

| tier | what |
|---|---|
| 0 | the open photo's blobs |
| 1 | the viewer's ±3 neighbours |
| 2 | packs for the album rows on screen |
| 3 | the open album's images |
| 4 | the remaining background pack sweep |
| 5 | explicitly requested albums, in request order |

An explicit request is last because everything above it is something the person is looking at
right now, and the whole sweep is ~0.45 GB — about a minute, not a wait worth restructuring for.

**Three worker roles, not one pool**, because the two size classes have opposite bottlenecks.
Measured against the live zone from a domestic link:

| concurrency | small blobs | large blobs |
|---|---|---|
| 1 | 2.33 MB/s · 168 ms each · **100 ms of it TTFB** | 6.36 MB/s |
| 2 | 3.88 MB/s | **7.49 MB/s** |
| 4 | 5.17 MB/s · 241 ms each | 7.12 MB/s · 3.7 s each |
| 8 | 7.84 MB/s · 413 ms each | — |

A small blob spends most of its life waiting for the first byte, so concurrency is what reaches
link rate; a large one saturates on one or two streams and after that only grows its own
latency. So: **1 `immediate`** worker serving tiers 0–1 and nothing else, **4 `small`** workers
for blobs ≤ 4 MiB, and **1 `general`** worker that may take anything — which is what stops four
video transcodes from occupying every worker at once.

> The link tops out at **7–8 MB/s** however it is reached, which confirms the 7.5 MB/s used
> throughout this document.

**4 MiB is the size boundary, and it was measured rather than guessed.** Across 6,525 real
rows: 90.4% of blobs are under 1 MiB and 99.4% under 2 MiB; only **24 are ≥ 4 MiB and 11 are
≥ 8 MiB**, every one of them video, the largest 73.9 MiB. A 1 MiB boundary would have classed
9.6% of blobs large — mostly harmless 1–2 MiB stills. **They cluster**, which is what makes the
role split necessary rather than theoretical: one album holds seven blobs ≥ 4 MiB totalling
214.5 MiB in 475 rows, so opening it puts four big videos in flight at once.

**Re-order, never cancel — except the immediate worker.** An in-flight fetch on `small` or
`general` always finishes, so no partial transfer is thrown away. The immediate worker is the
exception, and it has to be: swiping from one 73.9 MiB video to the next would otherwise hold a
worker for ten seconds on something already off screen. Having exactly one such worker is also
what bounds it — a new tier-0 target *replaces* the old rather than adding to it.

**A blob that will not download is given up on after three attempts** and skipped for the rest
of the session, retried on the next launch. A queue that retries for ever cannot drain, and a
row showing an error is more use than a strip frozen at 97%.

**The scheduler lives in the shared tier, not in an adapter.** The ladder, the roles and the
retry policy are the part that can be wrong, so they sit in `:app:domain` where a test with no
zone and no filesystem can hold every fetch open and assert on which ones started. The platform
port is four methods: `has`, `fetch`, `delete`, `present`.

> **Which shared tier, and why there are two.** `:domain` is what the app shares with the
> **CLI**: the catalog, the shard schema, the S3 client, the ingest rules — everything a
> laptop/phone disagreement would corrupt. `:app:domain` is what the two *apps* share with each
> other, and the CLI has no use for any of it: it writes thumbnail packs rather than collecting
> them, and it has no `blobs/` directory, no queue and no screen to keep in order. So the
> scheduler, the model, the ports and §4's on-device cache live there, and `:ui` is composables
> and nothing else.
>
> The line between them is not taste. `:app:cli` links `:domain` into a 26.7 MiB binary with a
> glibc 2.19 floor (§7), so `:domain` carries no Compose — while `:app:domain` carries exactly
> one Compose type, `ImageBitmap`, because a decoded preview is what the viewer draws and
> converting it twice would be worse.

**On iOS, tiers 0–4 run in-process and tier 5 runs on a background `URLSession`.** Tiers 0–4
exist to serve what is on screen and are pointless when the app is not; tier 5 is already last
and has no ordering requirement, which is precisely what a background session can offer — it
cannot be reordered, but nothing needs it to be. A persisted set of requested album ids means a
download survives the app being killed.

#### Where the controls live

**On the album list, and nowhere else.** Settings keeps account, storage totals, sync and log
out — it has no album list. One list means nothing has to keep two renderings of the same 288
albums consistent, the hierarchy comes free, and asking for an album happens where you are
already looking at it. **A container's control applies to every descendant**, which is how a
person thinks about a trip — on its header, which heads them all on the list. Under a search it
applies to the matches the header kept (see *Navigation and chrome*).

Each row carries **one strip on its trailing edge, and the strip *is* the progress bar**:

| strip | means |
|---|---|
| grey | nothing held |
| part blue | this much of the album is held, by bytes |
| part blue, **pulsing** | …and bytes are moving right now |
| full green | every blob is on disk; the album works offline |

That is the entire cache vocabulary, and it replaced a separate gauge plus a line of prose on
every row — so **the row's caption is what the album contains and nothing else**. It fills by
bytes rather than by count, because an album whose one video is missing is not nearly done.

> Motion means one thing everywhere in the app: bytes are moving for *this* item. A queued but
> waiting album shows a still bar, which is what keeps a pulsing row worth looking at in a list
> of 288.

**Swipe-left reveals icon-only actions, and tapping the strip does the same.** One row at a time,
and while one is open **a tap anywhere in the list closes it** rather than opening an album — the
tap that dismisses a menu is almost always on the row it belongs to. Which actions appear is a
function of state, so each row offers exactly what applies:

| album state | revealed |
|---|---|
| nothing cached | **download** |
| partial, stopped | **download** · **clear** |
| partial, running | **pause** · **clear** |
| complete | **clear** |
| nothing to fetch (§10's emptied album) | nothing |

**Swipe-right is unused**: it collides with the interactive back gesture, which matters at every
level of this list rather than only at the root. The strip's hit area is grown to 44pt without
growing the 4pt mark, and *that* is what makes the actions discoverable — there is no static
affordance for a swipe on any platform, and being visible is what a gesture is not. **Clear also
pauses**, is allowed at any time including on the album currently open, and asks for no
confirmation: it is reversible by re-downloading and nothing in the zone is touched.

iOS offers no system-level per-app cache clear (only Offload/Delete App), and a
`Settings.bundle` cannot help — it is a static plist with no buttons, no dynamic rows and no
images — so these controls are the only route.

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
| `Keyring` | (1) (2) | the Secret Service, in-process (§1): libdbus-1 on the CLI, dbus-java in the app |
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

**Every green CI run publishes the binary.** `./gradlew :app:cli:dist` strips the release
executable with konan's own `strip` — the toolchain that linked it, so a checkout that can
build at all can package — into `app/cli/build/dist/photos-cli`, and the workflow uploads that
as `photos-cli-linux-x64`, kept 7 days. The strip is what makes the artifact the 26.7 MiB above
rather than the linker's much larger output; the cost is that a crash in a downloaded binary
prints addresses instead of Kotlin frames, which is the right trade for a build fetched to try
by hand. It strips to a *copy*: the `.kexe` is left alone, so `:tests:cli` still forks an
unstripped binary and a failing scenario still reads. Only a run that passed `build` and the
end-to-end scenarios uploads anything, and the zip drops the exec bit — `chmod +x` after
unzipping.

> **x265 is GPLv2.** A statically linked binary inherits GPL terms *if distributed*. CI
> artifacts on a public repository are fetchable by any logged-in GitHub user, so that day has
> arrived: the repo carries no LICENSE and no third-party NOTICE, and it owes both.

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
  with its carved JPEG, and against `live_video_filename`, which is the only way the MOV half
  of a Live Photo is accounted for at all.
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
- **Pull is folded into `sync`, and it is what makes the laptop the archive.** A new album from
  the phone is an `uploaded` addition naming an album no shard is (§8). `sync` pulls it straight
  from its earliest addition — or from the one a stopped run already claimed — in four steps that
  each survive being interrupted:

  ```
  claim     If-Match on the addition: source_path, and every row's final name
  download  into that folder, skipping files already there
  commit    meta/<adds_to>, derived from those files, at encoded
  delete    the addition — its full-quality blobs are then the sweep's
  ```

  The album's other additions merge into it afterwards, in the same run. Its id is the one the
  phone minted and every device already shows it by; its name, parent and `added_at` are the ones
  the addition recorded, and `photo.id` is carried onto the derived rows, so a cover set on the
  phone still points at its photo. The folder is its parent's folder and its name.

  The order is the point. Claiming first records where the album will land *before any file
  exists*, so a run interrupted mid-download resumes into the same directory instead of
  choosing a fresh name beside it and fetching everything a second time. The phone's full-quality
  blobs — the only copy until the library copy is on disk — stay referenced by the addition until
  the album is committed. A run that stopped after the commit finds the album there and the claimed
  addition beside it, which is an ordinary merge with only the delete left.

  **A claimed folder is the pull's until it lands.** The walk leaves a folder that an `uploaded`
  addition with no album claims alone — no album of its own, nothing uploaded from it — because the
  files a stopped run already downloaded belong to that pull. Walked as a new folder they once went
  up as a second album beside the one the pull then finished into the same folder: `Transdinarica`,
  70 photos beside 400. A folder claimed by more than one, album or pull, is left alone and named on
  every run, and a pull does not resume into it.

  **A name taken is not renamed.** Names are unique among siblings, ignoring case (§2). A new
  album whose name a sibling has — a laptop folder made since, or another device's album — is not
  pulled, and is named in the report on every run until one of the two is renamed; readers keep
  showing it meanwhile. The same holds for **sibling folders named alike but for case** on the
  laptop: neither is ingested, nor anything beneath them, until one is renamed.

  An addition at `uploading` is skipped entirely: it is still in flight, and its shard is what
  keeps its blobs safe from the sweep. A phone album an older app wrote straight under `meta/` is
  no longer pulled; the zone was checked to hold none before this CLI was installed.

  A pulled Live Photo's MOV is written under `live_video_filename`, falling back to
  `<still-stem>.MOV` for a row from before schema 4 — the convention all 187 pairs already
  follow; pairing is by content identifier, so the walker re-pairs it either way. Pulls write unconditionally — the ignore
  rules govern what goes up.
- **An addition is merged into its album, and the laptop alone names its files.** An `uploaded`
  addition (§8) whose album is `encoded` — or pulled earlier in the same run — goes
  in after every other write of the run, in four steps that each survive being interrupted:

  ```
  claim     If-Match: source_path = the album's folder, and every row's final name
  download  into that folder, skipping files already there
  derive    only those files, into the album's shard: rows appended, pack repacked, still encoded
  delete    the addition's shard — its full-quality blobs are then the sweep's
  ```

  **Names are fixed in the claim**, against what the folder and the album already hold: a clash
  gains ` (2)`, compared by stem and ignoring case, since a derivative renames its source (a video
  lands as `.mp4`) and the library may not tell case apart. The phone does none of this (§8): it
  cannot see the folder. Fixing them before any file exists is what makes "skip a file already
  there" a safe resume, exactly as claiming the path is for a pull.

  **A claimed addition's files are not new photos.** A run that stopped after the download leaves
  them in the album's folder; the walk keeps every file a claimed addition names out of that
  album's uploads, so the merge — which carries the phone's `photo.id` onto each derived row —
  brings them in once. A run that stopped after committing the album finds the album already
  holding those files and has only the delete left. A file that failed to derive stays in the
  folder, and the next walk takes it up as the new photo it is.

  **An addition whose album is gone becomes that album again.** Its album deleted before the
  merge, it reads exactly as a new album does — photos for an album no shard is — and is pulled
  the same way, under that album's id. Not in the run that deleted the folder, which deletes
  before it pulls: a pull never puts back a folder the same run deleted, so it lands on the next.

  An addition at `uploading` is skipped like any upload in flight, and abandoned past the
  seven-day floor the same way.
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

  > At `ENCODING_VERSION = 1` this path was dormant for laptop-owned albums: the schema's second
  > CHECK forbids an `encoded` album at version 0, so no legal shard could sit below the
  > profile. What exercised the carry-over was the pull and the merge, which derive a phone
  > upload's files on their way to `encoded`. **Version 2 is the first bump**, and it woke the
  > re-derive for every album: every video transcode written at 1 had lost its soundtrack (§5).
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

- `OnCalendar=hourly`, `Persistent=true`, `RandomizedDelaySec=5m`. A no-op run is two LISTs —
  `meta/` and `addition/` — which is exactly what the ETag cache beside the shards buys.
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

**Full access or nothing.** Denied or "Selected photos" access shows the picker's explanation —
albums are needed for the name, and deleting needs full access too — with a button into
Settings. There is no reduced mode: it would be a second picker to build and to test.

**Flow.**

```
upload icon               on the album list, a container, or inside an album
  → gallery picker        loose photos with drag-across-to-select, oldest first
                          and opened at the end, under a pinned year heading;
                          or, below them, an album whole;
                          Live Photos and videos carry the tile mark (§6)
  → album dialog          one field: an album's path, picked from a list or typed,
                          pre-filled from where you started,
                          delete-from-gallery checkbox
  → [tap Upload]
  → prepare               export every asset to disk, name its file, pack the thumbnails
  → write the addition    at `uploading`, naming every object
  → background upload     every PUT pre-signed; each object read back by HEAD
  → write the addition    at `uploaded`, If-Match
  → delete from gallery   when asked: the photos, and a gallery album chosen whole
                          once it holds nothing else
```

**The picker's grid is oldest first, and opens at its end** — the Photos app's own layout, so the
library reads the way it does everywhere else on the phone, and the newest photo, the one being
uploaded, is still what the picker lands on. The grid's scroll is its own. §3's rules are about the
catalog, and the gallery is not the catalog; there is no sort to choose.

**The albums come after the photos.** Above them they would sit thousands of rows up, out of reach
from where the picker opens; below, they are in view at the end, under the newest photos, headed
*Or upload a whole album*. The end is the list's true end — the albums in view, however many there
are — and the photos carry no label of their own: the nav bar counts what is selected, and a year
heading heads them.

**An upload sends oldest first**, the grid's own order. The order decides only which of two photos
sharing a camera name keeps it and which gains ` (2)`, so it stays first-come-first-served, and a
gallery album taken whole names its photos exactly as a loose pick of the same photos would. The
album's own order comes from `taken_at` (§3) and never from this.

**A later open comes back where the picker was left.** One position for the session, in memory
only — the first item on screen named by key, its position as a fallback — so working through a
library a batch at a time does not mean scrolling past everything that has already gone up. It is
kept on the upload model rather than on the back stack, which drops a screen's scroll as the
screen is left (§6): here that is exactly the moment worth remembering. **A picker left at its end
opens at its end again**, remembered as the end rather than as the row it put first: the library is
re-read on every open, and the photos taken since land below that row, out of sight. The
thumbnails are fetched outward from where the picker opens, nearest first on either side — from
the library's head would leave the screen grey until the loop reached it. Photos uploaded and
deleted from the device take their row's key with them; the position then falls back to the same
place in the list, which the rows below it — the newer photos — moving up make the next photo the
upload did not take.

**The Upload bar takes its room from the top.** It comes with the first photo selected and goes
with the last, and the list keeps its bottom edge where it was rather than its top: a photo just
tapped near the bottom stays in view, and a list at its end stays at its end. During a drag that
selects, the list holds still until the finger lifts — moved under the finger, the range would
carry on from another photo than the one the finger is on.

**The loose photos are marked by year.** A heading is drawn wherever the year changes from the row
above — so it follows the grid's order rather than assuming one — and stays pinned while any of
that year is on screen: the calendar's year heading (§6), for the same reason, since in a library
of thousands there is otherwise no way to tell where you are. **The year alone**, and nothing to
tap: a whole year is nobody's album, and an inert heading leaves the drag the one gesture reading
the list. It is also transparent to that drag — what is under the finger is looked up among the
rows alone, so the photo *beneath* the pinned heading is still what a selecting finger is on,
which is what keeps selecting-while-scrolling working where the pinned band and the autoscroll
band overlap.

The date is PhotoKit's `creationDate`, a property of the fetch the picker already makes — unlike
an asset's filename, which is a query apiece and is why the picker shows none. Photos the library
has no date for are one *Undated* group, left where PhotoKit puts them, which under the oldest-first
fetch is first. **§3's calendar decides the year**, with no time zone applied, so a photo's year here
is its year everywhere else in the app; a photo taken in the first hours of 1 January local time can
therefore sit under the year before, which is a price worth one date rule rather than two. On the
harness the date is the file's modification time — enough to head a year with, and deliberately not
what the stand-in orders by, since a modification time is whatever last copied the file.

**No prompt appears during the upload flow.** The password was read from the Keychain when the
app launched (§1) and is already in memory; tapping Upload uses it to pre-sign every PUT for
this album, and the background session then runs against those URLs alone.

**The album dialog is one field and a list.** The field holds a path from the library root —
`Trips / Italy` — and the list below it every album that can take photos, labelled by path, in the
album list's order: every album the phone shows that holds photos rather than sub-albums (§2), and
the new albums this phone is still uploading. Tapping the field opens the list; typing filters it.
The path is matched ignoring case and the spaces around each `/`, and since names are unique among
siblings (§2) a path names at most one album.

- **Pre-filled from where the upload started.** Inside an album, that album is selected. In a
  container, the field reads the container's path and then the gallery album's name
  (`Trips / Croatia`), or the path alone for loose photos; at the root, the gallery album's name.
- **A path no album has offers `+ New album "<path>"`.** Tapping it only adds the album to the list,
  tagged *new*, and selects it: nothing is created until Upload, and Cancel drops it. Its last
  segment is the name and the rest its parent, which must be an existing container — **the phone
  never creates containers**. A container that does not exist, a photo album mid-path, or a path
  ending in a container is said under the field instead, and offers no `+`.
- **Upload needs a selected album.** Picking one selects it, and so does typing its path exactly;
  editing away from it clears the selection. The line under the field says what Upload will do:
  *Adds to Trips / Italy*, or *New album in Trips*.

**Every upload is an addition**, to an album that exists or to a new one. A new album is an id the
dialog mints when it is added; the upload records that id, the name and the parent, and a second
upload into the same album — picked from the list while the first is still on its way — records
the same id. No reader shows an album until an addition to it has landed, and then every reader
shows the additions to it as that one album (§4); the laptop's pull makes it an album of its own
(§7). The phone never writes `meta/`.

The photos do **not** go into an album's shard. A shard has one `state` for the whole album, and
an encoded album holding rows the laptop has not archived would need that state per photo, in the
pull and the sweep alike. So they go up as an **addition**: a shard of their own at
`addition/<uuid>.db`, with its own `state` and its own pack, whose `album_info.adds_to` names the
album. Everything else about it is an upload like any other — the same flow, the same order (the
addition first at `uploading`, the objects, the addition again at `uploaded`), the same resume,
cancel, abandonment and delete-from-gallery; the sheet and pill say *Uploading to* the album's path.

- **Readers fold it into the album** (§4): its photos are the album's the moment it lands, with no
  badge — a photo the phone uploaded behaves as any photo.
- **The laptop pulls or merges it** (§7): the files go into the album's folder, the rows into its
  shard, and the addition is deleted. `photo.id` survives, so a cover set on an uploaded photo
  still points at it.
- **Its `name` and `parent` are the album's**, recorded when it was uploaded. An addition whose
  album is deleted before the merge — its folder removed on the laptop, say — is shown as that
  album, and the laptop pulls it as that album again: the photos may exist nowhere else.
- **The phone names nothing.** An addition's rows carry the camera's names exactly, clashes and
  all: only the laptop can see what the album's folder holds, so only the laptop makes a name
  unique (§7).

**What goes up, per asset** — the version the Photos app shows, since that is what the library
then keeps:

| asset | uploaded as |
|---|---|
| still | the edited full-size image when there is one, otherwise the original; `image_id` |
| RAW or ProRAW | its rendered JPEG/HEIC only — the RAW is neither uploaded nor archived |
| video | the original file in `video_id`, **no poster**: §7's pull archives `image_id` before `video_id`, so a poster would be archived in place of the video. The viewer shows the thumbnail until the laptop encodes it |
| Live Photo | the still in `image_id` *and* `live_still_id` (one object), the MOV in `live_video_id`. The edited pair when edited: an edit made through `PHLivePhotoEditingContext` re-renders both halves and they still pair — verified on a simulator, where the uploaded, inverted pair assembles in full |
| iCloud-only | downloaded during preparation |

**A row's filename is the camera's stem and the extension of the bytes sent** — `IMG_1234.heic`,
never an edit's `FullSizeRender.heic` — and a Live Photo's MOV is named beside it. A clash is left
as it is, for the laptop's claim to suffix (§7). The names are fixed during preparation, so the
shard written first is final.

**Date, location and size come from PhotoKit's record, and are provisional.** EXIF stays the
authority (§3): the laptop re-derives every row from the files when it encodes the album, so a
date or place edited only in Photos reverts then. What PhotoKit supplies is what the album shows
for the hour before that.

**Preparation is foreground work, one album at a time.** It is where iCloud-only assets are
fetched and every file is exported — background transfers need files on disk — and it pauses
while the app is in the background. Further uploads queue behind it; their transfers may overlap
the one before.

**An `uploading` album is shown by no reader**, on any device — its shard names objects that may
not exist yet. The uploading phone shows the sheet or pill instead. Once `uploaded` it appears as
any other album: the grid from the phone-built pack, the viewer fetching the full-quality files.

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

The pill and its sheet **take the bottom of the window rather than float over it**: the screen
above them shrinks, and the sheet stops at half the window with its rows scrolling inside. Drawn
over the screen — which is how it started — the pill covered whatever sat at a screen's bottom
edge, and the picker's own "Upload N selected" button was then unreachable for exactly as long as
an upload ran. A toast still floats, above the pill: it is transient, it never reflows a list, and
a tap anywhere on it dismisses it, so unlike the pill it can never strand a control beneath it.

This is the one HTTP path that does not go through Ktor, whose Darwin engine cannot drive a
background session — so it is a **`BackgroundUploader` port**, with a Kotlin/Native adapter
over `URLSession` on iOS and a plain foreground implementation everywhere else, one PUT at a
time. Its sibling is the **`Gallery` port**: PhotoKit on the phone, and on the desktop a
directory whose subfolders stand in for gallery albums — so the whole flow is driven and tested
on the harness (`--gallery`, `POST /upload/…`, `:tests:app`'s `UploadTest`).

**Each upload keeps its state on disk**, under `uploads/<upload-id>/`: the request, the
shard as written, every exported file, and how far it got — requested, prepared, written,
landed. **What finished transferring is never remembered; it is asked of the zone.** An object
counts once a HEAD returns its size, which is also the read-back verification deletion waits
for. A relaunch that missed every event therefore still knows exactly what landed.

**Failures name their status (§1).** A transfer that errors without a status, or lands at the
wrong size, is retried three times; one that answers 403 — a bad password or an expired URL —
stops the album with the status on the pill, and Retry signs every PUT again.

**Cancel is allowed, and deletes nothing in the zone** — the app never does (§7). The transfers
stop and the local state goes; a shard already written stays at `uploading`, shown by no reader,
until the CLI removes it once its URLs have expired.

**Resume.** The app persists an upload manifest, re-creates the background session with the same
identifier on launch, diffs `getAllTasks` against the manifest and re-queues what is missing —
silently, no prompt.

> A background session survives *system* termination: iOS keeps transferring and relaunches the
> app in the background. But a **user force-quit from the app switcher cancels all background
> transfers and iOS will not relaunch the app.** Without manifest reconciliation that upload
> would be stranded permanently.

**Delete-from-gallery is a checkbox in the album dialog**, decided up front, and **ticked by
default**: uploading from the phone is how its storage gets freed, and iOS's own alert still stands
between the box and the deletion. Unticking it keeps that one upload's photos; the choice is not
remembered. iOS always shows its own deletion confirmation — an app cannot delete library assets
silently — so there are necessarily two confirmations. Deletion runs only after the uploaded objects are read back and
verified.

**A gallery album chosen whole goes with its photos** — the same checkbox, worded "Delete the album
and its photos" when an album was chosen. It goes only when it holds nothing besides the uploaded
assets: a photo added to it after it was chosen keeps its album, and nothing that did not go up is
touched. The album is decided before the change, and the assets and the album are deleted in *one*
PhotoKit change, which iOS confirms with two alerts — one for the album, one for its photos — and
which applies whole or not at all. An album an app may not delete —
synced from a computer, or shared — is left without a word. The gallery album's id is part of the
upload's persisted request, so a resumed upload deletes it too; a request written before this
carries none and deletes the photos alone. On the desktop stand-in the album's folder goes once it
is empty.

> **Accepted risk.** Until the hourly laptop pull runs, the bucket copy is the *only* copy, and
> bunny.net has no versioning or undelete. Leaving that box ticked leaves a single unversioned
> copy for up to an hour. Verification protects against corruption, not against deletion.

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

**Deletion is the exception, and it is the opposite shape.** Measured while emptying the live
zone: a single DELETE costs **~1.4 s**, and 64 in flight sustained **~45/s**. It carries no
bytes, so it never touches the upstream link the uploads are limited by — it is round-trip
latency, and round trips overlap.

> The number matters because of what §5 made routine. A profile bump re-derives every album,
> orphaning ~34,000 blobs at once; serially that is about **thirteen hours** of deleting against
> a three-hour import. `deleteJobs` therefore defaults to 64 where `uploadJobs` defaults to 1,
> and the two carry opposite reasoning for it.

Design consequence: **the ingest tool's parallelism is for derivative generation (CPU-bound),
not for uploading.** Overlap encoding with a small number of upload connections; adding upload
workers buys nothing — but do not carry that conclusion over to deletion. Measure the actual
link before planning a bulk import — see `INGEST.md`.

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
It must render an album with **zero photos**: emptying a directory leaves one (§7) — which also
means offering it no cache controls, since it has nothing to fetch and nothing to remove.

> **E.2 is the download queue** (§6): the ladder, the three worker roles, the per-album strip on
> the album list, and Settings losing its album list. It is built and running on the harness
> against the live zone. Two pieces of it are iOS-only and still unbuilt: tier 5's
> background `URLSession`, and persisting the requested-album set across a kill.
>
> **E.3 is the phone.** The app builds for iOS, plays videos and Live Photos in the platform's
> own views, keeps the key in the Keychain, and runs the desktop harness's scenarios on a
> simulator on every pull request. It has been installed and set up on a real phone. Still owed:
> share from the viewer, and §10's grid unknown, which only a device answers.

**F · Map** = B — parallel with E. Both maps, the shared tier's projection and clustering, the
remembered camera and the control routes, with MapLibre in the desktop window and on the phone
and the stand-in everywhere headless. Still owed: the map on a real device, the only place that
shows whether the overlay keeps up with the native map while panning.

**G · iOS upload** = A+B.

> **G runs on the harness and on a simulator.** The whole flow — picker, album dialog,
> preparation, shard first, transfer with read-back, landing, gallery deletion, cancel — runs on
> the desktop against S3Mock with a folder standing in for the library (`:tests:app`'s
> `UploadTest`), and every reader skips an `uploading` shard. On a simulator `:tests:ios`'s
> `UploadTest` uploads the device's own library through PhotoKit and a background `URLSession`,
> and asserts the zone: an `uploaded` shard, a row per asset, every object at the size its row
> names, and a seeded HEIC byte for byte. Deleting from the library is driven there too, with iOS's
> confirmation tapped by `PhotosUITests`' `SystemAlerts`: only once the album has landed, and only
> the assets that went up. And §8's last open question is answered on the same simulator: a Live
> Photo seeded and edited through `PHLivePhotoEditingContext` — still and video both re-rendered —
> uploads as its edit and still assembles in full, against an unedited control.
>
> What the simulator taught. `simctl privacy grant photos` records a system-set grant that iOS 26
> still prompts for, so the harness rewrites it as the user's choice. A still is exported by
> resource, byte for byte: matching `PHImageManager`'s type identifier sent a HEIF labelled
> `public.heif` through the render path, and it went up as a JPEG. And editing a Live Photo needs
> the MOV's timed `com.apple.quicktime.still-image-time` track, which assembling one does not: the
> fixture's generated MOV has none, so the seeder re-writes it as an iPhone would before editing —
> without it the save fails with `PHPhotosErrorDomain` -1 and "invalid or missing image display
> time".

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

Verified on macOS, building the shared tier for the phone:

| check | result |
|---|---|
| §3's catalog suites on `iosSimulatorArm64`, against the **platform SQLite** | **250 tests, all green** — §10's open question, answered |
| `:app:domain`'s model, scheduler and cache on the same target | **41 tests, all green** |
| the SigV4 vectors under the simulator | **green**, once the fixture path is forwarded as `SIMCTL_CHILD_*` |

> `simctl spawn` passes on only the variables named `SIMCTL_CHILD_*`, stripping the prefix as it
> does, so a Gradle `environment()` reaches the client and not the test. Measured, as six vector
> failures on an otherwise green run.

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
dependency stack is verified on Linux. One thing still needs Apple hardware:

- **whether a Compose lazy grid sustains §6's prefetch at scale on a device.** The tile is a
  13 KB thumbnail from the packed blob, which is the cheap case; what the harness cannot answer
  is the 2-column pinch density, where the grid decodes an already-cached **424 KiB viewing
  image** per tile at scroll speed. It fetches nothing — the bytes are on disk or the thumb is
  used — but the decode is real work, which is why the tile is decoded *to tile size* rather
  than to 3200px: a full decode is ~30 MB of pixels, and a shallow cache of those would be
  hundreds of megabytes for pixels no tile displays.
  > It cannot be answered yet for a second reason: **pinch-to-density is not built.** §6 says
  > that interactive layout transition has to be written by hand, and the app ships one fixed
  > density until it is. So this stays open for as long as the thing it asks about does not
  > exist, rather than waiting on a device.

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
