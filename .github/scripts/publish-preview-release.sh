#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Create or update the rolling "preview" release for the current commit.
#
# Run from a checkout of the repository with `dist/app-debug.apk` already built.
# Screenshots ship as release assets: one composed sheet when ImageMagick is
# available, otherwise the individual images.
set -euo pipefail

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"
SHORT_SHA="$(git rev-parse --short "$SHA")"
TAG="preview"
TITLE="Preview build"
APK="dist/app-debug.apk"
ASSET_APK="TeslaBatteryBLE-preview-$SHORT_SHA.apk"
SITE_IMAGES_DIR="website/images"
SHEET="screenshot-sheet.png"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./screenshot-sections.sh
source "$SCRIPT_DIR/screenshot-sections.sh"

SCREENSHOT_BASE_URL="https://github.com/$REPO/releases/download/$TAG"
SHEET_OK="false"
if bash "$SCRIPT_DIR/build-screenshot-sheet.sh" "$SHEET"; then
  SHEET_OK="true"
  SCREENSHOT_SHEET_URL="$SCREENSHOT_BASE_URL/$SHEET"
fi

[ -f "$APK" ] || { echo "missing $APK" >&2; exit 1; }
cp "$APK" "$ASSET_APK"

git fetch --tags origin

# Most recent release that is not the rolling preview itself.
PREV_TAG="$(gh release list --limit 100 --json tagName,createdAt \
  --jq '[.[] | select(.tagName != "preview")] | sort_by(.createdAt) | reverse | .[0].tagName // empty')"

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

{
  echo "Rolling preview of \`main\` — rebuilt on every push. The APK is a debug build."
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
  if [ "$SHEET_OK" = "true" ]; then
    echo
    echo "## Screenshots"
    echo
    echo "![App screenshots: light and dark]($SCREENSHOT_SHEET_URL)"
  else
    emit_screenshots "$SCREENSHOT_BASE_URL"
  fi
} > preview-notes.md

if gh release view "$TAG" > /dev/null 2>&1; then
  # Drop stale SHA-named APK assets from previous runs so old builds never linger.
  while read -r name; do
    [ -n "$name" ] || continue
    gh release delete-asset "$TAG" "$name" --yes || true
  done < <(gh release view "$TAG" --json assets --jq '.assets[].name | select(endswith(".apk"))')
  gh release edit "$TAG" --title "$TITLE" --prerelease --notes-file preview-notes.md
else
  gh release create "$TAG" --title "$TITLE" --prerelease --notes-file preview-notes.md "$ASSET_APK"
fi

gh release upload "$TAG" "$ASSET_APK" --clobber
if [ "$SHEET_OK" = "true" ]; then
  gh release upload "$TAG" "$SHEET" --clobber
elif compgen -G "$SITE_IMAGES_DIR/*.png" > /dev/null; then
  gh release upload "$TAG" "$SITE_IMAGES_DIR"/*.png --clobber
fi
