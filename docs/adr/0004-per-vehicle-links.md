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
- **No migration code.** The only install is the owner's, and its data was
  converted to the new formats once (history CSV rewritten in place, vehicles
  store created over the old one). New installs start clean; do not add
  back-compat paths for formats no install uses.

## Consequences

- Multi-car tracking works without a shared mutable "selected car" leaking into
  protocol state, and the model scales to more cars by raising the cap.
- The advertised name is stable but not human-friendly; the UI shows the car's
  GATT name or a user-set display name.
- Adding a second car is: scan, connect, enter its VIN, pair (NFC card), done.
