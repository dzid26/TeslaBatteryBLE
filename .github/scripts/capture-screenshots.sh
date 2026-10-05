#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Capture screenshots of the app on a booted device or emulator.
#
# Usage: capture-screenshots.sh <apk> <output-dir> [package] [activity]
#
# Walks the simulated-car flow (demo builds, `assembleDebug -PdemoCar=true`):
# the cars list, a scan, the car detail with a battery reading, the history
# graph and Settings, then repeats every screen in dark mode. Taps wait for the
# target UI text through uiautomator instead of fixed sleeps, so slow emulators
# stay reliable. On a plain debug build the car steps are skipped silently and
# only the scan is captured.
set -euo pipefail

# Git Bash on Windows rewrites /sdcard paths passed to adb; keep them literal.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

APK="${1:?usage: capture-screenshots.sh <apk> <output-dir> [package] [activity]}"
OUT="${2:?usage: capture-screenshots.sh <apk> <output-dir> [package] [activity]}"
PKG="${3:-com.dzid26.teslable}"
ACTIVITY="${4:-$PKG/.MainActivity}"

mkdir -p "$OUT"
WORK="$(mktemp -d)"
DUMP="$WORK/window.xml"
trap 'rm -rf "$WORK"' EXIT

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

# Start from a clean slate so the flow always begins on the cars list.
adb shell pm clear "$PKG" > /dev/null 2>&1 || true

# Grant the permissions the app asks for so no dialogs cover the UI.
for permission in \
  BLUETOOTH_SCAN \
  BLUETOOTH_CONNECT \
  ACCESS_FINE_LOCATION \
  POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$permission" || true
done
adb shell cmd location set-location-enabled true || true

# ------------------------------------------------------------------ UI helpers

# Dump the current window hierarchy. uiautomator can race a busy screen, so
# every caller treats a failed dump as "not found yet" and retries.
ui_dump() {
  adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 &&
    adb pull /sdcard/window.xml "$DUMP" > /dev/null 2>&1
}

# Wait until the raw hierarchy contains a literal string (usually `text="...`).
wait_for_literal() {
  local needle="$1" timeout="${2:-30}" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if ui_dump && grep -qF "$needle" "$DUMP"; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

wait_for_text() {
  wait_for_literal "text=\"$1" "${2:-30}"
}

# First <node> tag whose text starts with $1 (empty when there is none).
node_for_text() {
  tr '>' '\n' < "$DUMP" | grep -F "text=\"$1" | head -n 1
}

node_for_desc() {
  tr '>' '\n' < "$DUMP" | grep -F "content-desc=\"$1" | head -n 1
}

bounds_of() {
  grep -oE 'bounds="\[-?[0-9]+,-?[0-9]+\]\[-?[0-9]+,-?[0-9]+\]"' <<< "$1" | head -n 1
}

center_x() {
  sed -E 's/.*\[(-?[0-9]+),(-?[0-9]+)\]\[(-?[0-9]+),(-?[0-9]+)\].*/\1 \3/' <<< "$1" |
    awk '{print int(($1 + $2) / 2)}'
}

center_y() {
  sed -E 's/.*\[(-?[0-9]+),(-?[0-9]+)\]\[(-?[0-9]+),(-?[0-9]+)\].*/\2 \4/' <<< "$1" |
    awk '{print int(($1 + $2) / 2)}'
}

top_of() {
  sed -E 's/.*\[(-?[0-9]+),(-?[0-9]+)\]\[(-?[0-9]+),(-?[0-9]+)\].*/\2/' <<< "$1"
}

# Wait until a node with text/content-desc starting with $2 exists. On success
# FOUND_NODE holds the node's tag.
wait_for_node() {
  local attr="$1" prefix="$2" timeout="${3:-30}"
  local waited=0 node=""
  while [ "$waited" -lt "$timeout" ]; do
    if ui_dump; then
      case "$attr" in
        text) node="$(node_for_text "$prefix")" ;;
        desc) node="$(node_for_desc "$prefix")" ;;
      esac
      if [ -n "$node" ]; then
        FOUND_NODE="$node"
        return 0
      fi
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

tap_text() {
  local text="$1" timeout="${2:-30}" bounds
  if ! wait_for_node text "$text" "$timeout"; then
    echo "  ! '$text' never appeared" >&2
    return 1
  fi
  bounds="$(bounds_of "$FOUND_NODE")"
  if [ -z "$bounds" ]; then
    echo "  ! no bounds for '$text'" >&2
    return 1
  fi
  adb shell input tap "$(center_x "$bounds")" "$(center_y "$bounds")"
}

