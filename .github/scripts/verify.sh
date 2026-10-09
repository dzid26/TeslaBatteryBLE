#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# The checks every build must pass before an APK is produced: protocol and app
# unit tests, Android lint and the static analysis. Shared by the CI build job
# and the release job, so a release is verified exactly like a pull request.
set -euo pipefail

./gradlew :core:test :app:testDebugUnitTest :app:lintDebug ktlintCheck detekt
