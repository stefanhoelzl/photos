# photos

Ingest CLI and iOS app for a personal photo library on bunny.net storage.
The design lives in [`DESIGN.md`](DESIGN.md); this file covers building it.

## Layout

One package, one target per milestone, so §10's dependency arrows are enforced
by the compiler rather than by discipline.

| target | milestone | status |
|---|---|---|
| `PhotosStorage` | **A** — SigV4 signer + S3 client | done |
| `PhotosCatalog` | B — shards, merged DB, LIST-diff | not started |
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

### Still open

`S3Client.payloadSigning` defaults to `.signed`, which every S3 implementation
accepts. `.unsigned` would save a full read pass over every uploaded byte, but only
if bunny.net accepts `UNSIGNED-PAYLOAD` on header-authenticated PUTs — which one
manual probe against the real zone has yet to settle.
