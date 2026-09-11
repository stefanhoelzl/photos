#!/usr/bin/env bash
set -euo pipefail

# The imaging prefix is gitignored and per-checkout, so a fresh worktree has none. Build it
# rather than abort -- the repo knows how to make it. This is slow on a cold checkout and is
# what `timeout.gates` is sized for; on a warm one the guard costs a stat.
[ -f .tools/native/konan/lib/libheif.a ] || Scripts/build-native.sh

# Says in one line what a failed cinterop says in two hundred -- and catches a prefix that
# was built but is incomplete, which the stat above cannot.
./gradlew checkNativePrefix

# Every module, 319 tests.
./gradlew build

# The 14 scenarios against the *shipped* binary. Opt-in, and excluded from `build` on
# purpose -- so nothing runs them unless something like this asks.
./gradlew :tests:cli:e2e
