#!/usr/bin/env bash
# Builds milestone C's native imaging stack into a prefix SwiftPM can point at.
#
#     Scripts/build-native.sh host      -> .tools/native/host   (glibc; what `swift test` links)
#     Scripts/build-native.sh musl      -> .tools/native/musl   (what the release binary links)
#
# Two prefixes are not a preference. The Static Linux SDK ships no XCTest and no
# swift-testing, so tests can only ever run on the host -- while §7's deliverable is the
# musl binary. Building both from one pinned recipe is what keeps the tested configuration
# and the shipped one the same code.
#
# Everything lands in .tools/ (gitignored) and nothing is installed system-wide; this
# machine has no sudo. Versions and their reasons live in PROVENANCE.md beside this script.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
SRC="$TOOLS/native/src"
BIN="$TOOLS/bin"

TARGET="${1:-}"
case "$TARGET" in
    host|musl|konan) ;;
    *) echo "usage: $0 <host|musl>" >&2; exit 2 ;;
esac

PREFIX="$TOOLS/native/$TARGET"
BUILD="$TOOLS/native/build-$TARGET"
JOBS="$(nproc)"

mkdir -p "$SRC" "$BIN" "$PREFIX" "$BUILD"

# ---------------------------------------------------------------- pinned versions
CMAKE_V=3.31.6
NASM_V=2.16.03
JPEG_V=3.1.0
LCMS_V=2.16
EXIF_V=0.6.24
DE265_V=1.0.15
X265_V=3.6
HEIF_V=1.19.8
FFMPEG_V=7.1.1
EXPAT_V=2.6.4
DBUS_V=1.14.10
SQLITE_V=3530400
SQLITE_YEAR=2026
OPENSSL_V=3.0.16
CURL_V=8.11.1

log() { printf '\n\033[1m== %s\033[0m\n' "$*" >&2; }
have() { [ -f "$PREFIX/.stamp/$1" ]; }
stamp() { mkdir -p "$PREFIX/.stamp"; touch "$PREFIX/.stamp/$1"; }

fetch() { # fetch <url> <file>
    local url="$1" out="$SRC/$2"
    [ -f "$out" ] && { echo "$out"; return; }
    echo "fetching $2..." >&2
    curl -fsSL -o "$out.partial" "$url"
    mv "$out.partial" "$out"
    echo "$out"
}

unpack() { # unpack <tarball> <expected-dir>
    local tb="$1" dir="$SRC/$2"
    [ -d "$dir" ] && { echo "$dir"; return; }
    tar -C "$SRC" -xf "$tb"
    echo "$dir"
}

# ---------------------------------------------------------------- build tools (host-only)
# nasm and cmake are tools, not dependencies: they always run on this machine regardless of
# which target we are producing, so they are built/fetched once and shared.

if [ ! -x "$BIN/cmake" ]; then
    log "cmake $CMAKE_V"
    tb=$(fetch "https://github.com/Kitware/CMake/releases/download/v$CMAKE_V/cmake-$CMAKE_V-linux-x86_64.tar.gz" "cmake-$CMAKE_V.tar.gz")
    rm -rf "$TOOLS/cmake"
    mkdir -p "$TOOLS/cmake"
    tar -C "$TOOLS/cmake" --strip-components=1 -xf "$tb"
    ln -sf "$TOOLS/cmake/bin/cmake" "$BIN/cmake"
    ln -sf "$TOOLS/cmake/bin/ctest" "$BIN/ctest"
fi

if [ ! -x "$BIN/nasm" ]; then
    log "nasm $NASM_V"
    tb=$(fetch "https://www.nasm.us/pub/nasm/releasebuilds/$NASM_V/nasm-$NASM_V.tar.xz" "nasm-$NASM_V.tar.xz")
    d=$(unpack "$tb" "nasm-$NASM_V")
    ( cd "$d" && ./configure --prefix="$TOOLS/nasm" >/dev/null && make -j"$JOBS" >/dev/null && make install >/dev/null )
    ln -sf "$TOOLS/nasm/bin/nasm" "$BIN/nasm"
    ln -sf "$TOOLS/nasm/bin/ndisasm" "$BIN/ndisasm"
