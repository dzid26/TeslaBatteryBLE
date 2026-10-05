# Requirements: multiple vehicles and per-vehicle VINs

Status: Implemented (2026-10-05): per-vehicle storage, links and VIN, per-car notifications, cars list → car view navigation. All open questions decided (section 12).
Date: 2026-10-04 · Updated: 2026-10-05
Related: `docs/adr/0001-battery-tracker.md` (ADR-0001), `docs/adr/0002-battery-history-storage.md`, `docs/master-plan.md`

## 1. Problem

The app was built around one car. A single `selectedAddress` drives polling,
sessions, wake and SOC reads; one global VIN feeds every session; one history
file holds every sample; one notification represents "the car". A second
vehicle breaks all of these assumptions, and the global VIN is wrong the moment
two cars exist. This document defines the target behavior so the multi-vehicle
model can be implemented deliberately rather than patched.

## 2. Scope

In scope:
- Multiple Tesla vehicles on one phone: discovery, pairing, VIN, sessions,
  history, notifications, UI, migration of existing single-car data.
- Per-vehicle VIN entry, validation and storage.

Out of scope (unchanged unless stated):
- No cloud, no accounts, nothing leaves the device.
- iOS (still deferred).
- Commands beyond wake and SOC read.
- Roles other than CHARGING_MANAGER.
- Sharing/exporting data; aggregate "all vehicles" charts (see open questions).

## 3. Definitions

- **Vehicle**: a physical Tesla. Stable identity is its advertised BLE name;
  once known, its VIN.
- **Advertised name**: `S` + first 8 bytes of `sha1(VIN)` in hex + `C`
  (e.g. `Se1f0941734830fe7C`). Stable per VIN, no PII on the air.
- **BLE address**: the address Android connects to. It may rotate; it is an
  attribute of a vehicle, never its identity.
- **Phone key**: the app's single P-256 key pair, created once per install. It
  can be enrolled in any number of vehicles; enrollment is per vehicle.
- **Paired**: the app's key ID is present in that vehicle's whitelist.

## 4. Vehicle identity and VIN

- The advertised name is the identity key. When a known vehicle is seen at a
  new BLE address, update its address; never create a duplicate.
- One user-editable display name per vehicle; default to the car's GATT name
  (`🔑 Teslak`) or the advertised name.
- **VIN is per vehicle and required for authenticated work**: it is the
  personalization in every session MAC. It cannot be read over BLE (verified),
  so it is entered by the user.
- VIN entry flow:
  1. User enters a VIN for a vehicle (17 chars, normalised, case-insensitive).
  2. The app computes `sha1(vin)[:8]` and compares it with the vehicle's
     advertised name.
  3. Match → save; mismatch → refuse with a clear message showing the expected
     advertised name, and let the user retry or cancel.
- A vehicle may exist without a VIN (discovered, not yet paired). Authenticated
  features (pairing check, sessions, SOC) stay disabled until a VIN is set and
  the vehicle is paired.
- Privacy: the VIN is stored locally only, masked in the UI by default
  (e.g. `5YJ…3421`, with an explicit reveal), and **never logged or shown in
  notifications**.

## 5. Pairing and key enrollment

- Keep one app key pair per install. Enrollment and key slot are per vehicle.
- Pairing is per vehicle: select the vehicle → Pair → tap the NFC card →
  confirm on the car screen → poll that vehicle's whitelist until the app key
  ID appears → store the key slot and mark the vehicle paired.
- The "rename the Phone Key in Controls > Locks" hint appears only for the
  vehicle that just paired successfully, never on routine reconnects.
- Enrollment is re-verified on connect (whitelist request) and drives whether
  the Pair action is offered.
- Removing a vehicle from the app never removes the key from the car; document
  that unpairing on the car is manual.

## 6. Connection management

- All known vehicles are remembered and reconnected automatically when in
  range; scanning is never required for reconnection.
- The tracking service maintains an independent connection state per vehicle
  in range (session, pending commands, retries, RSSI, status, charge). One
  vehicle failing must not affect another.
- Polling per vehicle follows ADR-0001: VCSEC status ~10 s (safe while
  asleep); charge state only while that vehicle is awake; never wake a car to
  read SOC.
- A user-initiated scan adds newly discovered cars to the list (with a Pair
  option); it does not tear down existing connections. Automatic discovery for
  known vehicles stays silent (no scan-button takeover).
- Recommended cap: connect to at most 3 vehicles at once, prioritising the
  active vehicle, then most recently seen; document the cap in the UI/log.
- RSSI updates ~2 Hz while the UI is visible (current behavior), per vehicle.

## 7. UI

- **Cars list** (root): one row per known or discovered vehicle with its name,
  the four-state status (`Disconnected` / `Connected 💤` / `Connected (reading)` /
  `Connected · NN%`), SOC when known, and RSSI. Newly scanned, unpaired cars
  appear with a Pair action.
