#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Print release-note bullets for a revision range, one per change on the main
# line: the title of each merged pull request (GitHub puts it on the first line
# of the merge commit body) and the subject of any commit pushed directly. Only
# changes that touch the app or its build are listed; workflow, docs, website and
# tooling changes are left out.
# Usage: changelog.sh <owner/repo> <range-or-rev>
set -euo pipefail

REPO="${1:?usage: changelog.sh <owner/repo> <range-or-rev>}"
RANGE="${2:?usage: changelog.sh <owner/repo> <range-or-rev>}"

# What counts as "the app": its sources, the protocol core it ships, and the build.
APP_PATHS=(app core gradle config build.gradle.kts settings.gradle.kts gradle.properties)

git log --first-parent --format=%H "$RANGE" | while read -r sha; do
  # The change is what the commit (or merge) brings in over its first parent.
  if git diff --quiet "$sha^1" "$sha" -- "${APP_PATHS[@]}" 2>/dev/null; then continue; fi
  subject="$(git log -1 --format=%s "$sha")"
  case "$subject" in
    "Refresh screenshots from "*) continue ;;
    "Merge pull request #"*)
      number="${subject#Merge pull request #}"
      number="${number%% *}"
      title="$(git log -1 --format=%b "$sha" | sed -n '/./{p;q;}')"
      echo "- ${title:-$subject} ([#$number](https://github.com/$REPO/pull/$number))"
      ;;
    *)
      echo "- $subject ([${sha:0:7}](https://github.com/$REPO/commit/$sha))"
      ;;
  esac
done
