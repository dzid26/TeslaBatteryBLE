#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Publish a tagged release.
#
# If the rolling preview release points at this commit, convert it into the
# tagged release (move the release to the new tag, update title/notes, replace
# the asset) instead of deleting it and creating a new one. Otherwise fall
# back to creating a fresh release.
#
# Screenshots ship as release assets: one composed sheet when ImageMagick is
# available, otherwise the individual images.
set -euo pipefail

TAG="${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"
APK="app/build/outputs/apk/debug/app-debug.apk"
ASSET_APK="TeslaBatteryBLE-${TAG}.apk"
NOTES="release-notes.md"
SITE_IMAGES_DIR="website/images"
SHEET="screenshot-sheet.png"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$TAG" in
  *-*) PRERELEASE_JSON="true" ;;
  *) PRERELEASE_JSON="false" ;;
esac

[ -f "$APK" ] || { echo "missing $APK" >&2; exit 1; }
cp "$APK" "$ASSET_APK"

SHEET_OK="false"
if bash "$SCRIPT_DIR/build-screenshot-sheet.sh" "$SHEET"; then
  SHEET_OK="true"
fi
export SCREENSHOT_BASE_URL="https://github.com/$REPO/releases/download/$TAG"
if [ "$SHEET_OK" = "true" ]; then
  export SCREENSHOT_SHEET_URL="$SCREENSHOT_BASE_URL/$SHEET"
fi
bash "$SCRIPT_DIR/build-release-notes.sh"

git fetch --tags origin

REUSED="false"
PREVIEW_ID="$(gh release view preview --json databaseId --jq '.databaseId' 2>/dev/null || true)"
if [ -n "$PREVIEW_ID" ]; then
  PREVIEW_SHA="$(git rev-parse -q --verify 'refs/tags/preview^{commit}' 2>/dev/null || true)"
  if [ -n "$PREVIEW_SHA" ] && [ "$PREVIEW_SHA" = "$SHA" ]; then
    jq -n --arg tag "$TAG" --arg name "$TAG" --arg body "$(cat "$NOTES")" \
      --argjson pre "$PRERELEASE_JSON" \
      '{tag_name: $tag, name: $name, prerelease: $pre, body: $body}' > release-payload.json
    if gh api -X PATCH "repos/$REPO/releases/$PREVIEW_ID" --input release-payload.json > /dev/null; then
      REUSED="true"
      echo "Converted the rolling preview release into $TAG."
    else
      echo "Preview conversion failed; creating a new release instead."
    fi
  fi
fi

if [ "$REUSED" = "true" ]; then
  gh release upload "$TAG" "$ASSET_APK" --clobber
  while read -r asset; do
    [ -n "$asset" ] || continue
    gh release delete-asset "$TAG" "$asset" --yes || true
  done < <(gh release view "$TAG" --json assets --jq '.assets[].name | select(startswith("TeslaBatteryBLE-preview-"))')
  # The release no longer points at the rolling tag.
  git push origin ":refs/tags/preview" || true
elif [ "$PRERELEASE_JSON" = "true" ]; then
  gh release create "$TAG" --title "$TAG" --prerelease --notes-file "$NOTES" "$ASSET_APK"
else
  gh release create "$TAG" --title "$TAG" --notes-file "$NOTES" "$ASSET_APK"
fi

if [ "$SHEET_OK" = "true" ]; then
  gh release upload "$TAG" "$SHEET" --clobber
  # Drop stale individual screenshots now that the sheet is used.
  while read -r asset; do
    [ -n "$asset" ] || continue
    gh release delete-asset "$TAG" "$asset" --yes || true
  done < <(gh release view "$TAG" --json assets --jq '.assets[].name | select(endswith(".png")) | select(. != "screenshot-sheet.png")')
elif compgen -G "$SITE_IMAGES_DIR/*.png" > /dev/null; then
  gh release upload "$TAG" "$SITE_IMAGES_DIR"/*.png --clobber
fi
