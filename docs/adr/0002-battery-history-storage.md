# ADR-0002: Battery history storage

Status: Superseded by ADR-0006 (protobuf log)
Date: 2026-10-04

## Context

The app needs SOC history for an Android-style battery graph and "since last
charge" statistics. The data is a time series of SOC readings, only available
while the car is awake (see ADR-0001's polling cadence).

## Decision

- One sample per SOC read: vehicle id (advertised name, per ADR-0004),
  timestamp, and raw car fields only — displayed/usable SOC, rated/estimated/
  ideal ranges, and session energy counters when the car reports them — plus
  the charge limit and charging state; display values are derived at runtime.
- First cut: append-only CSV at `filesDir/battery-history.csv`, loaded into
  memory (capped at 20k samples) behind a `StateFlow`. Repeated identical
  readings within a minute are skipped so polling does not flood the file.
- UI: graph with 6h/24h/7d/All ranges, charging segments in green, and stats
  since the last completed charge. Graphs filter by vehicle.

## Consequences

- Zero dependencies and easy to inspect or export; ships now.
- The CSV format is not stable before 1.0: rows written by an older format are
  dropped on load, never migrated.
- Aggregations are in memory. Move to Room/SQLite when queries, retention, or
  the number of vehicles outgrow the CSV.
