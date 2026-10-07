#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Validate the artifact a pull request workflow hands to a workflow_run comment
# poster. The artifact is untrusted. Accept only a numeric PR number whose head
# is the repository and branch the run was for, the short SHA of the run's
# commit, and the named PNGs (regular files with a PNG signature), which are
# copied to <dest-dir>. Writes `pr` and `short` to $GITHUB_OUTPUT.
#
# Usage: validate-pr-artifact.sh <artifact-dir> <dest-dir> <file.png>...
# Requires: GH_TOKEN, GITHUB_REPOSITORY, GITHUB_OUTPUT, and HEAD_SHA,
# HEAD_BRANCH and HEAD_REPO from github.event.workflow_run.
set -euo pipefail

USAGE="usage: validate-pr-artifact.sh <artifact-dir> <dest-dir> <file.png>..."
ARTIFACT_DIR="${1:?$USAGE}"
DEST_DIR="${2:?$USAGE}"
shift 2
[ "$#" -gt 0 ] || { echo "$USAGE" >&2; exit 2; }
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}" "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"
: "${HEAD_SHA:?HEAD_SHA is required}" "${HEAD_BRANCH:?HEAD_BRANCH is required}"
HEAD_REPO="${HEAD_REPO:-}"

fail() {
  echo "::error::$*"
  exit 1
}

# A regular file, not a symlink or a directory.
regular() {
  [ -f "$1" ] && [ ! -L "$1" ]
}

for meta in pr-number short-sha; do
  regular "$ARTIFACT_DIR/$meta" || fail "the artifact has no $meta file"
done
pr="$(head -c 32 "$ARTIFACT_DIR/pr-number")"
short="$(head -c 64 "$ARTIFACT_DIR/short-sha")"
if [[ ! "$pr" =~ ^[1-9][0-9]{0,9}$ ]]; then
  fail "the PR number in the artifact is not numeric"
fi
if [[ ! "$short" =~ ^[0-9a-f]{7,40}$ ]] || [[ "$HEAD_SHA" != "$short"* ]]; then
  fail "the short SHA in the artifact does not match the run's commit"
fi

pr_head="$(gh api "repos/$GITHUB_REPOSITORY/pulls/$pr" --jq '(.head.repo.full_name // "") + ":" + .head.ref')"
if [ -z "$HEAD_REPO" ] || [ "$pr_head" != "$HEAD_REPO:$HEAD_BRANCH" ]; then
  fail "PR #$pr is not the pull request the run was for"
fi

mkdir -p "$DEST_DIR"
for name in "$@"; do
  file="$ARTIFACT_DIR/$name"
  regular "$file" || fail "the artifact has no $name"
  if [ "$(head -c 8 "$file" | od -An -tx1 | tr -d ' \n')" != 89504e470d0a1a0a ]; then
    fail "$name is not a PNG"
  fi
  cp "$file" "$DEST_DIR/$name"
done

echo "pr=$pr" >> "$GITHUB_OUTPUT"
echo "short=$short" >> "$GITHUB_OUTPUT"
