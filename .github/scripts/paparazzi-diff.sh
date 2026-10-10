#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Record the Paparazzi card snapshots for two commits and byte-compare them.
#
# Usage: paparazzi-diff.sh [before-ref] [after-ref] [before-dir] [after-dir]
#
# before-ref defaults to HEAD~1 (the pushed commit's parent, not the merge
# base) and after-ref to HEAD. Run from anywhere inside the repo; the working
# tree must be clean, since both commits are checked out in turn. The snapshot
# dirs default to fresh temporary directories; pass explicit dirs (for
# example $RUNNER_TEMP/before and $RUNNER_TEMP/after) to keep the PNGs.
#
# Prints a small report to stdout (progress goes to stderr):
#   after_dir <path> / before_dir <path>
#   after_sha <short> / before_sha <short|none>
#   changed <name> (one line per differing or new snapshot)
#   unchanged (when nothing differs)
#
# Exit 0 whether or not anything differs: this is a local review tool, not a
# gate. Non-zero only when the after record itself fails. The before record
# is best-effort (the parent may predate Paparazzi): when it fails every
# after snapshot counts as new and before_sha is "none". The original HEAD
# checkout is always restored, even on failure.
set -euo pipefail

BEFORE_REF="${1:-HEAD~1}"
AFTER_REF="${2:-HEAD}"

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "error: the working tree is not clean; commit or stash first" >&2
    exit 2
fi
cd "$(git rev-parse --show-toplevel)"
ORIG_BRANCH="$(git symbolic-ref --short --quiet HEAD || true)"
ORIG_REF="$(git rev-parse HEAD)"

BEFORE_DIR=""
AFTER_DIR=""
CLEAN_BEFORE=false
CLEAN_AFTER=false
if [ "$#" -ge 3 ]; then
    BEFORE_DIR="$3"
else
    BEFORE_DIR="$(mktemp -d)"
    CLEAN_BEFORE=true
fi
if [ "$#" -ge 4 ]; then
    AFTER_DIR="$4"
else
    AFTER_DIR="$(mktemp -d)"
    CLEAN_AFTER=true
fi

finish() {
    if [ -n "$ORIG_BRANCH" ]; then
        git checkout --quiet "$ORIG_BRANCH" || echo "::warning::could not restore $ORIG_BRANCH" >&2
    else
        git checkout --quiet "$ORIG_REF" || echo "::warning::could not restore $ORIG_REF" >&2
    fi
    rm -rf app/src/test/snapshots
    if [ "$CLEAN_BEFORE" = true ]; then
        rm -rf "$BEFORE_DIR"
    fi
    if [ "$CLEAN_AFTER" = true ]; then
        rm -rf "$AFTER_DIR"
    fi
}
trap finish EXIT

# Check out <ref>, record its snapshots, and copy the 24 card PNGs (kept by
# their trailing <card>_<state>_<theme> name) into <dir>. Prints how many
# were recorded; anything else goes to stderr.
render() {
    local ref="$1" dir="$2"
    git checkout --quiet "$ref"
    rm -rf app/src/test/snapshots
    ./gradlew :app:recordPaparazziDebug --console=plain >&2
    mkdir -p "$dir"
    local count=0 base file
    shopt -s nullglob
    for file in app/src/test/snapshots/images/*.png; do
        base="$(basename "$file")"
        if [[ "$base" =~ (vehicle-card|hero-card)_[a-z-]+_(light|dark)\.png$ ]]; then
            cp "$file" "$dir/${BASH_REMATCH[0]}"
            count=$((count + 1))
        else
            echo "::warning::unexpected snapshot name: $base" >&2
        fi
    done
    shopt -u nullglob
    echo "$count"
}

echo "Recording the after snapshots ($AFTER_REF)..." >&2
AFTER_COUNT="$(render "$AFTER_REF" "$AFTER_DIR")"
if [ "$AFTER_COUNT" -eq 0 ]; then
    echo "::error::no card snapshots were recorded for $AFTER_REF" >&2
    exit 1
fi
echo "Recorded $AFTER_COUNT after snapshots." >&2
AFTER_SHA="$(git rev-parse --short HEAD)"

echo "Recording the before snapshots ($BEFORE_REF)..." >&2
BEFORE_SHA="none"
if BEFORE_COUNT="$(render "$BEFORE_REF" "$BEFORE_DIR")"; then
    if [ "$BEFORE_COUNT" -gt 0 ]; then
        echo "Recorded $BEFORE_COUNT before snapshots." >&2
        BEFORE_SHA="$(git rev-parse --short HEAD)"
    else
        echo "::warning::no card snapshots were recorded for $BEFORE_REF; every after snapshot counts as new" >&2
    fi
else
    echo "::warning::before snapshots unavailable for $BEFORE_REF (the parent may predate Paparazzi); every after snapshot counts as new" >&2
fi

echo "after_dir $AFTER_DIR"
echo "before_dir $BEFORE_DIR"
echo "after_sha $AFTER_SHA"
echo "before_sha $BEFORE_SHA"
CHANGED=0
shopt -s nullglob
for after in "$AFTER_DIR"/*.png; do
    name="$(basename "$after")"
    if [ ! -f "$BEFORE_DIR/$name" ]; then
        echo "new snapshot: $name" >&2
        echo "changed $name"
        CHANGED=$((CHANGED + 1))
    elif ! cmp -s "$after" "$BEFORE_DIR/$name"; then
        echo "changed snapshot: $name" >&2
        echo "changed $name"
        CHANGED=$((CHANGED + 1))
    fi
done
shopt -u nullglob
if [ "$CHANGED" -eq 0 ]; then
    echo "unchanged"
    echo "No card snapshot changed." >&2
else
    echo "$CHANGED card snapshot(s) changed." >&2
fi
