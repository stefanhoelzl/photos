# photos

Ingest CLI and iOS app for a personal photo library on bunny.net storage.
The design lives in [`DESIGN.md`](DESIGN.md); this file covers building it.

## Layout

Ports and adapters (DESIGN §7). The domain holds every rule that decides what the zone should
contain; an adapter is the only place that knows how a platform does something; and `:app:cli`
is the only place that knows which adapter satisfies which port.

| module | what |
|---|---|
| `:domain` | the shared tier: EXIF interpretation, the S3 client and signer, the catalog and its sync loop, the library walk, the ingest rules |
| `:adapter:linux` | the imaging backend over `native/CImaging`, the Secret Service client over libdbus, the flock run lock, XDG paths |
| `:app:cli` | the shipped `photos-cli`: argument parsing, the composition root, exit codes |
| `:tests:fixtures` | synthetic media — JPEG, HEIC, video, CR2, PNG, EXIF — generated, never committed |
| `:tests:cli` | the end-to-end suite: declares a library and a zone, runs the *shipped* binary, asserts both |

Not modules, and not Kotlin:

| | |
|---|---|
| `native/CImaging` | ~2,200 lines of our own C. libjpeg reports errors through `setjmp`/`longjmp`, which no managed runtime can safely be on the far end of, so the error handler has to live in C — and once it does, one flat API over five libraries costs nothing more. Bound by cinterop with no glue layer. |
| `testdata/sigv4` | AWS's published SigV4 vectors, 38 cases. The only thing that proves the signer. |

## Toolchain

Nothing to install. The Gradle wrapper fetches Gradle, and Kotlin/Native fetches its own
compiler and its bundled gcc 8.3.0 / glibc 2.19 toolchain on first use.

```sh
./gradlew build
```

## Running it

```sh
cp photosignore.example ~/Pictures/Albums/.photosignore    # required: see DESIGN §7

cd ~/Pictures/Albums
photos-cli sync --dry-run    # always, after any reorganisation
photos-cli sync
```

The library root is the working directory, or `--library-path`. Run it somewhere that is not
a library and it aborts on the missing `.photosignore` rather than concluding every album was
deleted.

**Production** takes both credentials from the desktop keyring — two items under one service,
stored and removed by the tool itself:

```sh
photos-cli login     # prompts for the endpoint and the password, stores both
photos-cli logout    # removes both
```

The keyring is read and written in-process over D-Bus, so nothing is shelled out to and
nothing needs to be on `PATH`. Items stored earlier with `secret-tool` are found unchanged.

**Development** overrides them from Proton Pass — `.secrets.yaml` maps them, `secrets-env`
injects them:

```sh
secrets-env photos-cli sync --dry-run
```

The environment beats the keyring when set, and a run that uses it names the variables on
stderr, so a stale value cannot quietly point you at the wrong zone.

`sync` is the only verb. The library says everything: a new folder is a new album, a deleted
file is a deleted photo, `rm -rf` on an album deletes it from the zone. There is no
confirmation step, and no run happens at all without a readable
`$LIBRARY_ROOT/.photosignore` — that file is what proves the directory is the library rather
than an unmounted mount point.

Exit codes: `0` clean · `1` finished with failures · `2` usage · `3` aborted before writing
anything · `75` deferred — another sync holds the lock, the storage zone cannot be reached at
all (no DNS, no route, a refused connection: a laptop asleep or away from network), or the
keyring cannot be reached: no session bus yet, nothing answering on it, or a collection still
locked because nobody has logged in. Both of the last two are *not now* rather than *not
working*. A keyring that **does** answer and holds no such item is a real error, not a
deferral, and so is a zone that answers `403` — both say so.

Only one sync runs at a time (an `flock` in the cache directory), which matters because the
first import outlasts the hour between timer firings. A run prints what it intends, then a
line per album as it commits; on a terminal a counter on stderr shows files, bytes, rate and
an estimate.

## Build and test

```sh
Scripts/build-native.sh          # once: imaging stack, sqlite, openssl, curl, dbus
./gradlew build                  # 314 tests
./gradlew :app:cli:linkReleaseExecutableLinuxX64
./gradlew :tests:cli:e2e         # 14 scenarios against the shipped binary — opt-in
```

`:tests:cli` is **not** part of `build`, which compiles its scenarios but does not run them —
so a rename in `:domain` still breaks the build immediately, while the inner loop stays fast.
Run it after touching the pipeline or ingest, and before tagging. It declares a library tree and
a zone, forks `photos-cli` against S3Mock, and asserts the library and zone that result: the
library open-world, naming only what matters, and the zone exhaustively, so an unexpected row
fails without anyone having predicted it.

The native stack **must** be built with Kotlin/Native's own bundled toolchain, which is what
`build-native.sh` does and why it takes no argument any more. Built with the host's gcc instead,
the prefix pulls in symbols konan's glibc-2.19 sysroot has never had — libmvec, `__isoc23_strtol`,
`__libc_single_threaded`, the modern libstdc++ `__cxx11` ABI — and the link fails. Building it
that way is also what gives the shipped binary its low glibc floor.
`./gradlew checkNativePrefix` says whether the prefix is there.

