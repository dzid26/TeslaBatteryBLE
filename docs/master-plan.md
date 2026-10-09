# TeslaBatteryBLE — Master plan

Status: living document. The checkboxes below are the source of truth for scope.
Created: 2026-10-04 · Maintained alongside every change (see `AGENTS.md`).
Research: `docs/research/feature-map.md` (battery-health feature synthesis) · `docs/reference/fleet-telemetry-vs-ble.md` (live cloud-vs-BLE signal matrix).

## 1. What this is

Android app (Kotlin + Compose) that talks to a Tesla over BLE, starting with
battery SOC and parked drain tracking. No cloud, no account, nothing leaves the
phone. The protocol core (`core/`) is a pure-JVM Kotlin implementation ported
from the Apache-2.0 Go `vehicle-command` and MIT Swift ports; after v1 it
becomes a reusable Tesla BLE library.

Positioning: *"Your Tesla's battery, tracked locally over BLE. No cloud, no
account, nothing leaves the phone."*

## 2. Locked decisions (2026-10-04)

| Decision | Choice | Notes |
| --- | --- | --- |
| License | **AGPL-3.0-only** | Whole repo. Switching to `-or-later` is a one-line change until external contributions land; afterwards it is effectively frozen. |
| Distribution | GitHub Releases + Obtainium now; F-Droid + IzzyOnDroid next; Play later | Signed releases are a prerequisite for F-Droid/IzzyOnDroid. |
| Core library | Internal until v1, then publish (Maven Central vs JitPack TBD) | Library-grade docs and API hygiene from now on. |
| Platforms | Android-first; KMP (jvm + apple) at library release | Keep JVM-only dependencies out of `core`. |
| Interop | Decisions weigh TeslaMate export/sync and the KMP path (2026-10-08) | Today's limits (20k-record cap, no INTERNET permission) are current choices, not commitments. The local log stays raw and complete; any filtering happens at export. |
| UX | Phased product pass: P0 polish → P1 product features → P2 extras | |
| Website | GitHub Pages landing for the app; library docs TBD at library release | |
| Money | Donations only (GitHub Sponsors / Ko-fi) | One free FOSS build everywhere; no ads, no telemetry. |

## 3. Definition of done (every change)

- [ ] Builds: `:core:test` + `:app:assembleDebug` (lint when wired).
- [ ] Tests cover new behavior; protocol changes always ship vectors.
- [ ] No secrets, VINs, or keys in logs.
- [ ] User-visible changes are documented (README / docs).
- [ ] No new back-compat or migration code for pre-release states; change formats freely and document any reset (re-pair / reinstall).
- [ ] Checkboxes here updated; ADR added when an architecture decision was made.

## 4. Validation gates

| Layer | Who / when |
| --- | --- |
| JVM unit tests + static analysis | CI, fully autonomous |
| Emulator UI/screenshots | CI (`android-emulator-runner`) |
| Real car smoke test | Owner, batched ~10 min per milestone |

Real-car checklist (keep short): pair key → wake car → SOC read → background
tracking survives screen off → notification updates → key survives app restart.

## 5. Phase 0 — Foundation (target: week of Oct 5)

A trustworthy shell around the existing protocol work. No user-facing features.

