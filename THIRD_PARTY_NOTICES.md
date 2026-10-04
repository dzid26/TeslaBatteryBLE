# Third-party notices

TeslaBatteryBLE is licensed under the GNU Affero General Public License v3.0
only (AGPL-3.0-only). See [LICENSE](LICENSE).

## Tesla protocol definitions — Apache License 2.0

- `core/src/main/proto/*.proto` are copied from
  [teslamotors/vehicle-command](https://github.com/teslamotors/vehicle-command)
  (Apache-2.0). The pinned revision is recorded in
  `core/src/main/proto/TESLA_COMMIT`.
- The Kotlin protocol implementation in `core/` is a port of the Go
  implementation (Apache-2.0) and of the MIT-licensed Swift ports
  ([misakatao/TeslaBLEKeyKit](https://github.com/misakatao/TeslaBLEKeyKit),
  [shoujiaxin/swift-tesla-ble](https://github.com/shoujiaxin/swift-tesla-ble)).

## Behavioral references only — no code copied

- The AGPL-licensed C++ implementations (pmdroid, yoziru/PedroKTFC lineage)
  were used to understand behavior only.

Full license texts for bundled dependencies (Wire, AndroidX, Kotlin, etc.)
ship with release artifacts and appear in the app's open-source licenses
screen; tracking is in [docs/master-plan.md](docs/master-plan.md).