The round-trip tests need `java`: the build fetches adobe/S3Mock into the gitignored `.tools/`,
starts it on a free port with the bucket already declared, and stops it afterwards. Without java
they skip and everything else still runs. The keyring tests do the same with `dbus-daemon`, each
getting a private bus with a stub Secret Service on it — so the real wire protocol is exercised
without ever touching your actual credentials.

## The shipped binary

**26.7 MiB stripped, glibc floor `GLIBC_2.17`** — older than any desktop distribution still in
use. libheif, x265, ffmpeg, libcurl, OpenSSL, SQLite and libstdc++ are all linked in; what
remains dynamic is base-system only:

```
libc libm libpthread libdl librt libz libgcc_s
```

The link passes `--as-needed`. Without it Kotlin/Native records a `DT_NEEDED` for every library
on its default link line, used or not — and one of those, `libcrypt.so.1`, does not exist on a
current Fedora, which moved `crypt` to libxcrypt and ships `libcrypt.so.2`. The binary refused
to start there while linking cleanly and passing every test, because the loader is the only
thing that ever reads that list.

It shells out to nothing. The desktop keyring is reached in-process through a statically linked
libdbus-1 — see DESIGN §1 for why libsecret, and therefore glib, is still refused.

## What proves what

- **The signer** is proven by the vendored AWS SigV4 vector suite
  (`testdata/sigv4`), compared stage by stage. S3Mock does
  not validate signatures, so nothing else covers this.
- **Everything around it** — retry, error mapping, LIST paging, key encoding — is
  covered offline through Ktor's `MockEngine` — the real client, scripted, so a mistake in how
  Ktor itself is used still surfaces.
- **Real HTTP behaviour** — paging, range GET, multipart, `If-Match` 412 — is
  covered by round-trips against S3Mock.
- **bunny.net-specific behaviour** is taken from §10's verification table, which was
  established against the live zone. There are no live tests.
- **The catalog** is proven by generated fixtures — shards built by B's own writer and read
  back — plus a keyed stub transport for the sync loop. No committed `.db` files, so nothing
  goes stale. The one case a round trip structurally cannot reach, a shard written by a
  *newer* schema, is forged in `Fixture.futureShard` rather than shipped as a binary.
- **The ingest rules** — every rule that can lose a photograph — are proven
  against real directories and hand-built shards, with no network and no
  encoder: a `stat` decides existence, `.photosignore` decides uploads, a missing directory
  deletes, an emptied one does not.
- **The full cycle** runs against `FakeZone`, an in-process zone with real `If-Match` and a
  clock a test can set. Deliberately not S3Mock: the wire is A's business and already covered
  above, whereas the sweep's age floor can only be asserted by a test that owns the clock.

### What the design turns on

The claims below are the ones a change is most likely to break quietly. DESIGN is the authority;
these are the short forms.

- **The signer is the one thing a server cannot check for us.** S3Mock does not validate
  signatures, so the vendored AWS vectors in `testdata/sigv4` are the whole proof — compared
  stage by stage, not just pass/fail, because a mismatch has to name where it diverged.
- **A truncated LIST must not parse as an empty bucket.** LIST *is* the sync mechanism and a
  missing key means "album deleted", so a connection dropped mid-response would otherwise drop
  the entire catalog. The parser is real, but the guard is ours: a `ListBucketResult` that was
  both opened *and* closed, because the parser streams and can yield objects before it throws.
- **Existence is a `stat`, never the walk.** `.photosignore` governs uploads and nothing else.
  Reading it the other way round would let a broadened rule delete photographs.
- **A missing directory deletes; an emptied one does not.** `rm album/*` leaves the album with
  zero photos, which is a thing the app must render (§6). `rm -rf album` removes it.
- **`.photosignore` is the library marker.** No readable file, no run — an unmounted disk is a
  bare mount point and a mistyped root is somebody else's directory, and neither has one. Note
  the deliberate asymmetry: *absent* aborts, while *empty* means a library that wants no
  exclusions. The walk returns no exclusions for an absent file; the refusal lives in ingest.
- **CR2 is carved, not developed.** Every CR2 embeds a full-resolution camera JPEG at IFD0, so
  the pipeline extracts it and grafts the RAW's EXIF on, rather than demosaicing.
- **A C shim is not stylistic.** libjpeg reports errors through `setjmp`/`longjmp`, which no
  managed runtime can safely be on the far end of, so the error handler has to live in C.
- **Fixtures are generated, never committed.** The library is personal data, and a committed
  corpus goes stale in a way generated inputs cannot.
- **The SQL floor is enforced, not asserted.** The query dialect is pinned to SQLite 3.24 — the
  oldest release with `ON CONFLICT … DO UPDATE`, the newest syntax anything here uses — so SQL
  that would fail on an older device's library fails the build instead.
