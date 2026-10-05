# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims for
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once stable releases begin.

## [Unreleased]

### Added

- README, contribution guide, security/privacy policies, code of conduct
- Issue forms, pull request template, Dependabot, `.editorconfig`
- Protocol documentation under `docs/protocol/`
- App icon (adaptive + monochrome)
- SPDX license headers across the source tree
- GitHub Sponsors funding config and README support section

### Changed

- Vehicle key now stored in app-private storage and included in Android backup so
  pairing survives a device change (ADR-0005); legacy Keystore-encrypted keys migrate
  automatically
- CI: concurrency cancellation, least-privilege permissions, Android lint and app
  unit tests in the pipeline

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

[Unreleased]: https://github.com/dzid26/TeslaBatteryBLE/compare/v0.1.0-beta.1...HEAD
[0.1.0-beta.1]: https://github.com/dzid26/TeslaBatteryBLE/releases/tag/v0.1.0-beta.1
