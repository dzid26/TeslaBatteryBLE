#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Reuse the screenshots of a preview build when a tag is cut from the same
# commit (the preview was promoted to a release): the release then only has to
# rebuild the APK, because versionName/versionCode derive from the tag, and can
# skip the ~15 minute emulator capture.
#
# Looks for a release (the tag's own, in case the preview was re-tagged in the
# GitHub UI, or `preview`) carrying `TeslaBatteryBLE-preview-<sha7>.apk` for
# the checked-out commit. On a hit it writes `screenshots-section.md` (the
# notes' "## Screenshots" section) and downloads the hosted PNGs into
# `screenshots/`. Always exits 0; the outcome is the `found` step output.
set -euo pipefail

TAG="${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
SHORT_SHA="$(git rev-parse --short=7 HEAD)"
WANT="TeslaBatteryBLE-preview-${SHORT_SHA}.apk"
OUT="${GITHUB_OUTPUT:-/dev/stdout}"
NAMES=(02-scanning 03-car 05-settings 02-scanning-dark 03-car-dark 05-settings-dark)

for rel in "$TAG" preview; do
  json="$(gh release view "$rel" --json assets,body 2>/dev/null || true)"
  [ -n "$json" ] || continue
  printf '%s' "$json" | jq -e --arg want "$WANT" '.assets | map(.name) | index($want)' > /dev/null || continue
  printf '%s' "$json" | jq -r '.body' | tr -d '\r' | sed -n '/^## Screenshots/,$p' > screenshots-section.md
  grep -q '<img' screenshots-section.md || { rm -f screenshots-section.md; continue; }

  # Best effort: the PNGs only feed the committed README/website refresh.
  mkdir -p screenshots
  i=0
  while read -r url; do
    name="${NAMES[$i]:-}"
    i=$((i + 1))
    [ -n "$name" ] || break
    curl -fsSL "$url" -o "screenshots/$name.png" || rm -f "screenshots/$name.png"
  done < <(grep -o 'src="[^"]*"' screenshots-section.md | sed 's/^src="//;s/"$//')
  echo "Reusing the screenshots of release '$rel' ($WANT); skipping the emulator capture."
  echo "found=true" >> "$OUT"
  exit 0
done

echo "No preview build of $SHORT_SHA to reuse; the screenshots will be captured."
echo "found=false" >> "$OUT"
