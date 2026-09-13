#!/usr/bin/env bash
# Build the app, run it on a simulator, and take its picture.
#
# The iOS counterpart of `./gradlew :app:desktop:run`, and the reason it is a script rather than
# a paragraph in the README: the incantation is four tools deep -- xcodebuild's destination
# grammar, simctl's device lifecycle, the bundle id, and simctl's rule that it forwards only
# variables named SIMCTL_CHILD_* -- and none of that is worth rediscovering per session.
#
# It signs ad-hoc (`CODE_SIGN_IDENTITY=-`), which needs no certificate and is what a simulator
# accepts. That is not a formality: without a signature the app carries no entitlements, and
# every Keychain call then fails with -34018 while the rest of the app runs perfectly.
#
#   Scripts/ios-sim.sh                 build, install, launch, screenshot
#   Scripts/ios-sim.sh build           build only
#   Scripts/ios-sim.sh shot out.png    screenshot a running app
#
# The app's console goes to build/ios-sim.log, which is where an uncaught Kotlin exception
# ends up -- the failure this loop hits most.
#
# Credentials come from PHOTOS_ENDPOINT / PHOTOS_PASSWORD, exactly as the CLI and the desktop
# app take them, so `secrets-env Scripts/ios-sim.sh` works. Without them the app says it is not
# set up rather than showing an empty library -- until §1's setup screen lands, at which point
# this half of the script goes away.
set -euo pipefail

cd "$(dirname "$0")/.."

PROJECT=app/ios/Photos.xcodeproj
SCHEME=Photos
BUNDLE_ID=net.stho.photos
# A concrete model, not `generic/`: an install needs a booted device, and naming one here is
# what makes two runs use the same simulator and therefore the same container -- which is the
# whole point, since §4's cache is what a second run should find already warm.
DEVICE="${PHOTOS_SIM_DEVICE:-iPhone 17 Pro}"
DERIVED=build/DerivedData
APP="$DERIVED/Build/Products/Debug-iphonesimulator/Photos.app"
LOG=build/ios-sim.log
BUILD_LOG=build/ios-sim-build.log

build() {
  xcodebuild build \
    -project "$PROJECT" \
    -scheme "$SCHEME" \
    -destination "platform=iOS Simulator,name=$DEVICE" \
    -derivedDataPath "$DERIVED" \
    CODE_SIGN_IDENTITY=- \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGNING_ALLOWED=YES \
    >"$BUILD_LOG" 2>&1 || {
      # Checked by exit status, never by whether a bundle exists: a failed build leaves the
      # previous run's Photos.app in place, and launching that is a run that looks like it
      # worked while testing code that was never compiled -- measured, once, as a probe that
      # printed nothing because the app it launched did not contain it.
      grep -E "e: file://|error: [^C]" "$BUILD_LOG" | sort -u >&2 || tail -20 "$BUILD_LOG" >&2
      echo "build failed; full log in $BUILD_LOG" >&2
      exit 1
    }
  grep -E "BUILD SUCCEEDED" "$BUILD_LOG"
}

booted() {
  # `boot` on an already-booted device exits non-zero, which under `set -e` would end the run
  # on the second invocation -- the common case.
  xcrun simctl boot "$DEVICE" 2>/dev/null || true
  xcrun simctl bootstatus "$DEVICE" -b >/dev/null
}

launch() {
  xcrun simctl install "$DEVICE" "$APP"
  # `--console-pty` is what makes an uncaught Kotlin exception visible -- without it a crash on
  # launch is an app that simply is not there, with the reason in a log nobody opened. It never
  # returns on its own, though, so it is backgrounded to a file and killed once the screenshot
  # is taken; the file is then the run's report.
  #
  # simctl passes on only SIMCTL_CHILD_*, stripping the prefix as it does. Measured the hard
  # way: a plain `export PHOTOS_ENDPOINT` reaches simctl and not the app.
  SIMCTL_CHILD_PHOTOS_ENDPOINT="${PHOTOS_ENDPOINT:-}" \
  SIMCTL_CHILD_PHOTOS_PASSWORD="${PHOTOS_PASSWORD:-}" \
    xcrun simctl launch --console-pty "$DEVICE" "$BUNDLE_ID" >"$LOG" 2>&1 &
  CONSOLE_PID=$!
  # The screenshot wants the first frame, not the first instant.
  sleep "${PHOTOS_SIM_SETTLE:-6}"
}

# Killing the console pty takes the app down with it, so this happens *after* the screenshot --
# which is the whole difference between a picture of the app and a picture of the home screen.
stop_console() {
  [ -n "${CONSOLE_PID:-}" ] && kill "$CONSOLE_PID" 2>/dev/null || true
}

report() {
  # An uncaught Kotlin exception is the failure this loop actually hits, so it is surfaced
  # rather than left in the file.
  if grep -q "Uncaught Kotlin exception" "$LOG" 2>/dev/null; then
    echo "--- the app crashed on launch ---" >&2
    head -8 "$LOG" >&2
    return 1
  fi
}

shot() {
  local out="${1:-build/ios-sim.png}"
  mkdir -p "$(dirname "$out")"
  xcrun simctl io "$DEVICE" screenshot --type=png "$out"
  echo "$out"
}

case "${1:-all}" in
  build) build ;;
  shot)  shot "${2:-}" ;;
  all)   build; booted; launch; shot "${2:-}"; stop_console; report ;;
  *)     echo "usage: $0 [build|shot <path>|all]" >&2; exit 2 ;;
esac
