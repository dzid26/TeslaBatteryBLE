# ADR-0006: Raw ChargeState history log

Status: Accepted
Date: 2026-10-06
Amended by: ADR-0008 wraps every logged reply in a `BleRecord` envelope with the phone's acquisition time (the car's timestamp stays the charge timeline) moves charge records to `<vehicleId>.charge.pblog`, and logs every charge reply, keeping one without a timestamp or level off the chart. Read the "no envelope", "not logged" and file-name lines below as the first cut.

## Context

ADR-0002 stored history as an append-only CSV. Every new raw field forced a
format change that dropped all earlier rows (pre-1.0 hygiene), and the app is
about to log more fields (charge rates, charger power/voltage/amps). Tesla's
`vehicle.proto` already models the charge state completely — including a
timestamp (`ChargeState.timestamp`, field 44) — so a custom record schema
buys nothing.

## Decision

- History is an append-only log of the car's raw `ChargeState` records,
  length-delimited (varint length + message bytes, `ProtoLog`, which frames any
  Wire message and will serve drive records too). No custom proto, no
  per-record envelope.
- One file per vehicle: `filesDir/battery-history/<vehicleId>.pblog`, where
  `<vehicleId>` is the advertised BLE name the app records (ADR-0004's stable
  key). Tesla names are `S` + the first 8 bytes of SHA-1(VIN) in hex + a
  suffix letter (C, with D/P/R variants reported); the scanner accepts the `C`
  form today, so the name is a stable, filesystem-safe key. The raw response
  carries no vehicle id, so identity lives in the file name; pre-ADR-0004 rows
  with an empty id live in `legacy.pblog`. If the app later accepts other
  suffixes or prefers a VIN-derived key, files can be merged on the shared
  hash.
- Time comes only from the car's own `timestamp`, which it sets on every
  sample. The app never fills one in, so every logged record is exactly what
  the car sent; a record without a timestamp or a level has no place on the
  timeline and is not logged. Absent fields stay absent: records are sparse
  snapshots, readers ignore unknown fields, and new car fields never drop old
  rows. The whole raw response is kept, not just the fields the app parses.
- Pre-store CSV history is not migrated: the old CSV and older formats are
  ignored (pre-1.0 reset), and the CSV is no longer written. An explicit
  import/export feature can return later.
- Each vehicle file keeps its newest 20k records (rewritten via temp file +
  atomic move, trimming a thousand below the cap so a full file costs one
  rewrite per thousand appends); the in-memory cache holds the newest 20k
  samples overall. Every SOC read is logged; nothing is deduplicated.
- `HistoryStore` keeps the old store's API (constructor, `samples`
  StateFlow, `record(vehicleId, charge)`), minus the phone-time parameter that
  `record` no longer needs.
- Backup rules exclude the log directory.

## Consequences

- Old rows survive field additions; unknown future fields survive reads and
  rewrites because the raw message is what gets stored.
- Installs upgrading from the CSV start with empty history; no migration code
  runs.
- No one-off adb fixes: the record format is Tesla's, and it only changes when
  Tesla changes it.
- No envelope: ordering and identity come from the file and its name. A
  cross-vehicle merge reads every file and sorts by the record timestamp.
- Derived values (learned scale, health, SOC at display) are never stored; the
  log stays raw. A heavy derived cache, if one ever appears, belongs in a
  separate artifact, not in the raw log.
- SQLite/Room is not needed yet: the capped history is one linear scan into
  memory, and all aggregation happens there. If queries outgrow that, the
  store can move to Room with the raw record bytes in a BLOB column plus
  indexed vehicle/timestamp columns, behind the same API.
