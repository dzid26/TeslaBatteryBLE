# ADR-0013: Split the Tesla BLE protocol into a library module

Status: Accepted
Date: 2026-10-16

## Context

- The plan has always been for the protocol code to become a reusable Tesla BLE
  library after v1 (master plan §1, Phase 3).
- `core` held two things: the Tesla BLE protocol (framing, keys, pairing,
  sessions, commands, Tesla's vendored protos) and this app's battery domain
  (history, projections, health, the app's own `ble_record.proto`). The
  protocol imported app log records, so the two could not be packaged apart.
- The KMP migration (ADR-0007) is about to move source sets. Moving files once,
  before it starts, keeps that migration to a single layout.

## Decision

- New Gradle module `:protocol` (package `com.dzid26.teslable.protocol`), with
  framing, GATT UUIDs, VIN names, keys, pairing, session handshake, commands,
  state views, reply predicates, and Tesla's vendored protos.
- `:core` keeps the battery domain (`history`, `health`, and the app's
  Infotainment poll policy in `core.infotainment`, ADR-0009). It depends on
  `:protocol` with `api`. Its Wire config generates only `ble_record.proto`,
  and it resolves the Tesla imports from `:protocol`'s proto root.
- `:protocol` never imports `:core` or the app. The Wire runtime is an `api`
  dependency because requests and replies are Tesla proto messages.
- Publishing, once the library is ready: Maven Central as
  `io.github.dzid26:teslable-protocol`. JitPack handles KMP and Apple targets
  badly, and GitHub Packages makes consumers authenticate to read.
- Same repository. The library is versioned on its own (`0.x` semver,
  `protocol-v*` tags), so the Go-vector CI and the simulated car stay next to
  the code.
- License stays AGPL-3.0-only, like the rest of the repository.

## Consequences

- Run tests with `:protocol:test :core:test`. CI's verify script runs both.
- Each module has its own detekt baselines. The existing findings moved with
  their files.
- Still to come before a first release: extract the post-handshake logic
  (wake, authenticated sends, reply routing) from the app's `VehicleLink` into
  `:protocol` behind a scheduler seam; move `TeslaTransport` down from the app;
  explicit API mode plus an ABI dump; KMP (ADR-0007); Dokka; the Central account
  and signing key (owner).
