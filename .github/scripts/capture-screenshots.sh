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

# The emulator script starts as soon as the device is online, which can still
# be inside the boot animation; wait for the system to finish booting.
for _ in $(seq 1 60); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    break
  fi
  sleep 5
done

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

adb install -r "$APK"

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

ls -l "$OUT"
