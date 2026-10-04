#!/usr/bin/env bash
# Create or update the rolling "preview" release for the current commit.
#
# Run from a checkout of the repository with `dist/app-debug.apk` already built
# and, optionally, screenshots in `screenshots/`.
set -euo pipefail

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"
SHORT_SHA="$(git rev-parse --short "$SHA")"
TAG="preview"
TITLE="Preview build"
APK="dist/app-debug.apk"
SCREENSHOTS_DIR="screenshots"
ASSET_APK="TeslaBatteryBLE-preview-$SHORT_SHA.apk"

[ -f "$APK" ] || { echo "missing $APK" >&2; exit 1; }
cp "$APK" "$ASSET_APK"

git fetch --tags origin

# Most recent release that is not the rolling preview itself.
PREV_TAG="$(gh release list --limit 100 --json tagName,createdAt \
  --jq '[.[] | select(.tagName != "preview")] | sort_by(.createdAt) | reverse | .[0].tagName // empty')"

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
  if [ -d "$SCREENSHOTS_DIR" ] && compgen -G "$SCREENSHOTS_DIR/*.png" > /dev/null; then
    echo
    echo "## Screenshots"
    echo
    for screenshot in "$SCREENSHOTS_DIR"/*.png; do
      name="$(basename "$screenshot")"
      echo "<img src=\"https://github.com/$REPO/releases/download/$TAG/$name\" width=\"360\" alt=\"${name%.png}\">"
    done
  fi
} > preview-notes.md

if gh release view "$TAG" > /dev/null 2>&1; then
  # Drop assets from the previous run (SHA-named APK, screenshots) so stale
  # files never linger.
  while read -r name; do
    [ -n "$name" ] || continue
    gh release delete-asset "$TAG" "$name" --yes || true
  done < <(gh release view "$TAG" --json assets --jq '.assets[].name | select(endswith(".png") or endswith(".apk"))')
  gh release edit "$TAG" --title "$TITLE" --prerelease --notes-file preview-notes.md
else
  gh release create "$TAG" --title "$TITLE" --prerelease --notes-file preview-notes.md "$ASSET_APK"
fi

gh release upload "$TAG" "$ASSET_APK" --clobber
if [ -d "$SCREENSHOTS_DIR" ] && compgen -G "$SCREENSHOTS_DIR/*.png" > /dev/null; then
  gh release upload "$TAG" "$SCREENSHOTS_DIR"/*.png --clobber
fi
