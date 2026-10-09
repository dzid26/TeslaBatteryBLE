#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Keep a draft "next release" that lists the changes on main since the last
# release tag. It has no tag, APK or screenshots: the tag release job builds
# those and publishes this draft as the real release.
set -euo pipefail

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"
DRAFT_NAME="Next release (draft)"

git fetch --tags origin

# Nearest release tag reachable from this commit.
PREV_TAG="$(git describe --tags --abbrev=0 --match 'v*' "$SHA" 2>/dev/null || true)"

# The existing draft, if any; it is updated in place so settings made on it by
# hand (such as the pre-release checkbox) survive later pushes.
DRAFT_ID="$(gh api --paginate "repos/$REPO/releases" \
  --jq '.[] | select(.draft and .name == "'"$DRAFT_NAME"'") | .id' | sed -n 1p)"

# If the newest release is cut from this commit there are no un-released
# changes: leave no draft behind.
if [ -n "$PREV_TAG" ]; then
  prev_sha="$(git rev-parse -q --verify "refs/tags/$PREV_TAG^{commit}" 2>/dev/null || true)"
  if [ -n "$prev_sha" ] && [ "$prev_sha" = "$SHA" ]; then
    if [ -n "$DRAFT_ID" ]; then gh api -X DELETE "repos/$REPO/releases/$DRAFT_ID"; fi
    echo "No un-released changes since $PREV_TAG; no draft."
    exit 0
  fi
fi

{
  echo "Draft of the next release - updated on every push to \`main\`."
  echo
  if [ -n "$PREV_TAG" ]; then
    echo "## Changes since [$PREV_TAG](https://github.com/$REPO/releases/tag/$PREV_TAG)"
  else
    echo "## Changes"
  fi
  echo
  if [ -n "$PREV_TAG" ]; then
    bash .github/scripts/changelog.sh "$REPO" "$PREV_TAG..$SHA"
  else
    bash .github/scripts/changelog.sh "$REPO" "$SHA"
  fi
  echo
  if [ -n "$PREV_TAG" ]; then
    echo
    echo "**Full diff**: https://github.com/$REPO/compare/$PREV_TAG...${SHA:0:7}"
  fi
} > draft-notes.md

if [ -n "$DRAFT_ID" ]; then
  gh api -X PATCH "repos/$REPO/releases/$DRAFT_ID" \
    -f target_commitish="$SHA" -F body=@draft-notes.md > /dev/null
else
  # A draft has no git tag until it is published; this name is only a placeholder.
  gh api -X POST "repos/$REPO/releases" \
    -f tag_name=next-release -f name="$DRAFT_NAME" -f target_commitish="$SHA" \
    -F draft=true -F body=@draft-notes.md > /dev/null
fi
