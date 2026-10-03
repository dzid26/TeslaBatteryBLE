# ADR-0001: Tesla BLE battery tracker — platform and protocol reuse

Status: Accepted (supersedes the same-day Go/gomobile decision)
Date: 2026-10-03
Deciders: dzidm

## Context

Goal: a phone app that connects directly to a Tesla over BLE (no cloud), tracks
battery state of charge first, and can grow into more vehicle data later. iOS is
a possible future target; Android is the priority.

Prior attempt (`dzid26/Tesla-ble-android`) never implemented the vehicle
protocol — its README still listed "replace placeholder auth payload" as a TODO —
and `notes.md` documents outdated BLE UUIDs and an outdated framing model. It
could not have connected or paired as written.

Constraints:

- Avoid cloud/Fleet API dependencies for the core feature.
- Phone-as-key security: the app holds a private key that can unlock the car, so
  key storage and scope matter.
- No foreign-function interface (FFI) or exotic build toolchains if avoidable.
- Reuse Tesla's protocol definitions and reference implementations rather than
  inventing anything.

## Findings

### Correct protocol facts (as of 2026)

- GATT service: `00000211-b2d1-43f0-9b88-960cebf8b91e`
- To vehicle (write): `00000212-b2d1-43f0-9b88-960cebf8b91e`
- From vehicle (indicate/notify): `00000213-b2d1-43f0-9b88-960cebf8b91e`
- Version/read characteristic: `00000214-b2d1-43f0-9b88-960cebf8b91e`
- `FEE7` / `7430000x` from `notes.md` are not the current command-protocol
  service. `FEE7` was an older/adjacent advertisement and will not reach the
  command endpoint.
- Framing: 2-byte big-endian length prefix, chunked to `MTU - 3` bytes. Tesla
  clients exchange MTU 256 when possible.
- Message model: protobuf `UniversalMessage.RoutableMessage`, routed to one of
  two domains, each with its own session keys:
  - `DOMAIN_VEHICLE_SECURITY` (VCSEC): lock/unlock, wake, closures, keychain.
  - `DOMAIN_INFOTAINMENT`: charge state, climate, media, most data queries.
- Auth: P-256 ECDH key agreement; per-domain session with AES-GCM (4-byte nonce
  variant on BLE) or HMAC-SHA256. Session state is long-lived and must be
  persisted across app restarts or re-handshaked.
- Discovery: the car advertises a VIN-derived local name
  `S<sha1(vin)[0:8] as hex>C`; it stops advertising when already at the maximum
  number of BLE connections.
- Pairing: send an unsigned `addKey` request while the car is in pairing mode
  (user taps an NFC key card on the console, then confirms on the vehicle UI).
  Cannot be automated. Key roles include `OWNER`, `DRIVER`, and
  `CHARGING_MANAGER`; key form factor can be `ANDROID_DEVICE`.
- The `addKey` request is sent unsigned (`SIGNATURE_TYPE_PRESENT_KEY`), so it
  works before any session crypto exists. The vehicle answers
  `OPERATIONSTATUS_WAIT` until the NFC card is presented.

### SOC availability over BLE

- VCSEC status polling works while the car is asleep and does not wake it:
  locked/unlocked, asleep/awake, user present, closures.
- Charge state (`GetState(StateCategoryCharge)`) terminates on Infotainment and
  is only available when the car is awake (driving, charging, recently used).
  Polling it can keep the car awake.
- Therefore a BLE-only app can track SOC continuously while driving/charging and
  can track lock/sleep state while parked, but cannot read parked SOC without
  waking the car. Parked SOC without waking is a cloud/telemetry problem
  (Fleet Telemetry), not a BLE problem.

### Anatomy of the reference implementation

The official Go implementation is small and layered. Paths are within
`teslamotors/vehicle-command`:

| Layer | Location | Size | Role |
| --- | --- | --- | --- |
| Command surface | `pkg/vehicle/*.go` | few lines per command | Build a protobuf action, send, parse. `GetState`, `ChargeStart`, `Unlock`, etc. |
| Dispatcher | `internal/dispatcher/{dispatcher,session,receiver}.go` | ~20 KB Go | RoutableMessage send/receive, counters, response matching, session handshake |
| Protocol | `pkg/protocol/` + `pkg/protocol/protobuf/` | `.proto` files | Message definitions and error classification |
| Spec | `pkg/protocol/protocol.md` | — | Human-readable protocol description |
| BLE transport | `pkg/connector/ble/ble.go` | ~360 lines, mostly adapter glue | GATT + length framing; framing itself is ~30 lines |

