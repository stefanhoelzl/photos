# Native libraries — what is built, and why

Mostly milestone C's imaging stack, plus the two libraries the keyring client needs (§1).
`build-native.sh` fetches and builds these from pinned source releases into
`.tools/native/<host|musl>/`, which is gitignored. Nothing is installed system-wide and
nothing needs `sudo`; this machine has none.

Two prefixes exist because **the Static Linux SDK ships neither `XCTest` nor `Testing`**.
`swift test` cannot be built for musl at all, so tests can only run against host glibc — while
§7's deliverable is the musl binary. One recipe, two prefixes, is what keeps the tested
configuration and the shipped one the same code rather than merely similar.

**Nothing third-party is committed to this repository.** Three things are easily confused:

| | what it is | in git? |
|---|---|---|
| `Sources/CImaging` | **our own C**, ~2,200 lines — the shim over the libraries below | yes, it is our source |
| `Sources/CNativeImaging` | a `systemLibrary` target carrying pkg-config flags; one modulemap and an empty header, no code | yes, ~300 bytes |
| `Sources/CDBus` | the same, for libdbus-1 — but its modulemap *does* expose `<dbus/dbus.h>`, because the Secret Service client is Swift written against it directly | yes, ~600 bytes |
| the libraries below | fetched as pinned source tarballs into gitignored `.tools/native/src` (~307 MB) and built here | **no** |

That last row is the difference from `Sources/CSQLite`, which *is* vendored: SQLite is a single
270k-line amalgamation, and milestone B committed it so the phone and the laptop run
byte-identical SQLite. Vendoring seven imaging libraries instead would put 307 MB of other
people's source in a Swift package, which is why this script exists.

| library | version | why it is here |
|---|---|---|
| **libjpeg-turbo** | 3.1.0 | 94% of the library is JPEG. Built for the *classic* libjpeg API, not TurboJPEG, because the pipeline depends on `scale_num`/`scale_denom` shrink-on-load — that is what bounds decode memory on the 164 MP panorama and makes 34k photos cheap. |
| **libheif** | 1.19.8 | HEIC decode and encode. `ENABLE_PLUGIN_LOADING=OFF`: libheif's default builds x265 and libde265 as `dlopen`-ed plugins, which a single static binary cannot use. |
| **libde265** | 1.0.15 | libheif's HEVC decoder. 1,531 HEICs in the library. |
| **x265** | 3.6 | HEVC encoder, for **both** previews and video. Choosing HEVC for video (decision 10) is what let libx264 out of the stack entirely — one video encoder instead of two. |
| **ffmpeg** | 7.1.1 | Video decode/transcode, PNG and TIFF decode, and swscale — the single resampler for stills and video frames alike. Configured `--disable-everything` and then enumerated; see below. |
| **lcms2** | 2.16 | The one colour conversion the pipeline performs: thumbnails to sRGB. ~1 MB of C, no dependencies. |
| **libexif** | 0.6.24 | EXIF extraction. Apple's maker note is walked by hand on top of the raw bytes libexif hands back — a bounded read of one documented IFD, which is what keeps exiv2 (C++, GPL, another cross-build) out of the stack for the sake of 187 Live Photos. |
| **libdbus-1** | 1.14.10 | The Secret Service client (§1) — the desktop keyring, read and written in-process instead of by exec'ing `secret-tool`, which is what makes the shipped binary self-sufficient. The reference D-Bus implementation, dependent on nothing but libc, and **not** glib: that is libsecret, which is what §7 refused. 204 KB in the final binary after `--gc-sections`. 1.14.x rather than 1.16, which dropped autotools for meson. Only the `dbus/` subdirectory is built — the daemon and the command-line tools are precisely what is being removed. |
| **expat** | 2.6.4 | Not wanted for itself: dbus's `configure` requires an XML parser even when only the client library is being built, and refuses to proceed without one. No dependencies of its own, and nothing in our code includes it. |
| **cmake** | 3.31.6 | Build tool, fetched as a binary. |
| **nasm** | 2.16.03 | Build tool, built from source. Needed for libjpeg-turbo's SIMD and x265's assembly. |

Not here, and deliberately:

- **LibRaw** — every CR2 embeds a full-resolution camera JPEG at IFD0, so "developing" a raw
  is a byte-range copy. No demosaic, no development parameters, no C++ dependency.
