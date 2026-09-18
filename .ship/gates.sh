#!/usr/bin/env bash
set -euo pipefail

# Every module, 319 tests, and the native libraries they link: compiled on a machine that has
# never built them -- which is what `timeout.gates` is sized for -- and taken from the build
# cache everywhere else.
./gradlew build

# The 14 scenarios against the *shipped* binary. Opt-in, and excluded from `build` on
# purpose -- so nothing runs them unless something like this asks.
./gradlew :tests:cli:e2e
