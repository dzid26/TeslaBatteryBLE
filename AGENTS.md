# Agent guide

TeslaBatteryBLE: Android app + pure-JVM Tesla BLE protocol core.
License: AGPL-3.0-only (`LICENSE`, `THIRD_PARTY_NOTICES.md`).
Master plan: `docs/master-plan.md` — read it first and update its checkboxes when work lands.

## Layout

- `app/` — Android UI (Compose), BLE integration, foreground service. minSdk 26.
- `core/` — protocol core (Kotlin/JVM, Wire protos). **No Android dependencies allowed.**
- `tools/go-fixtures/` — fixture generator run against `teslamotors/vehicle-command`; `expected.txt` is diffed in CI.
- `docs/` — master plan, ADRs, protocol notes.

## Commands

- `.\gradlew :core:test` — protocol tests. Must pass before any commit touching `core`.
- `.\gradlew :app:assembleDebug` — debug APK.
- `.\gradlew :app:lintDebug` — Android lint (once wired into CI).
- CI (`.github/workflows/android.yml`): core tests, debug build, Go fixture diff, rolling preview release.

## Conventions

- Kotlin official style; match the surrounding code. Imperative commit subjects ("Add ...", "Fix ...").
- Protocol changes require test vectors derived from the Go implementation. Never hand-edit `expected.txt`.
- Never log VINs, private keys, session keys, or decrypted payloads.
- `core` stays multiplatform-ready: no Android, no JVM-only crypto that would block KMP.
- New source files get `SPDX-License-Identifier: AGPL-3.0-only`.
- User-visible changes update README/CHANGELOG; architecture decisions get an ADR in `docs/adr/`.
- Pre-1.0: no back-compat or migration code for states that only exist on old betas or dev machines; change formats freely and document the reset (re-pair / reinstall).
- Merge PRs with a merge commit after rebasing the branch on `main`: `gh pr merge <n> --merge`. Never rebase-merge — history keeps PR provenance and reverts stay one command.
- Specs, plans, and product docs never name competitor products; competitive research lives in `docs/research/` only.

## GitHub comments

- Post agent comments as `github-actions[bot]` through the `Agent comment` workflow instead of the maintainer account:
  `body_b64="$(printf '%s' "comment" | base64 -w0)"; gh workflow run agent-comment.yml -f pr=<n> -f body_b64="$body_b64"`
- Pushes, merges, and commits stay on the maintainer account.

## Do not

- Commit secrets, keystores, or `local.properties`.
- Create accounts, spend money, or submit to stores without the owner's explicit go-ahead.
- Mix unrelated refactors into a feature/fix change.
- Touch uncommitted working-tree changes that are not yours.
