# ADR-0006: Battery history protobuf log

Status: Accepted
Date: 2026-10-05

## Context

ADR-0002 stored history as an append-only CSV. Every new raw field forced a
format change that dropped all earlier rows (pre-1.0 hygiene), and the app is
about to log more fields (charge rates). The existing raw-only history had to
survive, and future fields must not reset it again.

## Decision

- Store history as an append-only log of length-delimited protobuf records
  (`BatterySampleRecord`, `core/src/main/proto/history.proto`) at
  `filesDir/battery-history.pb`, behind the unchanged `BatteryHistoryStore`
  API (StateFlow + `record()`).
- The schema is additive: fields are only added, readers ignore unknown
  fields, and absent optional fields decode as null. No more format resets
  for new fields.
- On first run the raw-only CSV is imported once (parsed per row; rows from
  older formats are skipped), written to the log, and the CSV is retired as
  `battery-history.csv.imported`. The CSV is no longer written.
- The in-memory cache stays capped at 20k samples; exceeding the cap rewrites
  the log with the newest records via a temp file and an atomic move.

## Consequences

- Old rows survive field additions; pre-store installs keep their raw-only
  CSV rows and lose only rows from older formats (pre-1.0 hygiene).
- No one-off adb fixes for schema changes: extend `history.proto`, log, done.
- The file is binary; inspection uses the app or a decoder rather than a text
  editor. A CSV export can return later as an explicit feature.
- A truncated trailing record (interrupted append) is ignored on load.
