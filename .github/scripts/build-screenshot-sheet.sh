#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Compose a single screenshot sheet for release notes: light shots in one row,
# dark shots in a row below, on a transparent background.
#
# Usage: build-screenshot-sheet.sh <source-dir> <output.png>
set -euo pipefail

SRC="${1:?usage: build-screenshot-sheet.sh <source-dir> <output.png>}"
OUT="${2:?usage: build-screenshot-sheet.sh <source-dir> <output.png>}"

if command -v magick > /dev/null 2>&1; then
  MONTAGE="magick montage"
elif command -v montage > /dev/null 2>&1; then
  MONTAGE="montage"
else
  echo "ImageMagick montage is not available" >&2
  exit 1
fi

light=() dark=()
for file in "$SRC"/*.png; do
  [ -e "$file" ] || continue
  case "$(basename "$file")" in
    *-dark.png) dark+=("$file") ;;
    *screenshot-sheet.png) ;;
    *) light+=("$file") ;;
  esac
done
[ "${#light[@]}" -gt 0 ] || { echo "no light screenshots in $SRC" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

$MONTAGE "${light[@]}" -tile "${#light[@]}x1" -geometry 270x600+8+8 -background none "$tmp/light.png"
rows=("$tmp/light.png")
if [ "${#dark[@]}" -gt 0 ]; then
  $MONTAGE "${dark[@]}" -tile "${#dark[@]}x1" -geometry 270x600+8+8 -background none "$tmp/dark.png"
  rows+=("$tmp/dark.png")
fi
$MONTAGE "${rows[@]}" -tile 1x2 -geometry +0+12 -background none "$OUT"