tap_desc() {
  local desc="$1" timeout="${2:-30}" bounds
  if ! wait_for_node desc "$desc" "$timeout"; then
    echo "  ! '$desc' never appeared" >&2
    return 1
  fi
  bounds="$(bounds_of "$FOUND_NODE")"
  if [ -z "$bounds" ]; then
    echo "  ! no bounds for '$desc'" >&2
    return 1
  fi
  adb shell input tap "$(center_x "$bounds")" "$(center_y "$bounds")"
}

# Tap a control and wait for the screen it leads to. Compose exposes disabled
# buttons as enabled in the accessibility tree, so a tap that lands too early
# is simply retried instead of trusted.
tap_and_wait() {
  local tap="$1" wait="$2" timeout="${3:-25}" attempt
  for attempt in 1 2 3; do
    if tap_text "$tap" 20 && wait_for_text "$wait" "$timeout"; then
      return 0
    fi
    echo "  ... '$tap' did not lead to '$wait' (attempt $attempt)" >&2
    sleep 2
  done
  return 1
}

# Pull the current screen down to refresh: the cars list scans, and the car
# view reconnects, wakes, or reads the battery based on its state.
pull_refresh() {
  adb shell input swipe 540 600 540 1600 800
}

# Pull until the target text appears (one pull is one refresh action).
pull_until_text() {
  local wait="$1" attempts="${2:-3}" timeout="${3:-15}" attempt
  for attempt in $(seq 1 "$attempts"); do
    pull_refresh
    if wait_for_text "$wait" "$timeout"; then
      return 0
    fi
    echo "  ... pull did not lead to '$wait' (attempt $attempt)" >&2
    sleep 1
  done
  return 1
}

# Scroll until the text node sits in the upper part of the screen, so the card
# below it is framed. Swipes are slow so flings cannot overshoot the end of the
# content, and a card that sits near the bottom is accepted once it is high
# enough to fill the frame.
scroll_to_text() {
  local text="$1" tries=0 node bounds top distance
  while [ "$tries" -lt 6 ]; do
    if ui_dump; then
      node="$(node_for_text "$text")"
      if [ -n "$node" ]; then
        bounds="$(bounds_of "$node")"
        top="$(top_of "$bounds")"
        if [ -n "$top" ] && [ "$top" -ge 0 ] && [ "$top" -lt 1100 ]; then
          echo "  framed '$text' at y=$top"
          return 0
        fi
        if [ -n "$top" ] && [ "$top" -ge 1100 ]; then
          distance=$((top - 500))
          [ "$distance" -gt 900 ] && distance=900
          adb shell input swipe 540 1700 540 $((1700 - distance)) 600
        else
          # Scrolled past the text: bring it back down into view.
          adb shell input swipe 540 700 540 1200 600
        fi
      else
        adb shell input swipe 540 1700 540 1000 600
      fi
    fi
    sleep 1
    tries=$((tries + 1))
  done
  return 1
}

capture() {
  local name="$1"
  if adb exec-out screencap -p > "$OUT/$name" && [ -s "$OUT/$name" ]; then
    echo "  captured $name ($(wc -c < "$OUT/$name" | tr -d ' ') bytes)"
  else
    echo "  ! failed to capture $name" >&2
    rm -f "$OUT/$name"
  fi
}

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
sleep 2

# 02 — the scan finding the simulated cars. A pull on the cars list starts it;
# without Bluetooth hardware the app keeps running and reports it in the log,
# so this works on emulators too.
pull_refresh
if wait_for_text "Scanning:" 20 && wait_for_text "AA:BB:CC:DD:EE:01" 20; then
  sleep 1
  capture 02-scanning.png
else
  echo "  ! scan results never appeared" >&2
fi

