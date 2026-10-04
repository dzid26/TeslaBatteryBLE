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
adb install -r "$APK"

# Grant the permissions the app asks for so no dialogs cover the UI.
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
for permission in \
  BLUETOOTH_SCAN \
  BLUETOOTH_CONNECT \
  ACCESS_FINE_LOCATION \
  POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$permission" || true
done
adb shell cmd location set-location-enabled true || true

adb shell am start -W -n "$ACTIVITY" > /dev/null
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