fi

export PATH="$BIN:$PATH"

# ---------------------------------------------------------------- toolchain
# Finding the toolchain by following `swift` lands in swiftly's shim directory, where every
# entry -- including the musl clang config files -- is a symlink to the swiftly binary. clang
# then tries to parse an ELF as a config file. So a candidate only counts if its musl config
# is a real config: a readable file naming the triple.
find_toolchain() {
    local c cfg
    for c in "$(dirname "$(readlink -f "$(command -v swiftc 2>/dev/null)" 2>/dev/null)")" \
             $(ls -d "$HOME"/.local/share/swiftly/toolchains/*/usr/bin 2>/dev/null) \
             /usr/lib/swift/bin /usr/bin; do
        cfg="$c/x86_64-swift-linux-musl-clang.cfg"
        if [ -x "$c/clang" ] && [ -f "$cfg" ] && grep -q -- "-target x86_64-swift-linux-musl" "$cfg" 2>/dev/null; then
            echo "$c"; return 0
        fi
    done
    return 1
}
if [ "$TARGET" != konan ]; then
    SWIFT_TC="$(find_toolchain)" || { echo "no Swift toolchain with musl clang configs found" >&2; exit 1; }
    echo "toolchain: $SWIFT_TC" >&2
fi

if [ "$TARGET" = musl ]; then
    SDK_ROOT="$(ls -d "$HOME"/.swiftpm/swift-sdks/*static-linux*.artifactbundle/*/swift-linux-musl 2>/dev/null | head -1)"
    [ -n "$SDK_ROOT" ] || { echo "Static Linux SDK not installed" >&2; exit 1; }
    SYSROOT="$SDK_ROOT/musl-1.2.5.sdk/x86_64"
    [ -d "$SYSROOT" ] || { echo "musl sysroot missing at $SYSROOT" >&2; exit 1; }

    # The toolchain ships ready-made config files for this exact triple; they set
    # -target/-rtlib/-stdlib/-fuse-ld/-unwindlib/-static so we do not have to guess.
    CC="$SWIFT_TC/clang --config $SWIFT_TC/x86_64-swift-linux-musl-clang.cfg --sysroot=$SYSROOT"
    CXX="$SWIFT_TC/clang++ --config $SWIFT_TC/x86_64-swift-linux-musl-clang++.cfg --sysroot=$SYSROOT"
    AR="$SWIFT_TC/llvm-ar"
    RANLIB="$SWIFT_TC/llvm-ranlib"
    NM="$SWIFT_TC/llvm-nm"
    STRIP="$SWIFT_TC/llvm-strip"
    HOST_TRIPLE=x86_64-linux-musl
    CROSS=1
    CXX_RUNTIME_LIBS="-lc++ -lc++abi -lunwind"
elif [ "$TARGET" = konan ]; then
    # Kotlin/Native links linuxX64 against its own bundled crosstool-NG toolchain -- gcc 8.3.0,
    # glibc 2.19, kernel 4.9 headers. Building the imaging stack with that *same* toolchain is
    # what makes the two agree at link time, and it is what drops the shipped binary's glibc
    # floor to 2.17 (§7). Built with the host's gcc instead, the prefix pulls in libmvec,
    # __isoc23_strtol, __libc_single_threaded and the modern libstdc++ __cxx11 ABI, none of
    # which konan's sysroot has.
    KTC="$(ls -d "$HOME"/.konan/dependencies/x86_64-unknown-linux-gnu-gcc-*-glibc-*/ 2>/dev/null | head -1)"
    [ -n "$KTC" ] || { echo "konan gcc toolchain not found under ~/.konan/dependencies; link a linuxX64 binary once to fetch it" >&2; exit 1; }
    KTC="${KTC%/}"; KP="$KTC/bin/x86_64-unknown-linux-gnu"
    echo "toolchain: $KTC" >&2
    CC="$KP-gcc"
    CXX="$KP-g++"
    AR="$KP-ar"
    RANLIB="$KP-ranlib"
    NM="$KP-nm"
    STRIP="$KP-strip"
    HOST_TRIPLE=x86_64-unknown-linux-gnu
    # Deliberately NOT a cross build. A glibc-2.19 binary runs on a modern glibc host, so
    # configure's test programs execute normally and every cross-compile guard below would be
    # ceremony -- cmake try_run included.
    CROSS=0
    CXX_RUNTIME_LIBS="-lstdc++"
else
    CC=gcc
    CXX=g++
    AR=ar
    RANLIB=ranlib
    NM=nm
    STRIP=strip
    HOST_TRIPLE=x86_64-linux-gnu
    CROSS=0
    CXX_RUNTIME_LIBS="-lstdc++"
fi

# -ffunction-sections/-fdata-sections let the final link drop everything unreferenced, which
# is what keeps ffmpeg's enumerated codec set from costing what a full build would.
COMMON_CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections"

export CC CXX AR RANLIB NM STRIP
export CFLAGS="$COMMON_CFLAGS"
export CXXFLAGS="$COMMON_CFLAGS"
# Cross builds must not see the host's .pc files, or configure scripts happily link glibc
# libraries into a musl target and only the final link says so.
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"

CMAKE_ARGS=(
    -DCMAKE_INSTALL_PREFIX="$PREFIX"
    -DCMAKE_INSTALL_LIBDIR=lib
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DBUILD_SHARED_LIBS=OFF
    -DCMAKE_C_COMPILER_LAUNCHER=
    -DCMAKE_PREFIX_PATH="$PREFIX"
    -DCMAKE_FIND_ROOT_PATH="$PREFIX"
)

if [ "$CROSS" = 1 ]; then
    # A toolchain file rather than -DCMAKE_C_COMPILER: cmake must be told it is cross
    # compiling before it starts probing, or its try-run checks execute musl binaries under
    # assumptions that do not hold.
    TCFILE="$BUILD/musl-toolchain.cmake"
    cat > "$TCFILE" <<TCEOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER "$SWIFT_TC/clang")