# Open the simulated car and drive the demo flow. These steps are skipped
# silently on real builds, which share this script.
if tap_text "AA:BB:CC:DD:EE:01"; then
  if wait_for_text "Pair key" 30; then
    tap_and_wait "Pair key" "Paired" 30 || echo "  ! pairing did not finish" >&2
    # Pull-to-refresh replaced the Wake and Read buttons: one pull wakes the
    # car, the next reads the battery once the status refresh lands.
    pull_until_text "Awake" 2 || echo "  ! car never reported awake" >&2
    if pull_until_text "Charge limit" 3; then
      sleep 1
      capture 03-car.png
    else
      echo "  ! no charge reading appeared" >&2
    fi
    # 04 — the history graph, framed by scrolling to the card.
    if scroll_to_text "Battery history"; then
      sleep 1
      capture 04-history.png
    elif wait_for_text "Battery history" 5; then
      # The card is on screen but the page cannot scroll it any higher.
      echo "  ... history card is already as high as the page allows" >&2
      sleep 1
      capture 04-history.png
    else
      echo "  ! could not frame the history card" >&2
    fi
    # 01 — the cars list with the connected, paired car.
    adb shell input keyevent KEYCODE_BACK
    if wait_for_text "Cars" 20 &&
      wait_for_text "Demo Tesla" 20 &&
      wait_for_text "Connected · " 20; then
      sleep 1
      capture 01-overview.png
    else
      echo "  ! cars list did not show the connected car" >&2
    fi
    # 05 — Settings with the vehicle-key card.
    if tap_desc "Settings" 20; then
      if wait_for_text "Vehicle key" 20; then
        sleep 1
        capture 05-settings.png
      else
        echo "  ! settings screen did not open" >&2
      fi
      adb shell input keyevent KEYCODE_BACK
    fi
  else
    echo "  ! car detail did not open" >&2
  fi
else
  echo "  ! simulated car not listed (is this a demo build?)" >&2
fi

# Dark-mode pass: every screen again with the dark theme. The theme change
# recreates the activity (and can restart the app), so the app may come back on
# the car screen; navigate to the cars list first. Each capture goes through the
# same non-empty check as the light pass, and the dark files become required
# once the device accepts the theme switch.
dark_pass=false
if adb shell cmd uimode night yes > /dev/null 2>&1; then
  dark_pass=true
  sleep 4
  if ! wait_for_text "Cars" 15; then
    adb shell input keyevent KEYCODE_BACK
    wait_for_text "Cars" 15 || true
  fi
  if wait_for_text "Cars" 5; then
    sleep 1
    capture 01-overview-dark.png

    # 02 — the scan, with the simulated car already known from the light pass
    # (its address is no longer shown, so wait for the other car's address).
    pull_refresh
    if wait_for_text "Scanning:" 20 && wait_for_text "AA:BB:CC:DD:EE:02" 20; then
      sleep 1
      capture 02-scanning-dark.png
    else
      echo "  ! dark scan results never appeared" >&2
    fi

    # 03 — the car detail; after a process restart the car is asleep again.
    if tap_text "Demo Tesla" 20; then
      if ! wait_for_text "Charge limit" 5; then
        pull_until_text "Awake" 2 || true
        pull_until_text "Charge limit" 3 || true
      fi
      if wait_for_text "Charge limit" 10; then
        sleep 1
        capture 03-car-dark.png

        # 04 — the history graph, framed like the light pass.
        if scroll_to_text "Battery history"; then
          sleep 1
          capture 04-history-dark.png
        elif wait_for_text "Battery history" 5; then
          echo "  ... dark history card is already as high as the page allows" >&2
          sleep 1
          capture 04-history-dark.png
        else
          echo "  ! could not frame the dark history card" >&2
        fi

        # 05 — Settings.
        adb shell input keyevent KEYCODE_BACK
        if wait_for_text "Cars" 20 && tap_desc "Settings" 20; then
          if wait_for_text "Vehicle key" 20; then
            sleep 1
            capture 05-settings-dark.png
          else
            echo "  ! dark settings screen did not open" >&2
          fi
          adb shell input keyevent KEYCODE_BACK
        else
          echo "  ! could not open dark settings" >&2
        fi
      else
        echo "  ! dark car detail has no reading" >&2
      fi
    else
      echo "  ! dark car detail not reachable" >&2
    fi
  else
    echo "  ! dark overview not reachable" >&2
  fi
fi

required="01-overview.png 02-scanning.png 03-car.png 04-history.png 05-settings.png"
if [ "$dark_pass" = true ]; then
  required="$required 01-overview-dark.png 02-scanning-dark.png 03-car-dark.png 04-history-dark.png 05-settings-dark.png"
fi
missing=0
for name in $required; do
  if [ ! -s "$OUT/$name" ]; then
    echo "missing required screenshot: $name" >&2
    missing=1
  fi
done
ls -l "$OUT"
if [ "$missing" -ne 0 ]; then
  echo "screenshot capture incomplete" >&2
  exit 1
fi
