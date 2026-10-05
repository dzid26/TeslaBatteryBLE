# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims for
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once stable releases begin.

## [Unreleased]

### Added

- About screen (Settings → About): version, links, support, licenses, and the
  Tesla, Inc. disclaimer, as tappable rows; version, licenses, and their links
  are flat and always visible
- Permission wizard: full-screen Material pages with a greeting, then one page
  per missing permission explaining what it is for and what the user has to do,
  with an app-settings fallback when Android stops asking; the cars list keeps a
  compact fallback card and lands there instead of the restored car view when
  permissions are missing

### Changed

- Car view: the paired app key status sits in the hero above the connection
  state, the key card only appears while pairing, and the VIN is hidden behind
  a long-press "Edit VIN" menu on the car card
- Wording: the app's key is called "App key" everywhere; "Phone Key" appears
  only in the rename hint that mirrors the Tesla screen
- Battery history is excluded from Android backup, so a restored install never
  shows a stale "last known" percentage; cached key slots are dropped when the
  key is missing after a restore
- Vehicle keys are per car now, and pairing always enrolls a freshly generated
  key; a key the car no longer has is never re-enrolled, so a restored key from
  a stolen phone cannot be re-armed
- Settings explains when Android runs backups and how to check or trigger one;
  enabling key backup nudges the system so the key is stored sooner
- Pairing starts with a whitelist check: a key the car already has is marked
  paired instantly, with no card tap and no failure after clearing the cache
- Car view shows the last stored SOC ("Last known · …") when there is no live
  reading, instead of only a connection state
- Unpaired car view explains the NFC-card walkthrough before pairing
- RSSI is shown only while connected
- Cars list and car view refresh by pulling down, with a labeled Material pill
  indicator (Scan for cars / Wake car / Read battery) that swaps its icon for a
  spinner while running, replacing the Scan, Wake and Read buttons; a Stop
  action appears while scanning
- Battery history chart: missing stretches (car asleep or out of range) draw
  as a thin line instead of looking continuous, and while charging a dashed
  line projects when the charge limit will be reached at the current rate
- Battery readings keep the car's precise values: the car view shows range to a
  tenth of a mile, history stats use the usable SOC instead of the rounded whole
  percent, and history rows written before this change are dropped on load
- Wake and command send failures are logged instead of failing silently
- Release screenshots are captured from the simulated car automatically in CI
  and attached to each release as one `screenshot-sheet.png`; tag releases are
  always created fresh (the rolling preview is no longer converted in place)

### Fixed

- Crash on Android 12+ when opening a car while Bluetooth permissions were
  missing; the connect and scan paths now stop with a readable log instead

## [0.2.0-beta.2] - 2026-10-05

### Added

- Fresh screenshots from the simulated car (overview, scanning, car, history,
  settings, plus dark-mode variants) captured on API 34 / Pixel 6
- Social preview image and 512 px store icon

### Changed

- Website simplified to installation instructions and updated with the new
  screenshots
- Release notes embed screenshots from the deployed site (preview) or pinned to
  the release commit (tags); tag APKs are named `TeslaBatteryBLE-<tag>.apk`
- A tag cut at the rolling preview's commit converts that release instead of
  deleting it
- Preview job no longer runs an emulator

### Fixed

- Release screenshot order (light shots first, dark variants last)

## [0.2.0-beta.1] - 2026-10-05

### Added

- Settings screen (cog in the app bar) with privacy-first vehicle-key storage:
  device-only by default, opt-in encrypted Android backup behind a warning dialog
  (ADR-0005)
- Battery-health estimators in `core`: rated-range and energy-delta SoH with
  fusion and mismatch detection
- Landing website on GitHub Pages, F-Droid metadata, FAQ, and compatibility matrix
- 512 px store icon, adaptive + monochrome launcher icon, and social preview
- Protocol documentation under `docs/protocol/`, ADRs, README, contribution guide,
  security/privacy policies, code of conduct, issue forms, and SPDX headers
- DCO sign-off check, Dependabot, pinned GitHub Action SHAs, and funding config

### Changed

- Toolchain: Gradle 9.8, AGP 9.4.1, Kotlin 2.4.20, compileSdk/targetSdk 37,
  androidx.core 1.19.1, Compose BOM 2026.09.00
- CI: app unit tests, Android lint, and ktlint + detekt static analysis gates
- Multiple vehicles: one independent BLE link per known car; reworked car view
- Vehicle keys: default device-only; legacy Keystore-encrypted keys migrate
  automatically
- Preview release notes embed the deployed website screenshots instead of assets

### Fixed

- `isLocationEnabled` on Android 8/9 (API 26-27)
- DCO check no longer inspects the synthetic merge commit

## [0.1.0-beta.1] - 2026-10-04

First public preview.

### Added

- BLE scanning for Teslas by advertised name, with VIN hinting
- Key pairing via the unsigned `addKey` flow (NFC card tap + vehicle confirmation)
- Authenticated, encrypted session (P-256 ECDH + AES-GCM) with session persistence
- VCSEC status reads that work while the car is asleep
- On-demand wake; battery SOC and charge-state reads while awake
- Foreground tracking service (connection + battery monitoring, notification)
- Battery history storage and SOC graph (6 h / 24 h / 7 d / all)
- Simulated car in debug builds; emulator screenshots in the rolling preview release
- CI: core tests, debug APK, Go-fixture diff against `teslamotors/vehicle-command`

[Unreleased]: https://github.com/dzid26/TeslaBatteryBLE/compare/v0.2.0-beta.2...HEAD
[0.2.0-beta.2]: https://github.com/dzid26/TeslaBatteryBLE/releases/tag/v0.2.0-beta.2
[0.2.0-beta.1]: https://github.com/dzid26/TeslaBatteryBLE/releases/tag/v0.2.0-beta.1
[0.1.0-beta.1]: https://github.com/dzid26/TeslaBatteryBLE/releases/tag/v0.1.0-beta.1
