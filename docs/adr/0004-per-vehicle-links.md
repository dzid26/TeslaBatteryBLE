# ADR-0004: One BLE link per vehicle, advertised name as identity

Status: Accepted
Date: 2026-10-05
Related: `docs/requirements/multi-vehicle.md`, ADR-0001, ADR-0003

## Context

The app was built around one car: a single selected address drove polling,
sessions, wake and SOC; one global VIN fed every session; one CSV held every
sample. Supporting several cars (see the requirements) needs per-car state and
an identity that survives BLE address rotation.

## Decision

- **Identity.** The advertised name (`S<sha1(VIN)[:8]>C`) is the vehicle
  identity. The BLE address is an attribute that follows the car, and UI
  selection is by advertised name, so a rotating address never breaks state.
- **One link per car.** The controller owns a `VehicleLink` per vehicle:
  transport, sessions, pending commands, pairing state, retry/backoff counters
  and timers. One car failing cannot disturb another.
- **Connection policy.** Up to three cars connect at once; every awake car with
  a VIN reports SOC, not just the selected one. Silent discovery surfaces all
  known cars and is time-boxed; the user-initiated scan is additive.
- **VIN per vehicle.** Entered by the user, validated against the advertised
  name, stored with the vehicle, never logged. One app key pair is enrolled per
  car; the key slot is recorded per vehicle.
- **History per vehicle.** Rows carry the vehicle id (advertised name); graphs
  and charge stats filter by vehicle.
- **No migration code.** The app is pre-1.0 and has no users yet, so
  formats change freely and a reset (re-pair / reinstall) is documented instead
  of migrated. Revisit once real installs exist.
- **When "no migration" ends.** The first normal release, meaning a release
  tag that is not a prerelease (owner decision, 2026-10-16, issue #123).
  Prerelease builds do not count. Until then formats change freely. From that point every on-device format
  (history logs, summaries, vehicle store, key files) carries a version, and
  each change ships an importer or at least an in-app "history was reset"
  notice. At the same time each shipped format gets a golden fixture file and a
  test that the current reader accepts it.

## Consequences

- Multi-car tracking works without a shared mutable "selected car" leaking into
  protocol state, and the model scales to more cars by raising the cap.
- The advertised name is stable but not human-friendly; the UI shows the car's
  GATT name or a user-set display name.
- Adding a second car is: scan, connect, enter its VIN, pair (NFC card), done.
