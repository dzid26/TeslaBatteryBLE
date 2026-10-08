#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Reuse the screenshots of a preview build when a tag is cut from the same
# commit (the preview was promoted to a release): the release then only has to
# rebuild the APK, because versionName/versionCode derive from the tag, and can
# skip the ~15 minute emulator capture.
#
# Looks for a release to promote: the tag's own (the preview was re-tagged in
# the GitHub UI) or `preview` when its tag points at the checked-out commit.
# On a hit it writes `screenshots-section.md` (the notes' "## Screenshots"
# section) and downloads the hosted PNGs into `screenshots/`. Always exits 0;
# the outcome is the `found` step output.
set -euo pipefail

TAG="${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
OUT="${GITHUB_OUTPUT:-/dev/stdout}"
NAMES=(02-scanning 03-car 05-settings 02-scanning-dark 03-car-dark 05-settings-dark)

for rel in "$TAG" preview; do
  if [ "$rel" = preview ] && [ "$(git rev-parse -q --verify refs/tags/preview^{commit} 2>/dev/null || true)" != "$(git rev-parse HEAD)" ]; then
    continue
  fi
  body="$(gh release view "$rel" --json body --jq .body 2>/dev/null || true)"
  printf '%s' "$body" | tr -d 'tr -d '' | sed15' | sed -n '/^## Screenshots/,$p' > screenshots-section.md
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
  echo "Reusing the screenshots of release '$rel'; skipping the emulator capture."
  echo "found=true" >> "$OUT"
  exit 0
done

echo "No preview release for this commit to reuse; the screenshots will be captured."
echo "found=false" >> "$OUT"
