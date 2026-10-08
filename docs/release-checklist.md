# Release checklist

## Versioning policy

- Semantic versioning (`MAJOR.MINOR.PATCH`).
- `versionName` and `versionCode` are derived from the git tag by `app/build.gradle.kts` (`git describe`): tag `v0.3.0-beta.3` builds as `versionName 0.3.0-beta.3`, and `versionCode` is computed from the semver (major*1,000,000 + minor*10,000 + patch*100 + pre-release number, 99 for a stable release). Nothing to bump by hand.
- Git tags are `vX.Y.Z` or `vX.Y.Z-<pre>.N` (for example `v0.3.0-beta.3`); use one pre-release label per patch version so `versionCode` keeps rising.

## Pre-release

- [ ] Run `./gradlew :core:test` — must pass.
- [ ] Run `./gradlew :app:testDebugUnitTest` — must pass.
- [ ] Run `./gradlew :app:lintDebug` — must be clean.
- [ ] Release notes are generated from the commits since the previous tag at tag time; there is no hand-edited changelog.
- [ ] Confirm the relevant `docs/master-plan.md` checkboxes are updated.
- [ ] Real-car validation (see `docs/master-plan.md` section 4): pair key, wake car, SOC read, background tracking survives screen off, notification updates, key survives app restart.

## Release

- [ ] Create and push tag `vX.Y.Z` (e.g. `git tag v0.2.0 && git push origin v0.2.0`).
- [ ] CI builds the APK (replacing the rolling preview's APK when the tag promotes it, and removing that preview), attaches it as `TeslaBatteryBLE-<tag>.apk`, captures screenshots from the simulated car on the emulator, and embeds them (light/dark rows) as user attachments.
- [ ] The capture is required: a failed capture or a missing screenshot fails the run, and the release is not published without its screenshots.
- [ ] CI sets the release title, marks tags containing `-` as prereleases, and writes notes from the commits since the previous tag plus the screenshots.
- [ ] Verify the release page: correct tag/version, notes, screenshots, and installable APK artifact.
- [ ] The rolling preview is removed automatically when the tagged commit matches it; otherwise it remains until the next un-released push.

## Current status

- Stable signing: one owner-held keystore signs local builds, preview builds and tagged releases. Gradle reads the gitignored `keystore.properties`; CI writes it from the `SIGNING_*` secrets in the `release` environment (Settings → Environments; limited to `main` and `v*` tags, no required reviewer), and only the tag-release and rolling-preview jobs read them. Pull request builds, like the `build` job on every run, get no secrets and use the default debug key. Keep the keystore and its password backed up - losing them means no further updates to installed apps.
- The preview channel ships signed debug APKs on the rolling `preview` tag, rebuilt from `main` on every push.
- Switching signing keys (or installing a build signed elsewhere, e.g. F-Droid) requires uninstall + reinstall, which also deletes the car pairing key (re-pair with the NFC card).
- Screenshots live in `website/images/` — one set shared by the README and the landing page; release notes embed the same CI capture as individual shots.

## Store distribution (when signed)

- [ ] F-Droid: metadata lives in `fastlane/` (this repo). Follow the F-Droid submission process for a new app pointing at this metadata, then keep changelogs (`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`) current per release.
- [ ] IzzyOnDroid: submit once a signed stable release exists.
- [ ] Obtainium: already documented in `README.md` (tracks GitHub releases, include prereleases for the preview channel).
- [ ] Signature-change caveat: switching signing keys (e.g. debug/preview to stable, or GitHub to F-Droid) requires users to uninstall and reinstall — Android treats different signatures as different apps, and data does not migrate.

## Post-release

- [ ] Review the generated release notes and correct any commit subjects that read poorly.
- [ ] Verify the rolling preview still rebuilds green.
- [ ] Close the release milestone.
- [ ] Announce (release notes link; channels per master plan).

## Rollback

- [ ] Delete the release or mark it as pre-release/draft on GitHub if it is broken.
- [ ] If the `preview` tag was moved, re-point it at the last good commit and re-run CI.
- [ ] File a follow-up issue for anything that changed during the release.
