#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Create or update the rolling "preview" release for the current commit.
#
# Run from a checkout with `dist/app-debug.apk` built and the emulator
# screenshots captured into `screenshots/` (see android.yml).
set -euo pipefail

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"
TAG="preview"
TITLE="Preview build"
SHORT_SHA="${SHA:0:7}"
APK="dist/app-debug.apk"
ASSET_APK="TeslaBatteryBLE-preview-${SHORT_SHA}.apk"
SHOTS_DIR="screenshots"
# README-table-style columns: the scan, the car detail, Settings; light row + dark row.
SHOTS="02-scanning.png 03-car.png 05-settings.png 02-scanning-dark.png 03-car-dark.png 05-settings-dark.png"

[ -f "$APK" ] || { echo "missing $APK" >&2; exit 1; }
cp "$APK" "$ASSET_APK"

git fetch --tags origin

# Nearest release tag reachable from this commit. Release creation times are
# not reliable: converted previews keep the preview's original createdAt.
PREV_TAG="$(git describe --tags --abbrev=0 --match 'v*' "$SHA" 2>/dev/null || true)"

# If the newest release is cut from this commit there are no un-released
# changes: drop the rolling preview instead of publishing an empty one.
if [ -n "$PREV_TAG" ]; then
  prev_sha="$(git rev-parse -q --verify "refs/tags/$PREV_TAG^{commit}" 2>/dev/null || true)"
  if [ -n "$prev_sha" ] && [ "$prev_sha" = "$SHA" ]; then
    if gh release view "$TAG" > /dev/null 2>&1; then
      gh release delete "$TAG" --yes --cleanup-tag || true
      echo "No un-released changes since $PREV_TAG; removed the rolling preview."
    else
      echo "No un-released changes since $PREV_TAG; nothing to publish."
    fi
    exit 0
  fi
fi

# Move the rolling tag to this build.
git tag -f "$TAG" "$SHA"
git push origin "refs/tags/$TAG" --force

for name in $SHOTS; do
  [ -f "$SHOTS_DIR/$name" ] || { echo "missing $SHOTS_DIR/$name (the capture step must succeed)" >&2; exit 1; }
done
# Host the shots as user attachments so the release page keeps only the APK.
SCAN_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/02-scanning.png" "02-scanning-${SHORT_SHA}.png")"
CAR_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/03-car.png" "03-car-${SHORT_SHA}.png")"
SETTINGS_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/05-settings.png" "05-settings-${SHORT_SHA}.png")"
SCAN_DARK_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/02-scanning-dark.png" "02-scanning-dark-${SHORT_SHA}.png")"
CAR_DARK_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/03-car-dark.png" "03-car-dark-${SHORT_SHA}.png")"
SETTINGS_DARK_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/05-settings-dark.png" "05-settings-dark-${SHORT_SHA}.png")"

{
  echo "Rolling preview of \`main\` - rebuilt on every push. The APK is a debug build."
  echo
  echo "**APK**: [\`$ASSET_APK\`](https://github.com/$REPO/releases/download/$TAG/$ASSET_APK)"
  echo
  if [ -n "$PREV_TAG" ]; then
    echo "## Changes since [$PREV_TAG](https://github.com/$REPO/releases/tag/$PREV_TAG)"
  else
    echo "## Changes"
  fi
  echo
  if [ -n "$PREV_TAG" ]; then
    git log --no-merges --pretty=format:'- %s ([%h](https://github.com/'"$REPO"'/commit/%H))' "$PREV_TAG..$SHA" || true
  else
    git log --no-merges --pretty=format:'- %s ([%h](https://github.com/'"$REPO"'/commit/%H))' "$SHA" || true
  fi
  echo
  if [ -n "$PREV_TAG" ]; then
    echo
    echo "**Full diff**: https://github.com/$REPO/compare/$PREV_TAG...$TAG"
  fi
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
} > preview-notes.md

# Recreate the release on every build so the "released" time matches the
# commit; editing in place keeps the original creation date.
if gh release view "$TAG" > /dev/null 2>&1; then
  gh release delete "$TAG" --yes
fi
gh release create "$TAG" --title "$TITLE" --prerelease --notes-file preview-notes.md

gh release upload "$TAG" "$ASSET_APK" --clobber

# The release is not done until its APK is actually attached.
assets="$(gh release view "$TAG" --json assets --jq '.assets[].name')"
printf '%s\n' "$assets" | grep -qx "$ASSET_APK" || { echo "release $TAG is missing $ASSET_APK" >&2; exit 1; }
