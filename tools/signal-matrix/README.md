# Signal matrix (Fleet Telemetry vs BLE)

Generates [`docs/reference/fleet-telemetry-vs-ble.md`](../../docs/reference/fleet-telemetry-vs-ble.md):
a living comparison of what Tesla exposes through **Fleet Telemetry** (cloud, the same
vehicle data as the Fleet API JSON) versus what is readable over **local BLE**
(vehicle-command protobufs).

## Sources of truth

- **Cloud:** vendored protos from `teslamotors/fleet-telemetry`, pinned in
  `upstream/fleet-telemetry/FLEET_TELEMETRY_COMMIT`. The signal list comes from the
  `Field` enum in `vehicle_data.proto`.
- **BLE:** `core/src/main/proto/*.proto`, pinned to Tesla's vehicle-command revision in
  `core/src/main/proto/TESLA_COMMIT`.
- **The cross-map:** `mapping.json` — hand-curated equivalences, cloud-only rows, and the
  command table. This is the file to edit when you want the document to change.

## Commands

```sh
python tools/signal-matrix/generate.py            # regenerate the doc (offline, deterministic)
python tools/signal-matrix/generate.py --check    # fail if the committed doc is stale (CI)
python tools/signal-matrix/generate.py --refresh  # update vendored Fleet Telemetry protos from upstream
python tools/signal-matrix/generate.py --status   # print pinned vs upstream SHAs
python tools/signal-matrix/generate.py --dump     # list parsed message fields (mapping authoring)
```

No third-party Python packages are required.

## Rules

- The generated doc is **not edited by hand**.
- Every `cloud` name in `mapping.json` must exist in the Fleet Telemetry `Field` enum and
  every `ble` reference must exist in the pinned BLE protos; the generator fails loudly
  otherwise (that is the CI `--check`).
- Signals in the Field enum that are neither mapped nor ignored show up under
  *Unreviewed upstream signals* in the doc. That table is the review queue; keep it empty.
- BLE protos are **never auto-bumped**: `core/src/main/proto` changes are deliberate,
  tested changes. The weekly workflow only reports when upstream vehicle-command moves
  ahead of `TESLA_COMMIT`.

## Drift workflow

[`.github/workflows/signal-matrix.yml`](../../.github/workflows/signal-matrix.yml) runs
weekly (and on demand): it refreshes the vendored Fleet Telemetry protos, regenerates the
document, and opens a PR when anything changed. PRs touching this tool run `--check`.
