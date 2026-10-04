#!/usr/bin/env bash
# Capture screenshots of the app on a booted device or emulator.
#
# Usage: capture-screenshots.sh <apk> <output-dir> [package] [activity]
set -euo pipefail

APK="${1:?usage: capture-screenshots.sh <apk> <output-dir> [package] [activity]}"
OUT="${2:?usage: capture-screenshots.sh <apk> <output-dir> [package] [activity]}"
PKG="${3:-com.dzid26.teslable}"
ACTIVITY="${4:-$PKG/.MainActivity}"

mkdir -p "$OUT"

adb wait-for-device

# UI interaction needs more than sys.boot_completed: the input service and the
# package manager must be up too, and they can lag minutes behind without KVM.
ready=false
for _ in $(seq 1 96); do
  boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  input_service="$(adb shell service check input 2>/dev/null | tr -d '\r')"
  package_manager="$(adb shell pm path android 2>/dev/null | tr -d '\r')"
  if [ "$boot_completed" = "1" ] &&
    [ "${input_service#*: }" = "found" ] &&
    [ "${package_manager%%:*}" = "package" ]; then
    ready=true
    break
  fi
  sleep 5
done
if [ "$ready" != true ]; then
  echo "emulator framework did not become ready; skipping screenshots" >&2
  exit 1
fi

# Keep the screen on and make sure it is awake. `screencap` returns a black
# frame while the emulator display is asleep.
adb shell svc power stayon true || true
for _ in $(seq 1 30); do
  if adb shell dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake'; then
    break
  fi
  adb shell input keyevent KEYCODE_WAKEUP || true
  sleep 2
done
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true

installed=false
for _ in 1 2 3; do
  if adb install -r "$APK"; then
    installed=true
    break
  fi
  sleep 10
done
if [ "$installed" != true ]; then
  echo "failed to install $APK" >&2
  exit 1
fi

# Grant the permissions the app asks for so no dialogs cover the UI.
for permission in \
  BLUETOOTH_SCAN \
  BLUETOOTH_CONNECT \
  ACCESS_FINE_LOCATION \
  POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$permission" || true
done
adb shell cmd location set-location-enabled true || true

# Bring the app to the foreground and wait until its window has focus.
adb shell am start -W -n "$ACTIVITY" > /dev/null
focused=false
for _ in $(seq 1 30); do
  if adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | grep -q "$PKG"; then
    focused=true
    break
  fi
  sleep 2
  adb shell am start -n "$ACTIVITY" > /dev/null 2>&1 || true
done
if [ "$focused" != true ]; then
  echo "app never reached the foreground; skipping screenshots" >&2
  exit 1
fi

sleep 5
adb exec-out screencap -p > "$OUT/01-overview.png"

# Tap a button by the text shown on screen.
tap_text() {
  local text="$1" bounds cx cy
  adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 || return 1
  adb pull /sdcard/window.xml /tmp/window.xml > /dev/null 2>&1 || return 1
  bounds="$(tr '>' '\n' < /tmp/window.xml \
    | grep -F "text=\"$text\"" \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
    | head -n 1)" || return 1
  [ -n "$bounds" ] || return 1
  cx="$(sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \3/' <<< "$bounds" \
    | awk '{print int(($1 + $2) / 2)}')"
  cy="$(sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\2 \4/' <<< "$bounds" \
    | awk '{print int(($1 + $2) / 2)}')"
  adb shell input tap "$cx" "$cy"
}

# Show the scanning state. Without Bluetooth hardware the app keeps running
# and reports it in the log, so this works on emulators too.
if tap_text "Scan for Teslas"; then
  sleep 4
  adb exec-out screencap -p > "$OUT/02-scanning.png"
fi

# The Car tab hosts the car identity, VIN and the history graph.
if tap_text "Car"; then
  sleep 3
  adb exec-out screencap -p > "$OUT/03-car.png"
fi

# Demo builds (assembleDebug -PdemoCar=true) simulate a car. These steps are
# skipped silently on real builds, which share this script.
if tap_text "Connection"; then
  sleep 2
fi
if tap_text "AA:BB:CC:DD:EE:01"; then
  sleep 4
  adb exec-out screencap -p > "$OUT/04-demo-connected.png"
  if tap_text "Pair key"; then
    sleep 3
    adb exec-out screencap -p > "$OUT/05-demo-card-tap.png"
    sleep 6
    adb exec-out screencap -p > "$OUT/06-demo-paired.png"
  fi
  if tap_text "Wake vehicle"; then
    sleep 6
    adb exec-out screencap -p > "$OUT/07-demo-awake.png"
  fi
  if tap_text "Read SOC"; then
    sleep 4
    adb exec-out screencap -p > "$OUT/08-demo-soc.png"
  fi
fi

ls -l "$OUT"