Example of a "function": `GetState` is a protobuf wrapper, not protocol logic:

```go
func (v *Vehicle) GetState(ctx context.Context, category StateCategory) (*carserver.VehicleData, error) {
    submessage := category.submessage() // e.g. {GetChargeState: {}}
    action := carserver.Action_VehicleAction{
        VehicleAction: &carserver.VehicleAction{
            VehicleActionMsg: &carserver.VehicleAction_GetVehicleData{GetVehicleData: submessage},
        },
    }
    rsp, err := v.getCarServerResponse(ctx, &action)
    if err != nil { return nil, err }
    return rsp.GetVehicleData(), nil
}
```

The real work is the plumbing underneath: `Send` builds a `RoutableMessage`,
encrypts/authenticates it with the session, waits for a matching response with
retries; `StartSession` performs the ECDH handshake per domain.

### Library landscape

| Project | Language / license | Provides | Fit |
| --- | --- | --- | --- |
| teslamotors/vehicle-command (v0.4.1, Feb 2026, active) | Go, Apache-2.0 | Full protocol, pairing, sessions, commands, `GetState(StateCategoryCharge)` (SOC), `BodyControllerState` (works asleep), session cache | Primary reference for the port; `.proto` files reused directly |
| misakatao/TeslaBLEKeyKit | Swift, MIT | Commands, `getVehicleData()` (charge state), CoreBluetooth, keychain | Secondary reference; modern-language port blueprint |
| shoujiaxin/swift-tesla-ble (v1.0.0, Apr 2026) | Swift, MIT | 75 commands + queries, pairing, keychain | Secondary reference; shows protobuf vendoring + key management |
| pmdroid/tesla-vehicle-command | C++, **AGPL-3.0 or commercial** | Same protocol for Arduino/ESP32; proves it fits on an MCU | Behavioral reference only — do not copy code |
| PedroKTFC/tesla-ble + esphome-tesla-ble | C++, AGPL-3.0 | SOC/charge sensors on ESP32 | Behavioral reference only |
| JuulLabs/kable (0.44.x) | Kotlin MP, Apache-2.0 | BLE scan/GATT/notifications on Android and iOS | Future KMP transport |
| square/wire | Kotlin MP, Apache-2.0 | Protobuf codegen from Tesla `.proto` files | Core dependency |
| whyoleg/cryptography-kotlin | Kotlin MP, Apache-2.0 | ECDH, AES-GCM, HMAC, SHA-1 across platforms | Future KMP crypto (Android v1 can use JCA/BouncyCastle) |

No Kotlin/Java Tesla BLE implementation exists. The only Kotlin Tesla library
(`boltfortesla/tesla-fleet-sdk-kotlin`) is for the cloud Fleet API, not BLE.

### Cross-language reuse options

- Go to Kotlin: `gomobile bind` (AAR/XCFramework) works but is FFI with a
  restricted type subset, bundles the Go runtime, and forces the private key
  out of Android Keystore into Go memory. Evaluated and rejected for v1.
- C++ to Kotlin: NDK/JNI or SWIG; the maintained C++ code is AGPL.
- Python to Kotlin: no production converter; Chaquopy embeds CPython on Android
  only. Rejected.
- Rust to Kotlin: UniFFI is mature, but there is no Tesla BLE Rust library.
- Swift to Kotlin: no interop path; useful only as porting reference.
- Dart/JavaScript: no Tesla BLE package.

## Options considered

| | Approach | Reuse | Cost / risk | Platforms |
| --- | --- | --- | --- | --- |
| A | Native Kotlin protocol core + Android app (chosen) | Tesla `.proto` files; port logic from Go (Apache-2.0) and Swift (MIT) | ~1-1.5k lines of Kotlin; no FFI; JVM-testable; Keystore-friendly | Android now, KMP later |
| B | Go core via gomobile (previously chosen, now rejected) | Tesla-maintained protocol | FFI, Go+NDK toolchain, Go runtime, key exported to Go | Android + iOS via XCFramework |
| C | Native Android now; iOS later with Swift libraries | Go AAR on Android; MIT Swift on iOS | Two codebases later | Android, then iOS |
| D | C++ core via NDK/JNI | pmdroid/tesla-ble | AGPL-3.0 and embedded deps | Android |
| E | Capacitor / Flutter / React Native | None for BLE protocol | Weak background BLE, no protocol library | Both |

## Decision

1. Android first. iOS is deferred until the Android vertical slice works; the
   core is written to be Kotlin Multiplatform-friendly but v1 does not commit to
   KMP.
