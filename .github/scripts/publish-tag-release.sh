#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Publish a tagged release: the CHANGELOG section plus the screenshot sheet
# captured by CI. Run from a checkout with the debug APK built and the
# emulator screenshots captured into `screenshots/`.
set -euo pipefail

TAG="${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
VERSION="${TAG#v}"
APK="dist/TeslaBatteryBLE-${TAG}.apk"
NOTES="release-notes.md"
SHEET="screenshots/screenshot-sheet.png"

if [ "$TAG" != "${TAG%-*}" ]; then PRE_FLAG="--prerelease"; else PRE_FLAG=""; fi

[ -f "$APK" ] || { echo "missing $APK (the build job stages it for tags)" >&2; exit 1; }

[ -f "$SHEET" ] || { echo "missing $SHEET (the capture step must succeed)" >&2; exit 1; }
# Host the sheet as a user attachment so the release page keeps only the APK.
SHEET_URL="$(bash .github/scripts/upload-screenshot.sh "$SHEET" "screenshot-sheet-${TAG}.png" screenshots)"

{
  awk -v h="## [$VERSION]" '
    index($0, h) == 1 { found = 1 }
    found && /^## \[/ && index($0, h) != 1 { exit }
    found { print }
  ' CHANGELOG.md

  if [ -f "$SHEET" ]; then
    echo
    echo "## Screenshots"
    echo
    echo "![App screenshots: light and dark]($SHEET_URL)"
  fi
} > "$NOTES"

if [ ! -s "$NOTES" ]; then
  echo "See [CHANGELOG.md](https://github.com/$REPO/blob/main/CHANGELOG.md)." > "$NOTES"
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
