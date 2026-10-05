# Release checklist

## Versioning policy

- Semantic versioning (`MAJOR.MINOR.PATCH`).
- `versionCode` (integer, always increments) and `versionName` (semver string) live in `app/build.gradle.kts` (`defaultConfig`).
- Git tags are `vX.Y.Z` and must match `versionName` (e.g. tag `v0.2.0` = `versionName "0.2.0"` with a bumped `versionCode`).

## Pre-release

- [ ] Bump `versionCode` (+1) and set `versionName` in `app/build.gradle.kts`.
- [ ] Run `./gradlew :core:test` — must pass.
- [ ] Run `./gradlew :app:testDebugUnitTest` — must pass.
- [ ] Run `./gradlew :app:lintDebug` — must be clean.
- [ ] Update `CHANGELOG.md` with the curated changelog for this version.
- [ ] Confirm the relevant `docs/master-plan.md` checkboxes are updated.
- [ ] Real-car validation (see `docs/master-plan.md` section 4): pair key, wake car, SOC read, background tracking survives screen off, notification updates, key survives app restart.

## Release

- [ ] Create and push tag `vX.Y.Z` (e.g. `git tag v0.2.0 && git push origin v0.2.0`).
- [ ] CI builds the APK, attaches it as `TeslaBatteryBLE-<tag>.apk`, captures screenshots from the simulated car on the emulator, composes one `screenshot-sheet.png`, and attaches it.
- [ ] The capture is required: a failed capture or a missing sheet fails the run, and the release is not published without its screenshots.
- [ ] CI sets the release title, marks tags containing `-` as prereleases, and writes notes from the matching `CHANGELOG.md` section plus the sheet.
- [ ] Verify the release page: correct tag/version, notes, screenshots, and installable APK artifact.
- [ ] The rolling preview is removed automatically when the tagged commit matches it; otherwise it remains until the next un-released push.

## Current status

- Stable signing: one owner-held keystore signs local builds, preview builds and tagged releases (Gradle reads the gitignored `keystore.properties`; CI reads the `SIGNING_*` repository secrets). Keep the keystore and its password backed up - losing them means no further updates to installed apps.
- The preview channel ships signed debug APKs on the rolling `preview` tag, rebuilt from `main` on every push.
- Switching signing keys (or installing a build signed elsewhere, e.g. F-Droid) requires uninstall + reinstall, which also deletes the car pairing key (re-pair with the NFC card).
- Screenshots live in `website/images/` — one set shared by the README and the landing page; release sheets are captured separately in CI.

## Store distribution (when signed)

- [ ] F-Droid: metadata lives in `fastlane/` (this repo). Follow the F-Droid submission process for a new app pointing at this metadata, then keep changelogs (`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`) current per release.
- [ ] IzzyOnDroid: submit once a signed stable release exists.
- [ ] Obtainium: already documented in `README.md` (tracks GitHub releases, include prereleases for the preview channel).
- [ ] Signature-change caveat: switching signing keys (e.g. debug/preview to stable, or GitHub to F-Droid) requires users to uninstall and reinstall — Android treats different signatures as different apps, and data does not migrate.

## Post-release

- [ ] Update `CHANGELOG.md` if anything changed during release.
- [ ] Verify the rolling preview still rebuilds green.
- [ ] Close the release milestone.
- [ ] Announce (release notes link; channels per master plan).

## Rollback

- [ ] Delete the release or mark it as pre-release/draft on GitHub if it is broken.
- [ ] If the `preview` tag was moved, re-point it at the last good commit and re-run CI.
- [ ] File a follow-up issue and note it in `CHANGELOG.md`.
