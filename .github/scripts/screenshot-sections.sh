#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Emit the "## Screenshots" section as grouped light and dark rows, each sized
# as a percentage of the page width so the group always fits. Used as a
# fallback when no composed screenshot sheet is available.
#
# Usage: emit_screenshots <base-url>
# The base URL is the release's asset URL (or any prefix); file names are
# appended directly, so it must not end with a slash.
emit_screenshots() {
  local base="$1"
  local dir="website/images"
  local light=() dark=() file
  if [ -d "$dir" ]; then
    for file in "$dir"/*.png; do
      [ -e "$file" ] || continue
      case "$(basename "$file")" in
        *-dark.png) dark+=("$file") ;;
        *) light+=("$file") ;;
      esac
    done
  fi

  if [ "${#light[@]}" -eq 0 ] && [ "${#dark[@]}" -eq 0 ]; then
    return 0
  fi

  echo
  echo "## Screenshots"

  local line file_name width
  if [ "${#light[@]}" -gt 0 ]; then
    echo
    echo "**Light**"
    echo
    width=$((90 / ${#light[@]}))
    line=""
    for file in "${light[@]}"; do
      file_name="$(basename "$file")"
      line+="<img src=\"$base/$file_name\" width=\"$width%\" alt=\"${file_name%.png}\"> "
    done
    echo "${line% }"
  fi
  if [ "${#dark[@]}" -gt 0 ]; then
    echo
    echo "**Dark**"
    echo
    width=$((90 / ${#dark[@]}))
    line=""
    for file in "${dark[@]}"; do
      file_name="$(basename "$file")"
      line+="<img src=\"$base/$file_name\" width=\"$width%\" alt=\"${file_name%.png}\"> "
    done
    echo "${line% }"
  fi
}
