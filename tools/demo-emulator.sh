#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
#
# Run the app against the simulated car on a local Android emulator.
#
# Builds with -PdemoCar=true, boots an AVD when none is running, installs the
# APK, and launches the app. --capture runs the shared screenshot script
# (.github/scripts/capture-screenshots.sh) through the whole demo flow instead.
#
# Usage: tools/demo-emulator.sh [--avd NAME] [--no-window] [--capture]
#                               [--skip-build] [--out-dir DIR]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AVD="tbb30"
NO_WINDOW=0
CAPTURE=0
SKIP_BUILD=0
OUT_DIR="$REPO_ROOT/demo-screenshots"

usage() {
  sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd)
      AVD="${2:?--avd needs a name}"
      shift 2
      ;;
    --no-window)
      NO_WINDOW=1
      shift
      ;;
    --capture)
      CAPTURE=1
      shift
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --out-dir)
      OUT_DIR="${2:?--out-dir needs a path}"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

# The SDK lives in different places per platform, or wherever ANDROID_HOME says.
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then
  for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "${LOCALAPPDATA:-}/Android/Sdk"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
      SDK="$candidate"
      break
    fi
  done
fi
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
  echo "Android SDK not found; set ANDROID_HOME" >&2
  exit 1
fi
SDK="${SDK//\\//}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
if [[ ! -f "$ADB" && ! -f "$ADB.exe" ]]; then
  echo "adb not found under $SDK" >&2
  exit 1
fi
if [[ ! -f "$EMULATOR" && ! -f "$EMULATOR.exe" ]]; then
  echo "emulator not found under $SDK; install it with sdkmanager" >&2
  exit 1
fi

# adb accepts POSIX paths on Linux/macOS but not on Git Bash; cygpath fixes it.
to_host_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

emulator_serial() {
  "$ADB" devices 2>/dev/null | awk '/^emulator-[0-9]+[[:space:]]+device/ { print $1; exit }'
}

APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "Building demo APK (-PdemoCar=true)..."
  (cd "$REPO_ROOT" && ./gradlew :app:assembleDebug -PdemoCar=true --console=plain)
fi
if [[ ! -f "$APK" ]]; then
  echo "missing $APK" >&2
  exit 1
fi

SERIAL="$(emulator_serial)"
if [[ -z "$SERIAL" ]]; then
  echo "Starting emulator '$AVD'..."
  emulator_args=(-avd "$AVD" -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect)
  if [[ "$NO_WINDOW" -eq 1 ]]; then
    emulator_args+=(-no-window)
  fi
  ("$EMULATOR" "${emulator_args[@]}" >/dev/null 2>&1 &)
  for _ in $(seq 1 120); do
    sleep 3
    SERIAL="$(emulator_serial)"
    [[ -n "$SERIAL" ]] && break
  done
fi
if [[ -z "$SERIAL" ]]; then
  echo "emulator did not show up in adb devices" >&2
  exit 1
fi
echo "Using $SERIAL"

"$ADB" -s "$SERIAL" wait-for-device
booted=""
for _ in $(seq 1 120); do
  booted="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$booted" == "1" ]] && break
  sleep 3
done
if [[ "$booted" != "1" ]]; then
  echo "emulator did not finish booting" >&2
  exit 1
fi

"$ADB" -s "$SERIAL" install -r "$(to_host_path "$APK")" >/dev/null
"$ADB" -s "$SERIAL" shell pm grant com.dzid26.teslable android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true

if [[ "$CAPTURE" -eq 1 ]]; then
  echo "Running the shared demo script -> $OUT_DIR"
  ANDROID_SERIAL="$SERIAL" bash "$REPO_ROOT/.github/scripts/capture-screenshots.sh" \
    "$(to_host_path "$APK")" "$(to_host_path "$OUT_DIR")"
else
  "$ADB" -s "$SERIAL" shell am start -n com.dzid26.teslable/.MainActivity >/dev/null
  echo "Launched on $SERIAL"
  echo "Stop it with: $ADB -s $SERIAL emu kill"
fi
