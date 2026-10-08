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
# section); the committed website screenshots are not refreshed for a promoted
# release. Always exits 0; the outcome is the `found` step output.
set -euo pipefail

TAG="${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
OUT="${GITHUB_OUTPUT:-/dev/stdout}"

for rel in "$TAG" preview; do
  if [ "$rel" = preview ] && [ "$(git rev-parse -q --verify refs/tags/preview^{commit} 2>/dev/null || true)" != "$(git rev-parse HEAD)" ]; then
    continue
  fi
  body="$(gh release view "$rel" --json body --jq .body 2>/dev/null || true)"
  printf '%s' "$body" | sed -n '/^## Screenshots/,$p' > screenshots-section.md
  grep -q '<img' screenshots-section.md || { rm -f screenshots-section.md; continue; }

  echo "Reusing the screenshots of release '$rel'; skipping the emulator capture."
  echo "found=true" >> "$OUT"
  exit 0
done

echo "No preview release for this commit to reuse; the screenshots will be captured."
echo "found=false" >> "$OUT"
