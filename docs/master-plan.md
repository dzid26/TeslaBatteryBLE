# TeslaBatteryBLE — Master plan

Status: living document. The checkboxes below are the source of truth for scope.
Created: 2026-10-04 · Maintained alongside every change (see `AGENTS.md`).

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

- [ ] Land the in-flight BLE refactor (`BlePermissions`, `KnownCarStore`, controller, strings) after build + test verification.
- [ ] `LICENSE` (AGPL-3.0-only) + `THIRD_PARTY_NOTICES.md` + `SPDX-License-Identifier` headers in sources + first-party license screen data.
- [ ] README: pitch, badges, screenshots, features, install (GitHub / Obtainium), build, architecture diagram, license, "not affiliated with Tesla, Inc.".
- [ ] App icon: adaptive + monochrome + 512 px store asset; keep the existing notification icon.
- [ ] `SECURITY.md`, `PRIVACY.md` ("nothing leaves the device"), `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md` (DCO, no CLA), `CHANGELOG.md`.
- [ ] Issue forms (bug: car model/year, vehicle software, Android version, logs; feature request), PR template, `.editorconfig`.
- [ ] CI gates: ktlint + detekt + Android lint; required checks on `main`; concurrency cancel; least-privilege permissions; pinned action SHAs; Dependabot.
- [ ] Release engineering: keystore + GitHub secrets, `versionCode`/`versionName` policy, tag-driven signed release, release checklist; preview channel stays debug and clearly labeled.
- [ ] Docs skeleton: `docs/adr/` (move ADR-0001), `docs/protocol/` (framing, session, pairing, VCSEC vs Infotainment).
- [ ] `AGENTS.md` for future sessions.
- [ ] Branch protection on `main`: required CI, no force-push.

## 6. Phase 1 — v0.2 "Trust & polish" (target: end of October)

- [ ] UX P0: permission rationale + pairing walkthrough (incl. NFC card tap), connection state clarity, last-known SOC, readable errors.
- [ ] About screen: version, licenses, privacy statement, donation links.
- [ ] Dark theme + strings extracted to resources (translation-ready).
- [ ] Fake BLE transport + simulated car; controller state-machine tests; store tests.
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
- [ ] SOC history graph + charge sessions.
- [ ] Notifications: charge complete, SOC thresholds, **vampire-drain alert**.
- [ ] Widget (Glance), automation intents (Tasker), CSV/JSON export.
- [ ] Multiple cars; share redacted diagnostics.
- [ ] Compatibility matrix (car models, vehicle software, Android versions).
- [ ] FAQ / troubleshooting.
- [ ] Coverage thresholds; property/fuzz tests; `tesla-control` interop oracle.
- [ ] Play prep: privacy policy URL, location declaration, closed testing track → go/no-go.

## 8. Phase 3 — v1.0 "Library & launch" (target: Q1 2027)

- [ ] `core` → KMP (jvm + apple), expect/actual crypto, Dokka.
- [ ] Publish the library (Maven Central vs JitPack — decide then); semver + binary compatibility validator; sample CLI; library documentation.
- [ ] Decide monorepo vs separate library repo (default: split at publish).
- [ ] Governance lite: MAINTAINERS, public roadmap, Discussions, good-first-issues.
- [ ] Launch: F-Droid stable, announcements (r/TeslaLounge, TMC forums, HN), funding live.
- [ ] v1.0 signed release; reproducible-build notes; OpenSSF Scorecard; SBOM.

## 9. Continuous

- [ ] Dependency updates + security fixes.
- [ ] Preview rebuild on every `main` push stays green.
- [ ] Docs kept current with every user-visible change.
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
