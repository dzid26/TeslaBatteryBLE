#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Build release notes for a tag: the CHANGELOG section for the version plus
# screenshots served from the deployed GitHub Pages site.
set -euo pipefail

TAG="${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
VERSION="${TAG#v}"
SITE_IMAGES_DIR="website/images"

# GitHub Pages origin for the deployed site images (owner is lowercased).
OWNER="${REPO%/*}"
NAME="${REPO#*/}"
PAGES_ORIGIN="https://$(printf '%s' "$OWNER" | tr '[:upper:]' '[:lower:]').github.io/$NAME"

{
  awk -v h="## [$VERSION]" '
    index($0, h) == 1 { found = 1 }
    found && /^## \[/ && index($0, h) != 1 { exit }
    found { print }
  ' CHANGELOG.md

  if [ -d "$SITE_IMAGES_DIR" ] && compgen -G "$SITE_IMAGES_DIR/*.png" > /dev/null; then
    echo
    echo "## Screenshots"
    echo
    for screenshot in "$SITE_IMAGES_DIR"/*.png; do
      file_name="$(basename "$screenshot")"
      echo "<img src=\"$PAGES_ORIGIN/images/$file_name\" width=\"360\" alt=\"${file_name%.png}\">"
    done
  fi
} > release-notes.md

if [ ! -s release-notes.md ]; then
  echo "See [CHANGELOG.md](https://github.com/$REPO/blob/main/CHANGELOG.md)." > release-notes.md
fi