- **libx264** — decision 10 made HEVC the video codec, and x265 was already being built.
- **exiv2** — see libexif above.
- **libpng / libtiff** — 36 files in the whole library. ffmpeg is linked for video regardless
  and decodes both.
- **libvips** — its tidier API buys nothing once a C shim exists anyway (libjpeg's
  `setjmp`/`longjmp` error handling forces one), and it would drag a glib stack into the musl
  cross-build.
- **libsecret** — the keyring library, as opposed to libdbus the *bus* library. It would drag
  meson, libffi, PCRE2, proxy-libintl, libgcrypt and libgpg-error in for about 8 MB, and glib
  `dlopen`s its GIO modules, which always fails in a static musl binary. The Secret Service is
  a D-Bus protocol; speaking it needs libdbus and about 300 lines of Swift.
- **`KeyringAccess` / `wendylabsinc/dbus` (Swift)** — the right shape, Apache-2.0, and pure
  Swift, but built on SwiftNIO and arriving with CryptoSwift and swift-nio-ssl. Measured
  against the Static Linux SDK: +8.5 MB and +18 packages, including nio-http2,
  swift-certificates and service-lifecycle, plus a second BoringSSL beside swift-crypto's — to
  read one password once per run.

## ffmpeg's configuration

`--disable-everything`, then exactly what the measured inventory needs: demuxers for
mov/mp4/avi/mpegps/matroska; decoders for h264, hevc, mjpeg, mpeg1/2video, mpeg4, msmpeg4v2/v3,
h263 — one per codec actually present in the 550 video files — plus png/tiff/bmp/gif for the
still strays and aac/mp3/pcm for audio; `libx265` and `aac` encoders; the mp4 muxer; and the
scale/yadif/transpose/format filters.

**`--enable-zlib` is not optional.** ffmpeg builds its PNG decoder only when zlib is present,
and `--disable-autodetect` means it has to be asked for by name. Without it the PNG decoder is
dropped silently and every PNG fails with "could not find codec parameters" — which is exactly
how it was found. Both sysroots already ship `libz.a`.

Result: **70.6 MB stripped**, against milestone A's 63 MB. The whole imaging stack costs about
8 MB, which is what the enumerated build buys over a default one.

## Concurrency and memory

x265 opens a full thread pool per encoder by default. With one pipeline worker per core that
is a second, hidden pool per file — measured at 1100% CPU and 7.2 GB resident on a 16-core
machine. `pi_encode_heic` and `pi_video_transcode` therefore take a thread bound, and
`Pipeline` defaults it to 1: decision 13 puts scheduling with the caller, and an encoder that
schedules for itself contradicts that.

Bounding the pool does not make encoding cheap. x265 still holds lookahead and reference
buffers per encoder, so **budget roughly 400 MB per worker** — 16 workers plateau at about
6.4 GB resident, stable rather than growing. That is the number D needs to size its pool
against, and it is why `photos-scan` takes `--jobs`.

## Licensing

**x265 is GPLv2**, and it is statically linked. Personal use is not distribution, so nothing
binds today. It binds the day a release artifact is published: the whole binary is then GPLv2
and its source must be offered. Recorded here as well as in DESIGN §7 because this is the file
where the linking is actually decided.

libheif is LGPL; libjpeg-turbo, lcms2 and libde265 are permissive; ffmpeg is LGPL except that
`--enable-gpl` (required for libx265) makes it GPL. So the binary is GPLv2 either way, and
decision 10 changed nothing there — libx264 is GPL too.

## The link line

`photos-native.pc` spells out the whole static closure in `Libs:` rather than `Libs.private:`,
because SwiftPM calls `pkg-config --libs` without `--static`. The archives are wrapped in
`-Wl,--start-group … -Wl,--end-group`: libavcodec calls into x265, libheif calls into both
x265 and libde265, and a static linker resolves strictly left to right. Grouping them is what
stops the correct order from being something anyone has to know.

Writing the `.pc` ourselves is also what keeps `unsafeFlags` out of `Package.swift` — a package
using them cannot be consumed as a dependency, and the iOS app will consume this one.

`photos-dbus.pc` is separate rather than folded in, because it is a separate concern:
`PhotosIngest` links the keyring client and `PhotosPipeline` links the imaging stack, and
neither should drag the other in merely because one script builds both. It carries two include
directories, since dbus generates `dbus-arch-deps.h` per architecture and installs it under
`libdir` rather than beside its other headers.