set(CMAKE_CXX_COMPILER "$SWIFT_TC/clang++")
set(CMAKE_C_FLAGS_INIT "--config $SWIFT_TC/x86_64-swift-linux-musl-clang.cfg --sysroot=$SYSROOT")
set(CMAKE_CXX_FLAGS_INIT "--config $SWIFT_TC/x86_64-swift-linux-musl-clang++.cfg --sysroot=$SYSROOT")
set(CMAKE_AR "$AR" CACHE FILEPATH "")
set(CMAKE_RANLIB "$RANLIB" CACHE FILEPATH "")
set(CMAKE_NM "$NM" CACHE FILEPATH "")
set(CMAKE_SYSROOT "$SYSROOT")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
TCEOF
    CMAKE_ARGS+=(-DCMAKE_TOOLCHAIN_FILE="$TCFILE")
fi

cmake_build() { # cmake_build <srcdir> <name> [extra cmake args...]
    local src="$1" name="$2"; shift 2
    rm -rf "$BUILD/$name"
    cmake -S "$src" -B "$BUILD/$name" "${CMAKE_ARGS[@]}" "$@" >"$BUILD/$name.log" 2>&1 \
        || { echo "cmake configure failed for $name; tail of $BUILD/$name.log:" >&2; tail -30 "$BUILD/$name.log" >&2; exit 1; }
    cmake --build "$BUILD/$name" -j"$JOBS" >>"$BUILD/$name.log" 2>&1 \
        || { echo "build failed for $name; tail of $BUILD/$name.log:" >&2; tail -30 "$BUILD/$name.log" >&2; exit 1; }
    cmake --install "$BUILD/$name" >>"$BUILD/$name.log" 2>&1
}

