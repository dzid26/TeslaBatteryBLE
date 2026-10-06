#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Upload a screenshot and print a URL that can be embedded in GitHub content.
#
# Prefers GitHub's user-attachments endpoint (the same one the web UI's
# drag-and-drop uses), so release pages keep only their APK. Falls back to an
# asset on the hidden `screenshots` release when the endpoint is unavailable,
# so an embed URL always exists.
#
# Usage: upload-screenshot.sh <file> [asset-name] [fallback-release-tag]
# Requires: GH_TOKEN (contents:write) and GITHUB_REPOSITORY.
set -euo pipefail

FILE="${1:?usage: upload-screenshot.sh <file> [asset-name] [fallback-tag]}"
NAME="${2:-$(basename "$FILE")}"
TAG="${3:-screenshots}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GH_TOKEN:?GH_TOKEN is required}"

repository_id="$(gh api "repos/$REPO" --jq .id)"
response="$(curl -sS "https://uploads.github.com/user-attachments/assets?name=$NAME&content_type=image/png&repository_id=$repository_id" \
  -X POST -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/json" \
  --data-binary "@$FILE" || true)"
url="$(printf '%s' "$response" | sed -n 's/.*"url":[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
if [ -n "$url" ]; then
  printf '%s\n' "$url"
  exit 0
fi

echo "user-attachments upload failed; falling back to the $TAG release asset" >&2
cp "$FILE" "$NAME"
if ! gh release view "$TAG" > /dev/null 2>&1; then
  gh release create "$TAG" --title "Screenshots" --prerelease \
    --notes "Hosted screenshots for release notes and PR comments." > /dev/null
fi
gh release upload "$TAG" "$NAME" --clobber > /dev/null
printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$TAG" "$NAME"
