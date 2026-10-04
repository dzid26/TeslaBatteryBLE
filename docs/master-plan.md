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
| License | **AGPL-3.0-only** | Whole repo. Switching to `-or-later` is a one-line change until external contributions land; afterwards it is effectively frozen (DCO, no CLA). |
| Distribution | GitHub Releases + Obtainium now; F-Droid + IzzyOnDroid next; Play later | Signed releases are a prerequisite for F-Droid/IzzyOnDroid. |
| Core library | Internal until v1, then publish (Maven Central vs JitPack TBD) | Library-grade docs and API hygiene from now on. |
| Platforms | Android-first; KMP (jvm + apple) at library release | Keep JVM-only dependencies out of `core`. |
| UX | Phased product pass: P0 polish → P1 product features → P2 extras | |
| Website | GitHub Pages landing for the app; library docs TBD at library release | |
| Money | Donations only (GitHub Sponsors / Ko-fi) | One free FOSS build everywhere; no ads, no telemetry. |

## 3. Definition of done (every change)

- [ ] Builds: `:core:test` + `:app:assembleDebug` (lint when wired).
- [ ] Tests cover new behavior; protocol changes always ship vectors.
- [ ] No secrets, VINs, or keys in logs.
- [ ] User-visible changes are documented (README / docs / CHANGELOG).
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
- [ ] In-app license/credits screen data (with the About screen, Phase 1).
- [x] README: pitch, badges, screenshots, features, install (GitHub / Obtainium), build, architecture diagram, license, "not affiliated with Tesla, Inc.".
- [x] App icon: adaptive + monochrome (512 px store export still pending).
- [x] `SECURITY.md`, `PRIVACY.md` ("nothing leaves the device"), `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md` (DCO, no CLA), `CHANGELOG.md`.
- [x] Issue forms (bug: car model/year, vehicle software, Android version, logs; feature request), PR template, `.editorconfig`.
- [x] CI gates: Android lint, app unit tests, concurrency cancel, least-privilege permissions, Dependabot.
- [ ] CI hardening: ktlint + detekt, pinned action SHAs, required checks on `main` (branch protection deliberately deferred while agents push directly).
- [ ] Release engineering: signed release blocked on owner keystore; release checklist documented.
- [x] Docs skeleton: `docs/adr/` (ADR-0001 moved), `docs/protocol/` (transport, session/pairing, domains).
- [x] `AGENTS.md` for future sessions.

## 6. Phase 1 — v0.2 "Trust & polish" (target: end of October)

- [ ] UX P0: permission rationale + pairing walkthrough (incl. NFC card tap), connection state clarity, last-known SOC, readable errors.
- [ ] About screen: version, licenses, privacy statement, donation links.
- [ ] Dark theme + strings extracted to resources (translation-ready).
- [x] Fake BLE transport + simulated car (`FakeCarProtocol`, round-trip tests, CI demo run).
- [ ] Controller state-machine tests.
- [ ] Store tests.
- [ ] Compose UI smoke tests + screenshot tests in CI.
- [ ] Protocol vectors: nonce/metadata/counter/clock-skew edge cases + negative tests.
- [ ] Static analysis zero baseline; R8 + resource shrinking; Baseline Profile; LeakCanary (debug only).
- [ ] Screenshots → `docs/images/` with captions; social preview image.
- [ ] Website v1 on GitHub Pages (landing + install buttons + privacy story).
- [ ] Obtainium instructions; F-Droid metadata (fastlane) + submission; IzzyOnDroid submission; document GitHub→F-Droid signature migration.
- [ ] `FUNDING.yml` + README/About donation links.
- [ ] v0.2.0 tagged, signed, with curated changelog.

## 7. Phase 2 — v0.3–0.5 "Product" (target: Nov–Dec)

- [ ] Navigation (Home / Car / Settings / Logs) + ViewModels.
- [x] SOC history graph + charge sessions (first cut 2026-10-04: CSV store, 6h/24h/7d/All graph, since-last-charge stats; session list + export later).
- [ ] **Battery health v1 (loose, BLE-only)**: rated-range + energy-delta capacity estimates fused with confidence + data-quality flag; session-count "Learning" gate; Service-Mode health-test result logging; habit cards (charge-limit share, AC/DC mix, deep discharges); charge taper / balancing-sawtooth detection; static reference bands from published studies. Never claim cell imbalance, pack temperatures, or month-quantified lifespan without pack-level data.
- [ ] Notifications: charge complete, SOC thresholds, **vampire-drain alert**.
- [ ] Widget (Glance), automation intents (Tasker), CSV/JSON export.
- [ ] Multiple cars: storage, per-vehicle links and VIN landed (ADR-0004); notifications + UI in progress. Requirements: `docs/requirements/multi-vehicle.md`.
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
- [x] Preview rebuild on every `main` push stays green.
- [ ] Docs kept current with every user-visible change.
- [ ] Signal-matrix drift PRs reviewed (`.github/workflows/signal-matrix.yml`, weekly).
- [ ] Real-car validation per milestone.
- [ ] Community triage once issues arrive.

## 10. Open decisions (defaults so nothing blocks)

- Display name before store submission (default: keep `TeslaBatteryBLE`); `applicationId com.dzid26.teslable` is frozen.
- Library name + publication target (Phase 3).
- Play go/no-go (Phase 2).
- Library docs site repo/hosting (Phase 3).

## 11. Risks

- **Crypto edge cases** → vectors + `tesla-control` oracle; fuzzing later.
- **OEM battery management** kills background BLE → foreground service, documented expectations, FAQ.
- **Store policy** (location/BLE, Tesla trademark) → F-Droid-first, original icon, explicit disclaimer.
- **AGPL deters some library consumers** → accepted trade-off (community norm in this protocol space); revisit only if library traction stalls.
- **Bus factor** → docs-first, `AGENTS.md`, DCO contributions, no CLA.
