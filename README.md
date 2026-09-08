# photos

Ingest CLI and iOS app for a personal photo library on bunny.net storage.
The design lives in [`DESIGN.md`](DESIGN.md); this file covers building it.

## Layout

One package, one target per milestone, so §10's dependency arrows are enforced
by the compiler rather than by discipline.

| target | milestone | status |
|---|---|---|
| `PhotosCore` | shared contracts: EXIF vocabulary + mapper, `PhotoRow`, derivative specs | done |
| `PhotosStorage` | **A** — SigV4 signer + S3 client | done |
| `CSQLite` | vendored SQLite amalgamation (see its `PROVENANCE.md`) | — |
| `PhotosCatalog` | **B** — shards, merged DB, sync loop | done |
| `CNativeImaging` / `CImaging` | the native stack and the C shim over it | done |
| `PhotosPipeline` | **C** — thumbs, previews, transcode, CR2, pairing | done |
| `PhotosLibrary` | walking `$LIBRARY_ROOT`, and `.photosignore` | done |
| `photos-scan` | C's acceptance harness; D absorbs it as `photos scan` | done |
| `native-smoke` | the only thing that can exercise the musl build | done |
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

Milestone C links a native imaging stack, so builds go through a wrapper that points
pkg-config at the right prefix:

```sh
Scripts/build-native.sh host          # once: libjpeg-turbo, libheif, x265, ffmpeg, lcms2, libexif
Scripts/with-native.sh host swift build
Scripts/with-native.sh host swift test
```

The stack is built from pinned source releases into the gitignored `.tools/native/<triple>/`;
`Scripts/PROVENANCE.md` records each library, its version, and why it is there. Nothing is
installed system-wide and nothing needs `sudo`.

`swift test` starts adobe/S3Mock itself, on a free port, and stops it afterwards.
The jar is fetched into the gitignored `.tools/` on first run; it needs `java`.
Without java the round-trip tests skip and the rest still run.

## Static Linux binary

§7 wants one self-contained binary with no Swift installation on the target.

```sh
swift sdk install <static-linux-sdk-url>
Scripts/build-native.sh musl
Scripts/with-native.sh musl swift build -c release --swift-sdk x86_64-swift-linux-musl
```

Verified for milestone A: swift-crypto, `FoundationXML` (libxml2) and
`FoundationNetworking` (curl + TLS) all link statically, and the resulting ELF has
no dynamic linker and makes real HTTPS requests. 63 MB stripped.

Verified for milestone C: the whole imaging stack cross-compiles against the SDK's musl
sysroot — libheif and x265 included, both C++ — and the resulting static binary decodes,
encodes and transcodes. **70.6 MB stripped**, so the imaging stack costs about 8 MB over A.

musl is not a preference. Swift ships exactly one fully-static target, and glibc resolves DNS
through NSS, which `dlopen`s at runtime — so a statically linked glibc binary would fail at
the one thing this tool does on every run.

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

### Milestone C

The native stack is built from source into two prefixes rather than installed: `swift test`
can only ever run against host glibc, because **the Static Linux SDK ships neither `XCTest`
nor swift-testing**, while §7's deliverable is the musl binary. One recipe, two prefixes, is
what keeps the tested configuration and the shipped one the same code.

That gap is why `native-smoke` exists — a plain executable, not a test target, so it builds
under musl. It runs the whole pipeline over generated inputs and exits nonzero. It is the only
thing that exercises the configuration that actually ships.

- **CR2 is carved, not developed.** Every CR2 embeds a full-resolution camera JPEG at IFD0, so
  the derivative §5 asks for already exists inside the file. No LibRaw, no demosaic, no
  development parameters — and the 139 files cost ~0.35 GB rather than ~1.2 GB.
- **A C shim is not stylistic.** libjpeg reports errors through `setjmp`/`longjmp`, which Swift
  cannot safely be on the far end of, so an error handler has to live in C regardless. Once it
  does, one flat API over five libraries costs nothing more — which is also why libvips's
  tidier API bought nothing worth a glib stack under musl.
- **Fixtures are generated, never committed.** The library is personal data, and a committed
  corpus goes stale invisibly. `Synthetic` builds JPEGs (with or without an orientation tag),
  HEICs, PNGs with alpha, CR2-shaped TIFFs and short HEVC clips carrying a
  `content.identifier` — the same posture as B's `Fixture.futureShard`.

Three bugs the tests caught that review had not:

| | |
|---|---|
| **shrink-on-load leaked into the catalog** | `PhotoRow.width` reported the decoder's buffer (2250) rather than the photograph (3000), because libjpeg had scaled 6/8 on the way in. `pi_image` now carries the source's own dimensions separately. |
| **JPEG orientation was never applied** | libjpeg does not rotate. §3 promises stored dimensions are already rotated — that was simply false for 94% of the library, and no synthetic fixture had an orientation tag to notice with. |
| **video rotation went the wrong way** | `av_display_rotation_get` returns the *counter-clockwise* angle, so a portrait iPhone video reports 270 where its `tkhd` matrix reads 90. Rotating the wrong way still swaps the dimensions, so a geometry-only assertion passes happily. |
| **HEIF EXIF returned nothing, for every HEIC** | A HEIF Exif block is a 4-byte offset, then the payload. The offset locates the TIFF header *inside* that payload; libexif wants the payload including its `Exif\0\0` introducer, and handed a bare `MM\0*` returns an *empty* `ExifData` rather than an error. Cost: all 1,531 HEICs ingested with no date and no GPS, and all 187 Live Photos unpaired. |

The last one is why `OrientationTests` checks where a corner pixel *lands*, not just what the
dimensions became. The HEIF one is worse: it was **structurally untestable**, because
`pi_encode_heic` writes pixels and a colour profile but no metadata, so every synthetic HEIC
was metadata-free and a reader returning zero tags for every file passed the whole suite.
`Synthetic.heic(at:width:height:orientation:model:)` now writes a HEIC that carries EXIF, and
`ExifReadingTests` was confirmed to fail against the original code before being kept.

Acceptance, run over the whole `Kalifornien` container — 4,759 real files, 17 albums, all 139
CR2s, 89 videos — **0 failures, 0 skipped**:

| | measured | INGEST.md |
|---|---|---|
| thumbnail, average | 10.46 KB | 11.6 KB |
| preview, average | 271 KB | 370 KB |
| thumbnails, projected | 0.36 GB | 0.40 GB |
| previews, projected | 9.30 GB | 12.35 GB |
| carved CR2 originals | 0.23 GB | ~1.2 GB |

Throughput is the one figure that came out *worse* than recorded: **2.7 s/photo serial, 0.43 s
wall at 16 workers**, against §7's 0.79 s/photo — about 3.4× optimistic. It changes nothing
operationally, because §9's upload takes 17–22 h and encoding overlaps it, so ~4 h of encoding
is still nowhere near the constraint. DESIGN §7 and INGEST §3 now carry the measured numbers.

`S3Client.payloadSigning` defaults to `.unsigned`: a one-off probe against the live
zone confirmed bunny.net accepts `UNSIGNED-PAYLOAD` on header-authenticated PUTs, so
file uploads skip a full read pass over every byte. In-memory bodies are still hashed
for real — they are small. `.signed` remains available.
