# TeslaBatteryBLE

**Your Tesla's battery, tracked locally over Bluetooth LE. No cloud, no account, nothing leaves the phone.**

[![Android CI](https://github.com/dzid26/TeslaBatteryBLE/actions/workflows/android.yml/badge.svg)](https://github.com/dzid26/TeslaBatteryBLE/actions/workflows/android.yml)
[![Website](https://img.shields.io/badge/website-live-brightgreen.svg)](https://dzid26.github.io/TeslaBatteryBLE/)
[![License: AGPL-3.0-only](https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg)](LICENSE)
![Platform: Android 8.1+](https://img.shields.io/badge/platform-Android%208.1%2B%20(API%2027)-green.svg)
[![Status: beta](https://img.shields.io/badge/status-beta-orange.svg)](docs/master-plan.md)

TeslaBatteryBLE talks to a Tesla over Bluetooth LE using an enrolled vehicle key —
the same local protocol Tesla's [`vehicle-command`](https://github.com/teslamotors/vehicle-command)
uses. No Tesla account, no virtual-key cloud registration, no telemetry. Install the
app, pair a key with an NFC card tap on the console, and read the car directly.

## Screenshots

Light and dark themes, side by side.

**Light**

<p><img src="website/images/02-scanning.png" alt="Scanning for nearby cars (light)" width="32%">;<img src="website/images/03-car.png" alt="Car detail with the battery reading (light)" width="32%">;<img src="website/images/05-settings.png" alt="Settings (light)" width="32%"></p>

**Dark**

<p><img src="website/images/02-scanning-dark.png" alt="Scanning for nearby cars (dark)" width="32%">;<img src="website/images/03-car-dark.png" alt="Car detail with the battery reading (dark)" width="32%">;<img src="website/images/05-settings-dark.png" alt="Settings (dark)" width="32%"></p>

## Features

- **Scan & connect** to nearby Teslas by advertised BLE name, with VIN-based hinting
- **Pair a key** (CHARGING_MANAGER role by default) via Tesla's unsigned `addKey` flow: NFC card tap on the console + vehicle confirmation
- **Read battery SOC** and charge state over an authenticated, encrypted session; a
  reading is blue only while it is still being read (under about 25 s old); after that it
  turns gray and the car view, the list and the notification say how long ago it was
  read ("12m ago")
- **On-demand wake** — the car is only woken when you ask, never in the background
- **Lets the car sleep** — while it is charging, driving, in Sentry mode or running the climate (the car stays
  awake and draws power anyway), readings refresh every 10 s. Otherwise the app keeps
  reading every 10 s for a short while after something happens (the car wakes, locks or unlocks, a door or the
  charge port opens or closes, you tap refresh): one minute, or ten while someone is in or near the car. Then it
  stops asking, so the car can fall asleep and the reading simply gets older. A car that stays awake for a reason
  the app cannot see is read once every twenty minutes, which is longer than the car's own sleep timer, so
  the app never keeps it awake.
- **Background tracking** via a foreground service: SOC timeline and connection state while parked or charging.
  Tracking resumes by itself after a reboot or an app update (once the phone is unlocked), as long as tracking
  is on and a car is paired. Some phones (Xiaomi, Huawei, Oppo/Vivo, Samsung "sleeping apps") block this unless
  you allow autostart for the app or set its battery usage to unrestricted. After a force stop, Android keeps
  the app stopped until you open it again.
- **History graph** (6 h / 24 h / 7 d / all) with since-last-charge stats;
  every measurement is a dot (dense samples form a thick band), and dashed
  lines project the charge completion or, while parked, the parked drain half
  the range ahead (stretches where the odometer moved are left out)
- **Local activity log** — the commands the app sends to the car (and why) and whether the screen was on and the
  app in the foreground are kept on the phone only, so a wake or a read in the car's own history can be told
  apart from yours
- **Simulated car** in debug builds for development without a vehicle or hardware (demo/screenshot mode)

Everything is computed on-device. The app has no internet permission.

## Coming next

Local battery-health analytics (capacity estimation from charge sessions and rated
range, charging-efficiency and parked-drain insights, habit cards), then an optional
OBD path for pack-level data. See:

- [`docs/master-plan.md`](docs/master-plan.md) — phases and scope
- [`docs/research/feature-map.md`](docs/research/feature-map.md) — battery-health feature synthesis

## Install

### GitHub Releases

Install the APK from the latest [release](https://github.com/dzid26/TeslaBatteryBLE/releases).
Releases are signed with the owner key and include prereleases such as betas.

### Obtainium (auto-updates from GitHub)

1. Open [Obtainium](https://github.com/ImranR98/Obtainium) → **Add App**
2. URL: `https://github.com/dzid26/TeslaBatteryBLE`
3. Obtainium tracks stable releases by default. To also follow tagged
   pre-releases (for example betas), enable **Include prereleases**.
4. Install and let Obtainium keep it updated

No stable release has shipped yet. Signed stable releases and F-Droid are on the
[roadmap](docs/master-plan.md).

## Build from source

Requirements: JDK 21 (Temurin recommended), Android SDK 37, Git.

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :core:test                # protocol unit tests (no device needed)
./gradlew :app:testDebugUnitTest    # app unit tests (simulated car)
./gradlew :app:lintDebug            # Android lint
```

On Windows, use `.\gradlew` instead of `./gradlew`. The debug APK lands in
`app/build/outputs/apk/debug/`.

CI (`.github/workflows/android.yml`) runs the core tests, app unit tests, lint, a
debug build, a Go-fixture diff against `teslamotors/vehicle-command`, and publishes
a draft of the next release; tag releases add the APK and emulator screenshots.

## Architecture

| Module | Purpose |
| --- | --- |
| `core/` | Pure-JVM Kotlin protocol implementation: BLE framing, session handshake, pairing, VCSEC/Infotainment messages (Tesla protos via Wire). No Android dependencies. |
| `app/` | Android UI (Compose), BLE integration (`BluetoothGatt`), foreground tracking service, history storage. |
| `tools/go-fixtures` | Deterministic fixtures generated against the Go reference implementation and diffed in CI. |

```mermaid
flowchart LR
    UI[Compose UI] --> Ctrl[TeslaBleController]
    Svc[Foreground tracking service] --> Ctrl
    Ctrl --> Core[core: framing, session, protocol]
    Core -- GATT write/notify --> Car[Tesla vehicle]
    Ctrl --> Store[(Local history)]
```

Protocol details: [`docs/protocol/`](docs/protocol/). Architecture decisions:
[`docs/adr/`](docs/adr/). Data availability:
[`docs/reference/fleet-telemetry-vs-ble.md`](docs/reference/fleet-telemetry-vs-ble.md)
— a live cloud-vs-BLE signal matrix of what can be read over BLE.

## Contributing

Contributions are welcome. Start with [`CONTRIBUTING.md`](CONTRIBUTING.md) — dev
setup, module map, and test expectations. Issues and pull requests use templates
in [`.github/`](.github/).

## Security & privacy

- [`SECURITY.md`](SECURITY.md) — how to report vulnerabilities privately
- [`PRIVACY.md`](PRIVACY.md) — what the app does (and deliberately does not do) with data

## Support

TeslaBatteryBLE is free, ad-free, and telemetry-free. If it's useful to you, you
can support development via [GitHub Sponsors](https://github.com/sponsors/dzid26).
Donations help cover development costs (test hardware, tooling) and keep the
project independent.

## License

TeslaBatteryBLE is licensed under the **GNU Affero General Public License v3.0 only**
([AGPL-3.0-only](LICENSE)). Third-party components and ported protocol definitions are
listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

> Not affiliated with, endorsed by, or sponsored by Tesla, Inc. "Tesla" is a
> trademark of Tesla, Inc., used here only to describe interoperability.
