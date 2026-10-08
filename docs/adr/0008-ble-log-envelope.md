# ADR-0008: BLE log envelope

Status: Accepted
Date: 2026-10-08
Related: ADR-0006 (raw ChargeState log, amended here), ADR-0004 (vehicle
identity), `docs/requirements/multi-phone.md`

## Context

- ADR-0006 logs the car's raw `ChargeState`, one file per vehicle, and takes
  time only from the car's own `timestamp`. It allows no custom proto and no
  per-record envelope.
- While connected, the app asks VCSEC for the vehicle status every 10 s,
  asleep or not; it is the one channel that answers without waking the car.
  Until now the readings only reached the in-memory debug log, on change.
- Each reading carries little, but together they are useful data points: when
  the car slept, woke, was locked, and had someone in it. TeslaMate's
  asleep/online states can be mapped from them later, and the parked-drain
  projection needs user presence to tell parked stretches from drives.
- `VCSEC.VehicleStatus` (closures, lock state, sleep status, user presence,
  detailed closures) has no timestamp field, and the reply carries no time
  either, so a raw status record would have no place on the timeline.
- DriveState is next, and more BLE replies will follow. Handling each as its
  own exception to ADR-0006 would not scale; one envelope for all of them
  does. The phone's clock at arrival is also worth keeping for the replies the
  car does stamp: it says when the phone read what the car reported.

## Decision

- **One envelope for every logged reply.** `BleRecord`
  (`core/src/main/proto-teslable/ble_record.proto`) holds:
  - `acquired_at`: the phone's clock when the reply arrived;
  - a `payload` oneof with the car's raw reply, verbatim, nothing filtered:
    `vehicle_status` (field 2) or `charge_state` (field 3). `DriveState`
    joins as field 4 later. Fields follow a reading's order: time, VCSEC
    status, charge, drive.

  Tesla's vendored protos (`core/src/main/proto`, pinned by `TESLA_COMMIT`)
  stay untouched; ours sit in a second Wire source root, so a re-vendor never
  touches it. This amends ADR-0006's "no custom proto, no envelope".
- **Car timestamps are untouched.** Phone time lives only in `acquired_at`:
  it is never written into a car timestamp field and never stands in for a
  missing one. Records the car stamps keep that stamp as their timeline:
  charge samples stay on `ChargeState.timestamp`.
- **Every reply is logged raw** (owner, 2026-10-08: keep all raw). A charge
  reply without the car's timestamp or without a level stays in the log but
  off the chart; this replaces ADR-0006's "not logged".
- **Status is timed by `acquired_at`**, because VCSEC has no clock. It is the
  one kind whose timeline is the phone's clock.
- **One file per vehicle per kind**, in `filesDir/battery-history/`, framed by
  the same `ProtoLog` codec with the same 20k-record cap and trim per file:
  - `<vehicleId>.charge.pblog` for charge replies;
  - `<vehicleId>.vcsec.pblog` for VCSEC status replies;
  - `<vehicleId>.drive.pblog` for DriveState replies, later.

  Each kind has its own suffix and none ends with another, so a reader never
  decodes another kind's file. The backup rules already exclude the
  directory. The store keeps the last logged status record per vehicle,
  seeded from the file on load, so the policy below holds across restarts.
- **Status logging policy** (`shouldLogStatus` in `core`). A reading is logged
  when any of these holds:
  - it is the first reading for the vehicle;
  - it is the first reading after a (re)connect;
  - the raw `VehicleStatus` differs from the last logged one;
  - otherwise, 15 minutes have passed since the last logged one.

  The heartbeat tells a sleeping car with a phone connected apart from a phone
  out of range: while a phone is connected the log gains a record at least
  every quarter hour, so a longer gap means no phone was connected. A reading
  acquired before the last logged one (the phone's clock moved back) is
  logged too.

  When the link drops, the newest reading the policy held back is logged as
  well (`StatusLogGate` in `core`), so each observed stretch ends at the last
  reading before the phone lost the car, within the 10 s poll, rather than up
  to a heartbeat earlier. If the app is killed instead, the heartbeat still
  bounds that end to 15 minutes.
- **Read models.** `BatterySample` and `StatusSample` are derived on load from
  the records (`BleRecord.toBatterySample`, `BleRecord.toStatusSample`);
  nothing is written back.
- **Evolution rules** (repeated at the top of `ble_record.proto`). The logs
  are append-only and long-lived, so the envelope only ever grows additively:
  - New fields and new `oneof` payload kinds only get new field numbers. Old
    records read them as absent, and older app versions skip them as unknown
    fields.
  - Never renumber, reuse or change the type of an existing field. A removed
    field's number and name go under `reserved`.
  - Never move an existing field into or out of the `oneof`.
  - New scalar fields use proto3 `optional`, so absent stays distinguishable
    from zero.

## Consequences

- **Reset (pre-1.0).** Older `<vehicleId>.pblog` files (raw `ChargeState`
  frames, ADR-0006) are no longer read or written, and they are not deleted.
  After updating, the history chart starts empty and fills from new reads. The
  old files stay in app storage, so a one-time import that wraps them in
  `BleRecord`s can come later if wanted; the charge timeline needs no
  `acquired_at`, so those records could leave it absent. No migration code
  runs. Status logs use the new name `.vcsec.pblog`, so a `.status.pblog`
  left by a pre-merge test build (an earlier record format) is ignored the
  same way instead of hiding every later record. Pre-merge test builds of
  this change also numbered the payload the other way round (charge 2,
  status 3). Those records decode as the wrong kind or not at all: the read
  models skip a record without their payload, and `ProtoLog` skips a complete
  record that fails to decode, so the records after it still read and nothing
  needs deleting.
- **Clock skew.** Status samples sit on the phone's clock, charge samples on
  the car's. The two agree to within seconds, which is fine for this use:
  status marks stretches of minutes to hours (asleep, parked, someone in the
  car), and nothing joins the two logs at second precision. Charge records
  also carry `acquired_at`, so the offset between the clocks can be measured
  when it matters.
- **Multi-phone merge** (`docs/requirements/multi-phone.md`): a vehicle's
  history is the union of every phone's records, ordered by the car's
  timestamp for charge and by `acquired_at` for status, with exact duplicates
  collapsing. Two phones watching at once only make the timeline denser.
- **Coverage.** Status readings exist only while a phone is connected; the
  gap between the last reading before a link drop and the first after the
  next connect is unlogged time, not sleep. At four heartbeats
  an hour plus one record per change, the 20k cap holds months of status per
  car.
- **Drives.** DriveState gets its own file later, as the `payload` oneof's
  field 4. It carries the car's own timestamp, which stays its timeline, so it
  needs no exception, and its odometer will separate drives that no phone
  saw.
