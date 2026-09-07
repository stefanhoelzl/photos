# photos

Ingest CLI and iOS app for a personal photo library on bunny.net storage.
The design lives in [`DESIGN.md`](DESIGN.md); this file covers building it.

## Layout

One package, one target per milestone, so §10's dependency arrows are enforced
by the compiler rather than by discipline.

| target | milestone | status |
|---|---|---|
| `PhotosStorage` | **A** — SigV4 signer + S3 client | done |
| `CSQLite` | vendored SQLite amalgamation (see its `PROVENANCE.md`) | — |
| `PhotosCatalog` | **B** — shards, merged DB, sync loop, EXIF mapping | done |
| `PhotosPipeline` | C — thumbs, previews, transcode | not started |
| `photos` | D — ingest CLI | not started |
| `ios/` | E–G — the app | blocked on a Mac |

## Toolchain

Swift 6.3 via [swiftly](https://swiftlang.github.io/swiftly/):

```sh
curl -O https://download.swift.org/swiftly/linux/swiftly-x86_64.tar.gz
tar zxf swiftly-x86_64.tar.gz && ./swiftly init
swiftly install 6.3.3
```

## Build and test

```sh
swift build
swift test
```

`swift test` starts adobe/S3Mock itself, on a free port, and stops it afterwards.
The jar is fetched into the gitignored `.tools/` on first run; it needs `java`.
Without java the round-trip tests skip and the rest still run.

## Static Linux binary

§7 wants one self-contained binary with no Swift installation on the target.

```sh
swift sdk install <static-linux-sdk-url>
swift build -c release --swift-sdk x86_64-swift-linux-musl
```

Verified for milestone A: swift-crypto, `FoundationXML` (libxml2) and
`FoundationNetworking` (curl + TLS) all link statically, and the resulting ELF has
no dynamic linker and makes real HTTPS requests. 63 MB stripped.

## What proves what

- **The signer** is proven by the vendored AWS SigV4 vector suite
  (`Tests/PhotosStorageTests/Fixtures/sigv4`), compared stage by stage. S3Mock does
  not validate signatures, so nothing else covers this.
- **Everything around it** — retry, error mapping, LIST paging, key encoding — is
  covered offline through the injectable `HTTPTransport`.
- **Real HTTP behaviour** — paging, range GET, multipart, `If-Match` 412 — is
  covered by round-trips against S3Mock.
- **bunny.net-specific behaviour** is taken from §10's verification table, which was
  established against the live zone. There are no live tests.
- **The catalog** is proven by generated fixtures — shards built by B's own writer and read
  back — plus a keyed stub transport for the sync loop. No committed `.db` files, so nothing
  goes stale. The one case a round trip structurally cannot reach, a shard written by a
  *newer* schema, is forged in `Fixture.futureShard` rather than shipped as a binary.

### Milestone B

SQLite is **vendored, not linked from the system**: the Static Linux SDK ships no static musl
`libsqlite3`, and vendoring also means the phone and the laptop run byte-identical SQLite, so
a version skew cannot make two devices disagree about a shard.

- **Shards** are built and read as bytes via `sqlite3_serialize`/`deserialize`, so a shard
  never touches the filesystem on its way to or from the zone.
- **The sync loop lives here**, not in the CLI and the app separately — §4 implemented twice
  is exactly the laptop/phone disagreement §7 says the shared package exists to prevent.
- **EXIF is split**: `ImageBackend` (implemented in C) extracts raw tags, `ExifMapper` (here)
  interprets them. Interpretation is where a disagreement would corrupt the catalog, so it is
  shared and has no platform dependency — B's tests feed literal tag dictionaries and need no
  imaging library at all.

Measured on this machine, at the library's real scale (337 albums, 34,607 photo rows):

| | |
|---|---|
| full merged rebuild | **0.39 s** — §4 budgets 1–3 s |
| merged DB on disk | **13.1 MB** (see DESIGN §3 on why this is 3× the old prototype) |
| opening the largest grid (1,755 photos) | under 10 ms, index-ordered |
| `swift build --swift-sdk x86_64-swift-linux-musl` | vendored SQLite compiles clean |

`RebuildScaleTests` is the regression bar for the first three; its budgets are deliberately
loose, so a failure means something structural changed rather than that CI was busy. It also
asserts via `EXPLAIN QUERY PLAN` that the grid query still uses `ix_photo_album` — if the
query and that expression index ever state §3's ordering rule differently, the planner
silently stops using the index and only a plan check notices.

`S3Client.payloadSigning` defaults to `.unsigned`: a one-off probe against the live
zone confirmed bunny.net accepts `UNSIGNED-PAYLOAD` on header-authenticated PUTs, so
file uploads skip a full read pass over every byte. In-memory bodies are still hashed
for real — they are small. `.signed` remains available.
