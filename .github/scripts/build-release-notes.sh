#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Build release notes for a tag: the CHANGELOG section for the version plus the
# screenshots. A composed sheet is used when SCREENSHOT_SHEET_URL is set;
# otherwise the individual images are grouped by color scheme.
set -euo pipefail

TAG="${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
VERSION="${TAG#v}"
TAG_SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"
SCREENSHOT_BASE_URL="${SCREENSHOT_BASE_URL:-https://raw.githubusercontent.com/$REPO/$TAG_SHA/website/images}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./screenshot-sections.sh
source "$SCRIPT_DIR/screenshot-sections.sh"

{
  awk -v h="## [$VERSION]" '
    index($0, h) == 1 { found = 1 }
    found && /^## \[/ && index($0, h) != 1 { exit }
    found { print }
  ' CHANGELOG.md

  if [ -n "${SCREENSHOT_SHEET_URL:-}" ]; then
    echo
    echo "## Screenshots"
    echo
    echo "![App screenshots: light and dark]($SCREENSHOT_SHEET_URL)"
  else
    emit_screenshots "$SCREENSHOT_BASE_URL"
  fi
} > release-notes.md

if [ ! -s release-notes.md ]; then
  echo "See [CHANGELOG.md](https://github.com/$REPO/blob/main/CHANGELOG.md)." > release-notes.md
fi