- [x] Land the in-flight BLE refactor (`BlePermissions`, `KnownCarStore`, controller, strings) after build + test verification.
- [x] `LICENSE` (AGPL-3.0-only) + `THIRD_PARTY_NOTICES.md` + `SPDX-License-Identifier` headers in sources.
- [x] In-app license/credits screen data (with the About screen, Phase 1).
- [x] README: pitch, badges, screenshots, features, install (GitHub / Obtainium), build, architecture diagram, license, "not affiliated with Tesla, Inc.".
- [x] App icon: adaptive + monochrome (512 px store export still pending).
- [x] `SECURITY.md`, `PRIVACY.md` ("nothing leaves the device"), `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`.
- [x] Issue forms (bug: car model/year, vehicle software, Android version, logs; feature request), PR template, `.editorconfig`.
- [x] CI gates: Android lint, app unit tests, concurrency cancel, least-privilege permissions, Dependabot.
- [x] CI hardening: pinned action SHAs (#16); ktlint + detekt gates (#19).
- [ ] Required checks on `main` (branch protection deliberately deferred while agents push directly).
- [x] Release engineering: owner keystore + `SIGNING_*` CI secrets; tag releases ship `TeslaBatteryBLE-<tag>.apk`; every release captures screenshots from the simulated car in CI and attaches them as light and dark rows; release checklist documented; a draft "Next release" tracks un-released changes and is published by the tag release.
- [x] Docs skeleton: `docs/adr/` (ADR-0001 moved), `docs/protocol/` (transport, session/pairing, domains).
- [x] `AGENTS.md` for future sessions.

## 6. Phase 1 — v0.2 "Trust & polish" (target: end of October)

- [x] UX P0: permission rationale + pairing walkthrough (incl. NFC card tap), connection state clarity, last-known SOC, readable errors (2026-10-05).
- [x] Key backup & restore UX: Settings-only wording shipped and restore verified on a real fresh install (2026-10-05); spec records what is intentionally out of scope: `docs/requirements/key-backup-ux.md`.
- [x] Theft recovery: per-car keys, pairing always enrolls a freshly generated key (2026-10-05).
- [x] About screen: version, licenses, privacy statement, donation links (2026-10-05).
- [ ] Dark theme + strings extracted to resources (translation-ready).
- [x] Fake BLE transport + simulated car (`FakeCarProtocol`, round-trip tests, CI demo run).
- [ ] Controller state-machine tests.
- [ ] Store tests.
- [ ] Compose UI smoke tests + screenshot tests in CI.
- [ ] Protocol vectors: nonce/metadata/counter/clock-skew edge cases + negative tests.
- [x] Static analysis zero baseline: Android lint + ktlint + detekt in CI.
- [ ] R8 + resource shrinking; Baseline Profile; LeakCanary (debug only).
- [x] Screenshots in `website/images/` (captured in CI, shared by the README and the landing page).
- [x] Social preview image rendered (`website/images/social-preview.png`); upload in GitHub repo settings pending (owner action).
- [x] Website v1 on GitHub Pages (plain, install-focused landing: https://dzid26.github.io/TeslaBatteryBLE/).
- [x] Obtainium instructions (README); F-Droid metadata (`fastlane/`).
- [ ] F-Droid submission; IzzyOnDroid submission; document GitHub→F-Droid signature migration (blocked on signed releases).
- [x] `FUNDING.yml` + README donation section.
- [x] About-screen donation links (with the About screen).
- [ ] v0.2.0 tagged, signed, with curated changelog.

## 7. Phase 2 — v0.3–0.5 "Product" (target: Nov–Dec)

- [ ] **Pre-1.0 hygiene sweep (owner decision 2026-10-05: remove all)**: delete legacy/migration paths — per-car legacy-key adoption, Keystore-material conversion, format migrations, dead branches. Very old installs re-pair (the key-storage session can restore a device key over adb); coordinate key-file changes with it.
- [ ] Navigation: cars list → car view landed 2026-10-05 (no tabs; last opened car restored). Settings/Logs + ViewModels later.
- [x] Tracking restarts after a reboot or an app update (2026-10-08): `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` receiver starts the service when tracking is on, a car is paired and the permissions are granted; nothing restarts after a force stop until the app is opened.
- [x] SOC history graph + charge sessions (first cut 2026-10-04: CSV store, 6h/24h/7d/All graph, since-last-charge stats; session list + export later).
- [x] Discharge projection on the history chart (2026-10-05, revised 2026-10-09): parked drain rate over the selected range, projected half the range ahead onto an x-axis expanded by that horizon; only parked pairs of samples count (not charging, odometer unchanged per the nearest drive sample), so an overnight gap up to the wake-up read counts and an unseen drive does not; dashed line and caption, demo drain; charge projection takes precedence while charging.
- [x] History graph line styles: constant narrow line with a dot at every measurement (dense samples form a thick band) and a dashed charge-completion projection (2026-10-05).
- [ ] **Battery health v1 (loose, BLE-only)**: rated-range + energy-delta capacity estimates fused with confidence + data-quality flag; session-count "Learning" gate; Service-Mode health-test result logging; habit cards (charge-limit share, AC/DC mix, deep discharges); charge taper / balancing-sawtooth detection; static reference bands from published studies. Never claim cell imbalance, pack temperatures, or month-quantified lifespan without pack-level data.
- [x] **Float-precision readings** (2026-10-05): parse the float range fields (`battery_range` / `est_battery_range` / `ideal_battery_range`) and both SOC levels into the charge model and history samples; the car hero and history show the displayed SOC and rated range, and the raw fields are logged for the health estimators. Old CSV rows are dropped, not migrated (pre-1.0 hygiene).
- [ ] Units choice in Settings: System default / Metric / Imperial (one global setting), defaulting to the device's measurement system (`android.icu.util.LocaleData.getMeasurementSystem`: US → imperial, SI → metric, UK → miles + °C); display-only conversion from canonical miles, with core conversion helpers + tests.
- [ ] **Range backbone** (owner direction 2026-10-05): canonical unit is rated miles; parse `charge_rate_mph` / `charge_rate_mph_float` as the charging slope; stats, charge projection, and drain run in miles; SOC is derived from the learned full-range scale for display only, falling back to the raw int until the scale is pinned. Respects the units setting (display-only conversion from canonical miles). Core landed 2026-10-05: rate parsing, miles-first charge projection, learned full-range scale; stats/drain and display wiring next.
- [ ] Dual range/SOC display: switchable axes and two value hints (miles + SOC) on the car view and history chart.
- [x] **History storage decision** (2026-10-06): append-only logs of the car's raw BLE replies (ADR-0006) behind `HistoryStore`, one file per vehicle per kind; additive by construction so new fields never drop old rows; charge-rate plus charger power/voltage/amps fields are logged. BLE log envelope (2026-10-08, ADR-0008): every logged reply is wrapped in a `BleRecord` with the phone's `device_timestamp` and the car's timestamps untouched; charge records go to `<vehicleId>.charge.pblog` on the car's own timestamp, VCSEC status readings to `<vehicleId>.vcsec.pblog` timed by `device_timestamp`, logged on change, on (re)connect and every 15 min. The old raw `<vehicleId>.pblog` files are no longer read (reset; not deleted).
- [x] **DriveState log** (owner direction 2026-10-08, ADR-0008): the car's raw `DriveState` is logged in full (shift state, speed, power, odometer, navigation destination and route), car-timestamped, in `<vehicleId>.drive.pblog`; one `GetDriveState` follows each charge reply while the car is awake. Real-car check pending: the Charging Manager key gets an answer and the file grows.
- [x] **Connection events and signal strength** (owner direction 2026-10-08, ADR-0008): every `BleRecord` carries the phone's latest RSSI for the car (`rssi`), and the controller logs when a connection becomes ready (`CONNECTED`) and when an established one ends (`DISCONNECTED`) in `<vehicleId>.connection.pblog`; failed connection attempts are not logged. Log only, with no read model or UI yet. DISCONNECTED replaces the held-back status flush (`StatusLogGate`): it marks where watching ended, and the last logged status holds until then, so a TeslaMate export can end an asleep/online stretch there. Real-car check pending: the connection file gains a record on each connect and disconnect.
- [x] **Infotainment poll back-off** (owner direction 2026-10-09, ADR-0009, #112): reads every 10 s while charging or driving, and for a hold after each status change (asleep to awake, any lock change, any closure change, fresh start, user request): 1 min, or 10 min while the car reports presence; none while asleep, so the car can sleep; an awake idle car gets one safety read per 20 min (longer than the car's own sleep countdown, tuned from the real-car check). Real-car check pending: the car shows asleep roughly 11-12 min after the last change with nobody present (20-25 min while a phone key counts as present); charging and driving still log every 10 s.
- [x] **Sentry and climate count as active** for the poll back-off (owner direction 2026-10-09, ADR-0008/0009): each read is now charge, drive, closures and climate (Sentry mode lives in `ClosuresState`; `VehicleState` holds only the guest mode), both logged raw in `<vehicleId>.closures.pblog` and `.climate.pblog`, and Sentry or climate on keeps reads at 10 s. Real-car check pending: in Sentry or with the climate on, readings refresh every 10 s, and the car sleeps once both are off.
- [ ] Evaluate Android activity recognition to detect driving, and an opt-in "stay awake while the phone is near and moving" feature (deferred in ADR-0009).
- [ ] **Drive detection** from the DriveState log (odometer deltas across gaps), for the parked-drain projection and TeslaMate drives. The projection part landed 2026-10-09; TeslaMate drives remain.
- [ ] Notifications: charge complete, SOC thresholds, **vampire-drain alert**.
- [ ] Widget (Glance), automation intents (Tasker), CSV/JSON export.
- [ ] **TeslaMate export/sync** (owner direction 2026-10-08): get the phone's BLE history into the owner's self-hosted TeslaMate to fill gaps in its cloud data. Export maps the raw records (ChargeState, DriveState, VCSEC status) onto TeslaMate's vehicle-data shape with the car's timestamps; status readings become asleep/online states. Upstream has no way in for this data yet (its only importer takes data older than the first TeslaMate record); discussion in teslamate-org/teslamate#5496. File export first (share sheet, no new permission); direct sync to the owner's server is opt-in and needs `INTERNET` plus, at targetSdk 37, the runtime `ACCESS_LOCAL_NETWORK` for LAN addresses, with PRIVACY.md and the positioning line updated when it ships. Retention keeps records until they are synced (20k cap, #123).
- [ ] Multiple cars: storage, per-vehicle links, VIN, per-car notifications and the cars/car UI landed (ADR-0004); share redacted diagnostics open. Requirements: `docs/requirements/multi-vehicle.md`.
- [ ] Share redacted diagnostics.
- [ ] Compatibility matrix (car models, vehicle software, Android versions).
- [ ] FAQ / troubleshooting.
- [ ] Coverage thresholds; property/fuzz tests; `tesla-control` interop oracle.
- [ ] Play prep: privacy policy URL, location declaration, closed testing track → go/no-go.

## 8. Phase 3 — v1.0 "Library & launch" (target: Q1 2027)

- [ ] `core` → KMP (jvm + apple), expect/actual crypto, Dokka.
- [ ] Publish the library (Maven Central vs JitPack — decide then); semver + binary compatibility validator; sample CLI; library documentation.
- [ ] **Battery health v2 (tight, optional OBD)**: BLE OBD dongle support (ELM327/STN), model harness guidance, read-only pack PIDs (nominal/full pack capacity, brick voltage min/max + CAC, pack temperatures), real imbalance card. Purely optional and fully on-device.
- [ ] Decide monorepo vs separate library repo (default: split at publish).
- [ ] Governance lite: MAINTAINERS, public roadmap, Discussions, good-first-issues.
- [ ] Launch: F-Droid stable, announcements (r/TeslaLounge, TMC forums, HN), funding live.
- [ ] v1.0 signed release; reproducible-build notes; OpenSSF Scorecard; SBOM.

## 9. Continuous

- [ ] Dependency updates + security fixes.
- [x] Draft release update on every `main` push stays green.
- [ ] Docs kept current with every user-visible change.
- [ ] Signal-matrix drift PRs reviewed (`.github/workflows/signal-matrix.yml`, weekly).
- [ ] Real-car validation per milestone.
- [ ] Community triage once issues arrive.

## 10. Open decisions (defaults so nothing blocks)

- Display name before store submission (default: keep `TeslaBatteryBLE`); `applicationId com.dzid26.teslable` is frozen.
- Library name + publication target (Phase 3).
- Play go/no-go (Phase 2).
- Library docs site repo/hosting (Phase 3).
- Multi-phone clones (2-3 phones collecting and merging history, optional key cloning): decide later; captured in `docs/requirements/multi-phone.md`.

## 11. Risks

- **Crypto edge cases** → vectors + `tesla-control` oracle; fuzzing later.
- **OEM battery management** kills background BLE → foreground service, documented expectations, FAQ.
- **Store policy** (location/BLE, Tesla trademark) → F-Droid-first, original icon, explicit disclaimer.
- **AGPL deters some library consumers** → accepted trade-off (community norm in this protocol space); revisit only if library traction stalls.
- **Bus factor** → docs-first, `AGENTS.md`, no CLA.
