#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# The checks every build must pass before an APK is produced: protocol and app
# unit tests, Android lint and the static analysis. Shared by the CI jobs and
# the release job, so a release is verified exactly like a pull request.
#
# Usage: verify.sh [tests|static|all]   (default: all)
#   tests   protocol and app unit tests
#   static  Android lint (debug and release), ktlint and type-resolved detekt
# CI runs the two groups as parallel jobs; the release job runs everything.
set -euo pipefail

case "${1:-all}" in
  tests) ./gradlew :protocol:test :core:test :app:testDebugUnitTest ;;
  static) ./gradlew :app:lintDebug :app:lintRelease ktlintCheck detektMain detektTest ;;
  all) ./gradlew :protocol:test :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease ktlintCheck detektMain detektTest ;;
  *) echo "usage: verify.sh [tests|static|all]" >&2; exit 2 ;;
esac