- **Car view**: hero SOC, Wake, Read SOC, phone-key state and key slot, VIN
  editor (with the advertised-name check), battery history and "since last
  charge" stats, and the log filtered to that vehicle.
- No tabs: opening a car switches from the list to its view; back is the
  app-bar arrow plus system back. The last opened car is restored on launch.
- The global "VIN (optional)" field is gone; the global Enable toggle and
  permission handling stay global (on the cars list).

## 8. Notifications

- One ongoing notification per connected vehicle, grouped; title = display
  name, text = the four-state status, Wake action when that vehicle is asleep,
  tapping opens that vehicle.
- Master disable stops tracking for all vehicles; it remains an in-app toggle.
- Throttle per vehicle: re-post on state change or an RSSI move of ≥5 dBm
  (current single-car behavior, generalized).

## 9. History

- Samples are stored per vehicle: `vehicleId, timestamp, percent,
  chargingState, chargeLimit`.
- `vehicleId` is the VIN when known, otherwise the advertised name (upgrade the
  rows when the VIN becomes known, or accept the advertised name as the key and
  map VIN → key in the vehicle record).
- Charts and statistics filter by the selected vehicle. Retention unchanged.
- ADR-0002 is extended (not replaced) with the new schema and migration.

## 10. Migration of existing installs

- `known_cars` entries become vehicles; their display name starts from the
  stored GATT name.
- The single stored VIN applies to the vehicle whose advertised name matches
  `sha1(vin)[:8]`. If nothing matches, keep the VIN in a pending state so the
  user can assign it.
- Existing `battery-history.csv` samples are assigned to that same vehicle.
- The key pair is untouched; enrollment is re-verified per vehicle on the next
  connect.
- No data loss; keep reading old preference keys for at least one release.

## 11. Acceptance criteria

1. Scan → connect → set VIN (validated against the advertised name) → pair →
   SOC readable, for a second vehicle, with the first vehicle's data untouched.
2. Two vehicles in range: both connect and update independently; the Battery
   tab shows only the selected vehicle's samples.
3. A BLE drop on one vehicle recovers with backoff without disturbing the
   other's connection or polling.
4. One notification per vehicle with the correct state; Wake wakes the right
   vehicle.
5. A VIN mismatch is rejected with a clear message; the VIN never appears in
   logs or notifications.
6. A single-car install migrates with its vehicle, VIN, enrollment and history
   intact.
7. An unreachable or unpaired vehicle is shown as such and never blocks other
   vehicles.

## 12. Decisions (open questions resolved 2026-10-05)

- **Simultaneous connections**: hard cap of 3 (`MAX_ACTIVE_LINKS`). When more
  known cars are in range, the active/selected car is prioritised, then the
  most recently seen; the rest stay disconnected and are picked up as slots
  free. A cap of 3 matches the per-vehicle link budget and the test fleet.
- **Aggregate "all vehicles" chart**: later (Phase 2+). Charts stay per vehicle
  so an unreachable car cannot distort another car's history.
- **Notifications**: one grouped notification per vehicle plus a group summary,
  each with its own Wake action and tap-to-open. Implemented.
- **History key**: the advertised name is the row key (`vehicleId`); the VIN is
  an attribute of the vehicle record and never rewrites history rows. A car
  whose VIN is entered later keeps its existing samples.
- **Unpairing in-app**: not offered yet. When it lands, removing a vehicle
  offers (never forces) clearing that vehicle's history; the key on the car
  stays until the owner removes it manually.

## 13. Current single-car assumptions to unwind (implementation map)

- `TeslaBleController`: `selectedAddress`, `poll`, `sessionRetry`, `reconnect`,
  `wakeRefresh`, `chargeAfterSession`, `readChargeAfterWake`, `keySlotQueue`,
  `decryptFailures`, `lastRehandshakeMs`, `failedAddresses`, `knownCars`
  (already keyed by advertised name). Everything keyed per vehicle.
- `BleUiState`: `devices`, `connections`, `selectedAddress` — becomes a vehicle
  map plus a selected vehicle id.
- `PairingKeyStore`: one key (keep), but enrollment/slot must be per vehicle.
- `BatteryHistoryStore`: single CSV without a vehicle column.
- `BleTrackingService`: single notification/model.
- `ScannerScreen`: global VIN field, single car list, single history.
- Preferences: the `vin` key is global and must move into the vehicle record.

## 14. References

- ADR-0001: protocol facts (advertised name, sessions, polling cadence).
- ADR-0002: battery history storage (to be extended).
- `docs/master-plan.md`: Phase 2 "Multiple cars" item.
- Tesla BLE tips: connection limit per vehicle, stop scanning once connected.
