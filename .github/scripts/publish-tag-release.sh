#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Publish a tagged release: the changes since the previous tag plus the
# screenshots captured by CI. Run from a checkout with the debug APK
# built and the emulator screenshots captured into `screenshots/`.
set -euo pipefail

TAG="${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
APK="dist/TeslaBatteryBLE-${TAG}.apk"
NOTES="release-notes.md"
SHOTS_DIR="screenshots"
# README-table-style columns: the scan, the car detail, Settings; light row + dark row.
SHOTS="02-scanning.png 03-car.png 05-settings.png 02-scanning-dark.png 03-car-dark.png 05-settings-dark.png"

if [ "$TAG" != "${TAG%-*}" ]; then PRE_FLAG="--prerelease"; else PRE_FLAG=""; fi

[ -f "$APK" ] || { echo "missing $APK (the release job stages it)" >&2; exit 1; }

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
  echo "**Light**"
  echo
  echo "<p><img src=\"$SCAN_URL\" alt=\"Scanning for nearby cars (light)\" width=\"32%\"><img src=\"$CAR_URL\" alt=\"Car detail with the battery reading (light)\" width=\"32%\"><img src=\"$SETTINGS_URL\" alt=\"Settings (light)\" width=\"32%\"></p>"
  echo
  echo "**Dark**"
  echo
  echo "<p><img src=\"$SCAN_DARK_URL\" alt=\"Scanning for nearby cars (dark)\" width=\"32%\"><img src=\"$CAR_DARK_URL\" alt=\"Car detail with the battery reading (dark)\" width=\"32%\"><img src=\"$SETTINGS_DARK_URL\" alt=\"Settings (dark)\" width=\"32%\"></p>"
} > "$NOTES"

if [ ! -s "$NOTES" ]; then
  echo "See the [commit history](https://github.com/$REPO/commits/$TAG)." > "$NOTES"
fi

if gh release view "$TAG" > /dev/null 2>&1; then
  gh release edit "$TAG" --title "$TAG" --notes-file "$NOTES" $PRE_FLAG
else
  gh release create "$TAG" --title "$TAG" --notes-file "$NOTES" $PRE_FLAG
fi

# Uploads are flaky; retry before failing the release.
for attempt in 1 2 3; do
  gh release upload "$TAG" "$APK" --clobber && break
  [ "$attempt" -lt 3 ] || { echo "uploading $APK failed after $attempt attempts" >&2; exit 1; }
  sleep $((attempt * 5))
done

# The release is not done until its APK is actually attached.
assets="$(gh release view "$TAG" --json assets --jq '.assets[].name')"
expected="$(basename "$APK")"
printf '%s\n' "$assets" | grep -qx "$expected" || { echo "release $TAG is missing $expected" >&2; exit 1; }

# A separate rolling `preview` release cut from this same commit is now redundant.
if [ "$(git rev-parse -q --verify "refs/tags/preview^{commit}" 2>/dev/null || true)" = "$(git rev-parse HEAD)" ]; then
  gh release delete preview --yes --cleanup-tag
fi