autotools_build() { # autotools_build <srcdir> <name> [configure args...]
    local src="$1" name="$2"; shift 2
    rm -rf "$BUILD/$name"; mkdir -p "$BUILD/$name"
    local host_arg=()
    [ "$CROSS" = 1 ] && host_arg=(--host="$HOST_TRIPLE")
    ( cd "$BUILD/$name" && "$src/configure" --prefix="$PREFIX" --enable-static --disable-shared \
        "${host_arg[@]}" "$@" ) >"$BUILD/$name.log" 2>&1 \
        || { echo "configure failed for $name; tail:" >&2; tail -30 "$BUILD/$name.log" >&2; exit 1; }
    make -C "$BUILD/$name" -j"$JOBS" >>"$BUILD/$name.log" 2>&1 \
        || { echo "build failed for $name; tail:" >&2; tail -30 "$BUILD/$name.log" >&2; exit 1; }
    make -C "$BUILD/$name" install >>"$BUILD/$name.log" 2>&1
}

# ---------------------------------------------------------------- libjpeg-turbo
# The classic libjpeg API, not TurboJPEG: milestone C depends on scale_num/scale_denom
# shrink-on-load, which is what bounds decode memory on the library's 164 MP panorama and
# makes 32k 3 MB JPEGs cheap. TurboJPEG's simplified API does not expose it.
if ! have jpeg; then
    log "libjpeg-turbo $JPEG_V"
    tb=$(fetch "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/$JPEG_V/libjpeg-turbo-$JPEG_V.tar.gz" "libjpeg-turbo-$JPEG_V.tar.gz")
    d=$(unpack "$tb" "libjpeg-turbo-$JPEG_V")
    cmake_build "$d" jpeg \
        -DENABLE_SHARED=OFF -DENABLE_STATIC=ON \
        -DWITH_TURBOJPEG=OFF -DWITH_JAVA=OFF \
        -DCMAKE_ASM_NASM_COMPILER="$BIN/nasm"
    stamp jpeg
fi

# ---------------------------------------------------------------- lcms2
# Decision 8: thumbs are converted to sRGB, previews carry their profile through untouched.
if ! have lcms2; then
    log "lcms2 $LCMS_V"
    tb=$(fetch "https://github.com/mm2/Little-CMS/releases/download/lcms$LCMS_V/lcms2-$LCMS_V.tar.gz" "lcms2-$LCMS_V.tar.gz")
    d=$(unpack "$tb" "lcms2-$LCMS_V")
    autotools_build "$d" lcms2 --without-jpeg --without-tiff
    stamp lcms2
fi

# ---------------------------------------------------------------- libexif
if ! have exif; then
    log "libexif $EXIF_V"
    tb=$(fetch "https://github.com/libexif/libexif/releases/download/v$EXIF_V/libexif-$EXIF_V.tar.bz2" "libexif-$EXIF_V.tar.bz2")
    d=$(unpack "$tb" "libexif-$EXIF_V")
    autotools_build "$d" exif --disable-docs --disable-nls
    stamp exif
fi

# ---------------------------------------------------------------- libde265 (HEIC decode)
if ! have de265; then
    log "libde265 $DE265_V"
    tb=$(fetch "https://github.com/strukturag/libde265/releases/download/v$DE265_V/libde265-$DE265_V.tar.gz" "libde265-$DE265_V.tar.gz")
    d=$(unpack "$tb" "libde265-$DE265_V")
    cmake_build "$d" de265 \
        -DENABLE_SDL=OFF -DENABLE_DECODER=OFF -DENABLE_ENCODER=OFF -DBUILD_TESTING=OFF
    stamp de265
fi