2. App: Kotlin + Jetpack Compose, native Android BLE (`BluetoothGatt`).
3. Protocol core: a **pure Kotlin port** of the Tesla BLE protocol, scoped to
   what the app needs. No FFI, no JNI, no Go runtime.
4. Reuse Tesla's `.proto` files directly (Apache-2.0), pinned to a commit, with
   Kotlin generated by Wire.
5. Port logic from the Apache-2.0 Go implementation and the MIT Swift
   implementations. The AGPL C++ libraries are behavioral references only; no
   code is copied from them.
6. No FFI anywhere: Kotlin calls Android BLE APIs and implements the protocol
   in-process. The FFI discussion only applied to the rejected Go/gomobile
   option.

## Architecture

```
Compose UI
  |
TeslaSession (Kotlin interface, coroutines)
  |
:core (pure Kotlin/JVM-testable)
  - Framing: 2-byte length, MTU chunking
  - Dispatcher: RoutableMessage send/receive, counters, response matching
  - Session: ECDH handshake, AES-GCM/HMAC, clock sync, session cache
  - Payloads: VCSEC status, CarServer GetVehicleData(charge)
  |
BleTransport (Kotlin interface)
  |
Android BluetoothGatt now; Kable later for KMP
```

Scope for v1 (SOC + lock state):

- Framing and transport: ~100-150 lines.
- Dispatcher and session/auth: ~600-900 lines (the hard part).
- Payload builders/parsers: ~50 lines per data point; protobuf classes are
  generated.
- Pairing (unsigned `addKey`): ~100 lines.
- Total core: roughly 1,000-1,500 lines plus generated code.

Key storage:

- v1: generate the P-256 key in the app (JCA/BouncyCastle), encrypt the PKCS#8
  with a Keystore-held AES key (StrongBox when available), store in app storage.
- Later: hold the key in Android Keystore and use TEE-backed ECDH (API 31+)
  where the device supports it.

Authentication workflow (matching existing art such as the wimaha/vslavik
proxy dashboard and esphome-tesla-ble):

1. Generate key pair; show the public key / key ID.
2. User enters VIN; app connects to the vehicle.
3. App sends the unsigned add-key request; car reports "waiting for card".
4. User taps an NFC key card on the console and confirms on the vehicle screen.
5. Key is enrolled; subsequent connections run the session handshake.
6. v1 shortcut if desired: enroll the key with `tesla-control` on a laptop and
   import the PEM, deferring in-app pairing to v1.1.

## Implementation milestones

1. `:core` module skeleton, Wire protos pinned to a Tesla commit, framing, and a
   fake transport; JVM unit tests ported from the Go test vectors.
2. Session handshake and dispatcher against the fake transport.
3. Android BLE transport; scan/connect to the car; verify MTU and framing.
4. Pairing flow (unsigned addKey, NFC tap, vehicle confirm).
5. `BodyControllerState` and `GetState(Charge)`; show SOC in the UI.
6. Foreground service, polling strategy (VCSEC ~10s safe asleep; Infotainment
   data only while awake, ~10s while charging), Keystore-wrapped key, session
   persistence.
7. Hardening: retries using error classification (`Temporary`,
   `MayHaveSucceeded`), reconnect logic, diagnostics.
8. Revisit iOS: promote `:core` to KMP with Kable and a Compose Multiplatform or
   native Swift UI.

## Risks and open questions

- Crypto edge cases: 4-byte nonce AES-GCM, SHA-1 truncated session key
  derivation, metadata hash, counter windows, clock sync. Mitigate by porting
  Go's tests and comparing against `tesla-control` as an oracle.
- Protocol drift: pin the `.proto` commit; Tesla occasionally removes fields or
  tightens validation (e.g. pairing validation in firmware 2026.2.x).
- Android Keystore ECDH requires API 31+ and is not always hardware-backed;
  keep the encrypted-software-key fallback.
- Android OEM battery optimization killing background BLE.
- `CHARGING_MANAGER` role support on older vehicles.
- `notes.md` remains stale; this document supersedes it.

## References

- https://github.com/teslamotors/vehicle-command
- https://github.com/teslamotors/vehicle-command/blob/main/pkg/protocol/protocol.md
- https://github.com/misakatao/TeslaBLEKeyKit
- https://github.com/shoujiaxin/swift-tesla-ble
- https://github.com/pmdroid/tesla-vehicle-command (AGPL, reference only)
- https://github.com/JuulLabs/kable
- https://github.com/square/wire
- https://github.com/PedroKTFC/esphome-tesla-ble
- https://github.com/teslamate-org/teslamate/issues/5496
