#!/usr/bin/env bash
# Runs a command with pkg-config pointed at one of the two native prefixes.
#
#     Scripts/with-native.sh host swift test
#     Scripts/with-native.sh musl swift build --swift-sdk x86_64-swift-linux-musl -c release
#
# SwiftPM resolves `pkgConfig: "photos-native"` itself, after the manifest has been evaluated
# in its own process — so the manifest cannot choose the prefix and something outside it must.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-}"
case "$TARGET" in
    host|musl) shift ;;
    *) echo "usage: $0 <host|musl> <command...>" >&2; exit 2 ;;
esac

PREFIX="$ROOT/.tools/native/$TARGET"
if [ ! -f "$PREFIX/lib/pkgconfig/photos-native.pc" ]; then
    echo "$TARGET prefix not built. Run: Scripts/build-native.sh $TARGET" >&2
    exit 1
fi

export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
exec "$@"
