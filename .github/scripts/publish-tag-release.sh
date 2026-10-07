#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Publish a tagged release: the changes since the previous tag plus the
# screenshots captured by CI. Run from a checkout with the debug APK
# built and the emulator screenshots captured into `screenshots/`.
set -euo pipefail

TAG="${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
VERSION="${TAG#v}"
APK="dist/TeslaBatteryBLE-${TAG}.apk"
NOTES="release-notes.md"
SHOTS_DIR="screenshots"
# README-table-style columns: the scan, the car detail, Settings; light row + dark row.
SHOTS="02-scanning.png 03-car.png 05-settings.png 02-scanning-dark.png 03-car-dark.png 05-settings-dark.png"

if [ "$TAG" != "${TAG%-*}" ]; then PRE_FLAG="--prerelease"; else PRE_FLAG=""; fi

[ -f "$APK" ] || { echo "missing $APK (the build job stages it for tags)" >&2; exit 1; }

for name in $SHOTS; do
  [ -f "$SHOTS_DIR/$name" ] || { echo "missing $SHOTS_DIR/$name (the capture step must succeed)" >&2; exit 1; }
done
# Host the shots as user attachments so the release page keeps only the APK.
SCAN_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/02-scanning.png" "02-scanning-${TAG}.png")"
CAR_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/03-car.png" "03-car-${TAG}.png")"
SETTINGS_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/05-settings.png" "05-settings-${TAG}.png")"
SCAN_DARK_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/02-scanning-dark.png" "02-scanning-dark-${TAG}.png")"
CAR_DARK_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/03-car-dark.png" "03-car-dark-${TAG}.png")"
SETTINGS_DARK_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/05-settings-dark.png" "05-settings-dark-${TAG}.png")"

# Notes come from the commits since the previous tag; there is no
# hand-edited changelog, so pull requests never collide on one.
git fetch --tags origin
PREV_TAG="$(git describe --tags --abbrev=0 --match 'v*' "$TAG^" 2>/dev/null || true)"

{
  if [ -n "$PREV_TAG" ]; then
    echo "Changes since [$PREV_TAG](https://github.com/$REPO/releases/tag/$PREV_TAG):"
  else
    echo "Changes:"
  fi
  echo
  if [ -n "$PREV_TAG" ]; then
    git log --no-merges --pretty=format:'- %s ([%h](https://github.com/'"$REPO"'/commit/%H))' "$PREV_TAG..$TAG"
  else
    git log --no-merges --pretty=format:'- %s ([%h](https://github.com/'"$REPO"'/commit/%H))' "$TAG"
  fi
  echo

  echo
  echo "## Screenshots"
  echo
  echo "| Theme | Scanning | Car | Settings |"
  echo "| --- | --- | --- | --- |"
  echo "| Light | ![Scanning for nearby cars (light)]($SCAN_URL) | ![Car detail with the battery reading (light)]($CAR_URL) | ![Settings (light)]($SETTINGS_URL) |"
  echo "| Dark | ![Scanning for nearby cars (dark)]($SCAN_DARK_URL) | ![Car detail with the battery reading (dark)]($CAR_DARK_URL) | ![Settings (dark)]($SETTINGS_DARK_URL) |"
} > "$NOTES"

if [ ! -s "$NOTES" ]; then
  echo "See the [commit history](https://github.com/$REPO/commits/$TAG)." > "$NOTES"
fi

if gh release view "$TAG" > /dev/null 2>&1; then
  gh release edit "$TAG" --title "$TAG" --notes-file "$NOTES" $PRE_FLAG
else
  gh release create "$TAG" --title "$TAG" --notes-file "$NOTES" $PRE_FLAG
fi

gh release upload "$TAG" "$APK" --clobber

# The release is not done until its APK is actually attached.
assets="$(gh release view "$TAG" --json assets --jq '.assets[].name')"
for expected in "$(basename "$APK")"; do
  printf '%s\n' "$assets" | grep -qx "$expected" || { echo "release $TAG is missing $expected" >&2; exit 1; }
done