# ---------------------------------------------------------------- x265 (HEIC + video encode)
# GPLv2. Personal use is not distribution; it binds the day a release artifact is published.
# Decision 10 made this the only video encoder, which is what let libx264 out of the stack.
if ! have x265; then
    log "x265 $X265_V"
    tb=$(fetch "https://bitbucket.org/multicoreware/x265_git/downloads/x265_$X265_V.tar.gz" "x265-$X265_V.tar.gz")
    d=$(unpack "$tb" "x265_$X265_V")
    cmake_build "$d/source" x265 \
        -DENABLE_SHARED=OFF -DENABLE_CLI=OFF -DENABLE_ASSEMBLY=ON \
        -DHIGH_BIT_DEPTH=OFF -DENABLE_HDR10_PLUS=OFF
    # x265 uses pthreads and named semaphores but does not say so in its .pc file, because on
    # glibc >= 2.34 those symbols live in libc and no flag is needed. Against konan's glibc
    # 2.19 they are still in libpthread, so ffmpeg's configure fails its x265 link test and
    # reports the misleading "x265 not found using pkg-config".
    sed -i 's/^Libs.private: \(.*\)/Libs.private: \1 -lpthread -lrt/' "$PREFIX/lib/pkgconfig/x265.pc"
    stamp x265
fi

# ---------------------------------------------------------------- libheif (HEIC container)
# ENABLE_PLUGIN_LOADING=OFF matters: libheif's default builds x265/libde265 as dlopen-ed
# plugins, which a single static binary cannot use.
if ! have heif; then
    log "libheif $HEIF_V"
    tb=$(fetch "https://github.com/strukturag/libheif/releases/download/v$HEIF_V/libheif-$HEIF_V.tar.gz" "libheif-$HEIF_V.tar.gz")
    d=$(unpack "$tb" "libheif-$HEIF_V")
    cmake_build "$d" heif \
        -DENABLE_PLUGIN_LOADING=OFF \
        -DWITH_EXAMPLES=OFF -DWITH_GDK_PIXBUF=OFF -DBUILD_TESTING=OFF \
        -DWITH_LIBDE265=ON -DWITH_X265=ON \
        -DWITH_AOM_DECODER=OFF -DWITH_AOM_ENCODER=OFF \
        -DWITH_DAV1D=OFF -DWITH_SvtEnc=OFF -DWITH_RAV1E=OFF \
        -DWITH_JPEG_DECODER=OFF -DWITH_JPEG_ENCODER=OFF \
        -DWITH_OpenJPEG_DECODER=OFF -DWITH_OpenJPEG_ENCODER=OFF \
        -DWITH_UNCOMPRESSED_CODEC=OFF
    stamp heif
fi

