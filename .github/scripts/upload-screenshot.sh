#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Upload a file (screenshot or APK) and print a URL that can be embedded in GitHub content.
#
# Uses GitHub's user-attachments endpoint, the same one the web UI's
# drag-and-drop uses. The endpoint rejects the Actions GITHUB_TOKEN, so it needs
# a user token in ATTACHMENTS_TOKEN (a classic PAT with repo scope). There is no
# release-asset fallback: release pages must not carry preview assets.
#
# Usage: upload-screenshot.sh <file> [asset-name] [content-type]
#   content-type defaults to image/png; APKs want application/vnd.android.package-archive.
# Requires: ATTACHMENTS_TOKEN, GH_TOKEN (for the repo lookup), GITHUB_REPOSITORY.
set -euo pipefail

FILE="${1:?usage: upload-screenshot.sh <file> [asset-name] [content-type]}"
NAME="${2:-$(basename "$FILE")}"
TYPE="${3:-image/png}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${ATTACHMENTS_TOKEN:?ATTACHMENTS_TOKEN is required (a user token; the Actions token is rejected by the endpoint)}"

repository_id="$(gh api "repos/$REPO" --jq .id)"
response="$(curl -sS "https://uploads.github.com/user-attachments/assets?name=$NAME&content_type=$TYPE&repository_id=$repository_id" \
  -X POST -H "Authorization: Bearer $ATTACHMENTS_TOKEN" -H "Accept: application/json" \
  --data-binary "@$FILE")"
url="$(printf '%s' "$response" | sed -n 's/.*"url":[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
if [ -z "$url" ]; then
  echo "user-attachments upload failed; response: $response" >&2
  exit 1
fi
printf '%s\n' "$url"