# ---------------------------------------------------------------- ffmpeg
# Decision 23: --disable-everything, then exactly the inventory's formats. The enabled set is
# derived from the measured library -- 223 hvc1, 166 avc1, 94 mjpeg (avi+mov), 33 mpeg1,
# 28 msmpeg4v2, 5 h263, 31 png, 5 tiff -- plus what the still path needs from ffmpeg
# (png/tiff decode) and what the video path emits (libx265 + aac in mp4).
if ! have ffmpeg; then
    log "ffmpeg $FFMPEG_V"
    tb=$(fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_V.tar.xz" "ffmpeg-$FFMPEG_V.tar.xz")
    d=$(unpack "$tb" "ffmpeg-$FFMPEG_V")
    rm -rf "$BUILD/ffmpeg"; mkdir -p "$BUILD/ffmpeg"

    ff_args=(
        --prefix="$PREFIX"
        --disable-everything --disable-programs --disable-doc --disable-network
        --disable-shared --enable-static --enable-pic
        --disable-autodetect --enable-gpl --enable-libx265
        # zlib is not optional here: ffmpeg's PNG decoder is built only when it
        # is present, and --disable-autodetect means it must be asked for by name.
        # Both sysroots already ship libz.a.
        --enable-zlib
        # containers we actually hold
        --enable-demuxer=mov,avi,mpegps,mpegvideo,matroska,image2
        --enable-muxer=mp4,image2
        --enable-protocol=file
        # video decoders, one per measured codec
        --enable-decoder=h264,hevc,mjpeg,mpeg1video,mpeg2video,mpeg4,msmpeg4v2,msmpeg4v3,h263,h263i
        # still decoders -- the 31 png / 5 tiff strays (decision 3 routes them here)
        --enable-decoder=png,tiff,bmp,gif
        # audio: 192 mp4a, 5 sowt(pcm), plus mp3 for older avi
        --enable-decoder=aac,aac_latm,mp3,pcm_s16le,pcm_s16be,pcm_u8,ac3,adpcm_ima_wav
        --enable-encoder=libx265,aac
        --enable-parser=h264,hevc,mpeg4video,mpegvideo,mjpeg,png
        --enable-filter=scale,yadif,transpose,hflip,vflip,format,null,anull,aresample
        --enable-swscale --enable-swresample --enable-avfilter
        --enable-bsf=hevc_mp4toannexb,h264_mp4toannexb,extract_extradata
        --pkg-config-flags=--static
    )
    if [ "$CROSS" = 1 ]; then
        ff_args+=(
            --enable-cross-compile --target-os=linux --arch=x86_64
            --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --nm="$NM" --strip="$STRIP"
        )
    else
        ff_args+=(--cc="$CC" --cxx="$CXX")
    fi

    ( cd "$BUILD/ffmpeg" && "$d/configure" "${ff_args[@]}" ) >"$BUILD/ffmpeg.log" 2>&1 \
        || { echo "ffmpeg configure failed; tail of $BUILD/ffmpeg.log:" >&2; tail -40 "$BUILD/ffmpeg.log" >&2; exit 1; }
    make -C "$BUILD/ffmpeg" -j"$JOBS" >>"$BUILD/ffmpeg.log" 2>&1 \
        || { echo "ffmpeg build failed; tail:" >&2; tail -40 "$BUILD/ffmpeg.log" >&2; exit 1; }
    make -C "$BUILD/ffmpeg" install >>"$BUILD/ffmpeg.log" 2>&1
    stamp ffmpeg
fi

# ---------------------------------------------------------------- expat
# Not wanted for itself: dbus's configure requires an XML parser even when only the client
# library is being built, and refuses to proceed without one. ~377 KB, no dependencies of its
# own. Nothing in our code includes it.
if ! have expat; then
    log "expat $EXPAT_V"
    tb=$(fetch "https://github.com/libexpat/libexpat/releases/download/R_${EXPAT_V//./_}/expat-$EXPAT_V.tar.xz" "expat-$EXPAT_V.tar.xz")
    d=$(unpack "$tb" "expat-$EXPAT_V")
    autotools_build "$d" expat --without-docbook --without-examples --without-tests
    stamp expat
fi

# ---------------------------------------------------------------- dbus
# The Secret Service client (§1) speaks D-Bus in-process rather than exec'ing `secret-tool`,
# which is what makes the shipped binary literally self-sufficient. libdbus-1 is the reference
# implementation and, unlike libsecret, does not drag glib in -- so §7's "no glib" survives
# intact while the last shell-out goes away.
#
# Only the `dbus/` subdirectory is built. The top-level target would also build dbus-daemon
# and the command-line tools, which is precisely the thing being removed here; the client
# library is all we link.
#
# 1.14.x rather than 1.16: 1.16 dropped autotools for meson, and this script speaks autotools
# and cmake. --disable-x11-autolaunch matters beyond size -- see Credentials.swift on why the
# client opens an explicit address rather than letting libdbus fork dbus-launch.
if ! have dbus; then
    log "dbus $DBUS_V"
    tb=$(fetch "https://dbus.freedesktop.org/releases/dbus/dbus-$DBUS_V.tar.xz" "dbus-$DBUS_V.tar.xz")
    d=$(unpack "$tb" "dbus-$DBUS_V")
    rm -rf "$BUILD/dbus"; mkdir -p "$BUILD/dbus"
    host_arg=()
    [ "$CROSS" = 1 ] && host_arg=(--host="$HOST_TRIPLE")
    ( cd "$BUILD/dbus" && "$d/configure" --prefix="$PREFIX" --enable-static --disable-shared \
        "${host_arg[@]}" --disable-tests --disable-doxygen-docs --disable-xml-docs \
        --disable-selinux --disable-apparmor --disable-systemd --disable-launchd \
        --without-x --disable-x11-autolaunch --with-xml=expat ) >"$BUILD/dbus.log" 2>&1 \
        || { echo "configure failed for dbus; tail:" >&2; tail -30 "$BUILD/dbus.log" >&2; exit 1; }
    make -C "$BUILD/dbus/dbus" -j"$JOBS" >>"$BUILD/dbus.log" 2>&1 \
        || { echo "build failed for dbus; tail:" >&2; tail -30 "$BUILD/dbus.log" >&2; exit 1; }
    make -C "$BUILD/dbus/dbus" install >>"$BUILD/dbus.log" 2>&1
    stamp dbus
fi

# ---------------------------------------------------------------- photos-dbus.pc
# Separate from photos-native.pc because it is a separate concern: PhotosIngest links the
# keyring client, PhotosPipeline links the imaging stack, and neither should drag the other in
# because they happen to be built by the same script.
#
# dbus splits its headers across two prefixes -- dbus-arch-deps.h is generated per
# architecture and installed under libdir -- so both directories have to be on the include
# path or <dbus/dbus.h> fails to resolve its own include.
log "photos-dbus.pc"
mkdir -p "$PREFIX/lib/pkgconfig"
cat > "$PREFIX/lib/pkgconfig/photos-dbus.pc" <<PCEOF
prefix=$PREFIX
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: photos-dbus
Description: libdbus-1 for the Secret Service client ($TARGET)
Version: 1
Cflags: -I\${includedir}/dbus-1.0 -I\${libdir}/dbus-1.0/include
Libs: -L\${libdir} -ldbus-1 -lexpat -lpthread
PCEOF

# ---------------------------------------------------------------- one .pc to rule them all
# SwiftPM's pkgConfig support calls `pkg-config --libs` without --static, so the transitive
# static closure has to be spelled out in Libs: rather than left to Libs.private. Writing it
# ourselves is also what keeps unsafeFlags out of Package.swift -- a package that uses
# unsafeFlags cannot be consumed as a dependency, and the iOS app will consume this one.
#
# --start-group because these archives reference each other in both directions: libavcodec
# calls into x265, libheif calls into both x265 and libde265, and a static linker resolves
# strictly left to right. Grouping them is what stops the correct order from being something
# anyone has to know.
# ------------------------------------------------- sqlite / openssl / curl (konan only)
# These three exist for the Kotlin binary and nothing else: the Swift tree takes HTTP from
# FoundationNetworking and SQLite from Sources/CSQLite, so building them into the host and musl
# prefixes would be minutes of build time nothing consumes -- and openssl and curl have never
# been built against the Swift musl toolchain, so it would be untested minutes at that.
if [ "$TARGET" = konan ]; then

# ---------------------------------------------------------------- sqlite
# Built here rather than taken from the distro, and the reason is the link, not the SQL.
# A distro libsqlite3.so is built against that distro's glibc -- Ubuntu 24.04's needs
# GLIBC_2.38 -- while everything else here links against konan's 2.19 sysroot. The two cannot
# meet, so using the platform's library would raise the shipped binary's floor from 2.17 to
# whatever the build host happens to have, and make it a property of the machine rather than
# of this recipe.
#
# Nothing is committed to the repository: this is a pinned tarball like the ten above.
#
# The flags are the small set that earns its place. DQS=0 turns a typo'd identifier into an
# error instead of a silent string literal. USE_URI=1 is required by SQLDelight's in-memory
# driver, which passes `file:name?mode=memory&cache=shared` and otherwise gets a *file* of
# that name. The rest are hardening and size. Notably absent: ENABLE_MATH_FUNCTIONS, because
# no query in this project calls one.
if ! have sqlite; then
    log "sqlite $SQLITE_V"
    tb=$(fetch "https://sqlite.org/$SQLITE_YEAR/sqlite-autoconf-$SQLITE_V.tar.gz" "sqlite-autoconf-$SQLITE_V.tar.gz")
    src=$(unpack "$tb" "sqlite-autoconf-$SQLITE_V")
    CFLAGS="$COMMON_CFLAGS \
        -DSQLITE_DQS=0 \
        -DSQLITE_THREADSAFE=1 \
        -DSQLITE_USE_URI=1 \
        -DSQLITE_OMIT_LOAD_EXTENSION \
        -DSQLITE_DEFAULT_MEMSTATUS=0 \
        -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1 \
        -DSQLITE_LIKE_DOESNT_MATCH_BLOBS \
        -DSQLITE_OMIT_DEPRECATED" \
        autotools_build "$src" sqlite --disable-readline
    CFLAGS="$COMMON_CFLAGS"
    stamp sqlite
fi

# ---------------------------------------------------------------- openssl
# Ktor's Kotlin/Native client speaks HTTP through curl, and curl needs a TLS stack. no-shared
# keeps it to the two static archives curl links; no-tests halves the build.
#
# 3.0.x rather than 3.5: `no-docs` only arrived in 3.1, and the LTS line is the one every
# distro has been shipping longest -- which is the same argument as the old toolchain above.
if ! have openssl; then
    log "openssl $OPENSSL_V"
    tb=$(fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_V/openssl-$OPENSSL_V.tar.gz" "openssl-$OPENSSL_V.tar.gz")
    src=$(unpack "$tb" "openssl-$OPENSSL_V")
    rm -rf "$BUILD/openssl"; mkdir -p "$BUILD/openssl"
    ( cd "$BUILD/openssl" && "$src/Configure" linux-x86_64 no-shared no-tests \
        --prefix="$PREFIX" --libdir=lib --openssldir="$PREFIX/ssl" \
        CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" ) >"$BUILD/openssl.log" 2>&1 \
        || { echo "configure failed for openssl; tail:" >&2; tail -30 "$BUILD/openssl.log" >&2; exit 1; }
    make -C "$BUILD/openssl" -j"$JOBS" >>"$BUILD/openssl.log" 2>&1 \
        || { echo "build failed for openssl; tail:" >&2; tail -30 "$BUILD/openssl.log" >&2; exit 1; }
    make -C "$BUILD/openssl" install_sw >>"$BUILD/openssl.log" 2>&1
    stamp openssl
fi

# ---------------------------------------------------------------- curl
# Everything optional is off: this client talks to one S3 endpoint over HTTPS and needs none
# of psl, idn2, http2, brotli, zstd or ldap. Each of them would be another pinned source in
# this file for no request this tool makes.
#
# --with-ca-bundle is not optional for a *statically linked* curl: it has no distro default
# and every HTTPS request would fail verification. The path below is Debian/Ubuntu; the CLI
# should probe the handful of known locations at runtime rather than trust this one.
if ! have curl; then
    log "curl $CURL_V"
    tb=$(fetch "https://curl.se/download/curl-$CURL_V.tar.xz" "curl-$CURL_V.tar.xz")
    src=$(unpack "$tb" "curl-$CURL_V")
    autotools_build "$src" curl \
        --with-openssl="$PREFIX" --without-libpsl --without-libidn2 --without-nghttp2 \
        --without-brotli --without-zstd --disable-ldap --disable-ldaps \
        --with-ca-bundle=/etc/ssl/certs/ca-certificates.crt
    stamp curl
fi

fi  # konan-only block

log "photos-native.pc"
mkdir -p "$PREFIX/lib/pkgconfig"
cat > "$PREFIX/lib/pkgconfig/photos-native.pc" <<PCEOF
prefix=$PREFIX
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: photos-native
Description: milestone C imaging stack ($TARGET)
Version: 1
Cflags: -I\${includedir}
Libs: -L\${libdir} -Wl,--start-group -lheif -lde265 -lx265 -lavfilter -lavformat -lavcodec -lswscale -lswresample -lavutil -ljpeg -llcms2 -lexif -lz -Wl,--end-group $CXX_RUNTIME_LIBS -lm -lpthread -ldl
PCEOF

log "done: $PREFIX"
ls -la "$PREFIX/lib"/*.a 2>/dev/null | awk '{printf "  %10.1f KB  %s\n", $5/1024, $9}'
