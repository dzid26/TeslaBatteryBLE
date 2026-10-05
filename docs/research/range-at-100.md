# Range at 100% SOC: what the car reports, and how to derive it

Date: 2026-10-05
Related: `docs/research/battery-health-methods.md` (method taxonomy), `docs/reference/fleet-telemetry-vs-ble.md` (signal matrix), `docs/research/tesla-ble-capabilities.md` (BLE field catalogue).

Question: does any car-reported value already equal "range at 100% SOC", and if not, what is the best derivation from what the app can read?

## 1. Direct answer: no 100%-SOC range field exists anywhere we can read

Checked exhaustively; the closest values are still "at the current SOC" or "energy, not range".

### BLE (`ChargeState`, `core/src/main/proto/vehicle.proto`)

| Field | # | Meaning | At 100%? |
|---|---|---|---|
| `battery_range` | 111 | Rated range (float, 2 dp): remaining energy / trim's rated consumption constant | No — current SOC |
| `est_battery_range` | 112 | Estimated range (2 dp): current SOC projected with recent consumption | No — current SOC |
| `ideal_battery_range` | 113 | Ideal range (2 dp): idealized constant, legacy display; often absent | No — current SOC |
| `battery_level` | 114 | Displayed SOC % (int32) | SOC, not range |
| `usable_battery_level` | 115 | Usable SOC % (int32); can sit below displayed | SOC, not range |
| `charge_limit_soc` | 104 | Charging target %; 100 only if the owner set it | Target, not range |
| `charge_energy_added` | 116 | kWh added this session (1 dp); 0 when idle | Session delta |
| `charge_miles_added_rated` / `_ideal` | 117–118 | Rated/ideal miles added this session | Session delta |
| `charge_rate_mph` / `charge_rate_mph_float` | 126 / 156 | Charging speed | Rate, not range |
| `minutes_to_full_charge` / `minutes_to_charge_limit` | 123 / 142 | Time estimates | Time, not range |
| `max_range_charge_counter` | 109 | Count of range-mode charges | Counter, not range |
| `powershare_vehicle_energy_left_hr` | 173 | Powershare runtime hours | Not traction range |

A search of the pinned protos for `range`, `energy`, `capacity`, `nominal`, `full` finds nothing else: there is no full-pack energy, no capacity, no `range_at_100`, no range-reset field in BLE.

### Cloud (Fleet Telemetry, pinned `vehicle_data.proto` + Tesla's `available-data.tsv`)

| Signal | # | Meaning | At 100%? |
|---|---|---|---|
| `RatedRange` | 32 | Rated range at current SOC (`charge_state.battery_range`) | No |
| `EstBatteryRange` / `IdealBatteryRange` | 40 / 41 | Estimated / ideal range at current SOC | No |
| `Soc` / `BatteryLevel` | 8 / 42 | Usable / displayed SOC; cloud can send sub-percent reals | SOC |
| `EnergyRemaining` | 158 | Nominal energy remaining, kWh | Current energy |
| `NominalFullPackEnergyKwh` | 263 | True full-pack energy, kWh (proto only; added for firmware 2026.32, missing from Tesla's docs table) | Capacity in kWh, not miles |
| `BmsFullchargecomplete` | 3 | Boolean "BMS is fully charged" | Marker, not range |
| `ChargeLimitSoc`, `DC/ACChargingEnergyIn` | 38, 34, 36 | Limit and session energy | Not full range |

No range-at-100 signal. Fleet Telemetry also needs an owner account plus a self-hosted server; the app cannot read it today.

### Service Mode / in-car

- Controls > Service > **Battery Health** (and the longer Battery Health Test) reports a state-of-health % / capacity-vs-new after a controlled cycle (conditions: <20% SOC, AC charger ≤5 kW, up to 24 h; rate-limited). No API — the result is read off the touchscreen and typed in.
- The car's own range display (Rated/Estimated/Ideal) at 100% is the empirical ground truth, not a field: it is exactly `battery_range` rendered at `battery_level = 100`.

**Conclusion:** the only direct "range at 100%" is the car's own display while actually at 100% SOC. Everything else must be derived.

## 2. Derivations and their error modes

### Rated range / SOC (primary)

`fullRatedRange ≈ ratedRangeMiles / (soc / 100)` — the app's `RatedRangeEstimator` against an EPA baseline is this formula.

- **Usable vs displayed SOC.** Rated miles are computed from usable energy; the bottom buffer is excluded from `usable_battery_level`. At 100% both read 100, so the anchor point is unaffected; below 100% the conventions diverge by roughly the buffer share. We now log both raw ints (`batteryLevel`, `usableBatteryLevel`) so the calibration below can pick the one that reproduces the 100% anchor.
- **Quantization.** BLE SOC is a whole int32, so 1% ≈ 1% of the result near 100% but ≈2% at 50%; cloud SOC is real. This is the floor on single-reading accuracy.
- **BMS noise.** SOC and range are estimates. Near the top the OCV curve is steep and the BMS recalibrates; after `Complete` the pack keeps top-balancing for tens of minutes. Read rested (≥30 min), not during taper/termination.
- **Temperature/rest.** Available energy is temperature-dependent; a cold-soaked car can report less. Note ambient temperature and compare like with like; trend over weeks (single reading ±2–5%, trended ±1–2% per `battery-health-methods.md`).
- **Firmware.** Tesla re-scales the rated constant with OTA updates, so full-range history has steps unrelated to degradation.
- **Charge limit.** A reading at `charge_limit_soc = 80` is a range at 80%, not full. Only treat `Complete` + `chargeLimit = 100` as an anchor.

### Estimated / ideal variants

- `est_battery_range` mixes recent consumption into the projection, so it moves with driving conditions rather than capacity; it is the right "real-world" number but the wrong SoH input. Error is largest after highway/cold drives.
- `ideal_battery_range` uses an idealized (non-EPA) constant and is optimistic; newer firmware often omits it. Logged for completeness only.

### Energy delta (`charge_energy_added`, `charge_miles_added_rated`)

`capacity ≈ energyAdded / (ΔSOC / 100)` over one charge session, or `charge_miles_added_rated / (ΔSOC / 100)` for a full-range estimate directly.

- Losses inflate "added": BLE exposes one `charge_energy_added`; Tesla's cloud splits AC (charger-measured) from DC (battery-measured) and that distinction is lost over BLE. HVAC/sentry load during the session also counts.
- Small swings amplify SOC error: use ΔSOC ≥30–50%, rested endpoints, repeat and average (±3–5% per session, ±1–3% averaged).
- `charge_miles_added_rated` is derived from energy added / rated constant, so it carries the same bias.

### Cross-check

Average the range-method and energy-delta results; flag a gap >5 pp as a data-quality issue (per `battery-health-methods.md`). The 100% anchor, not a mid-SOC extrapolation, should settle the usable-vs-displayed question.

## 3. Real-car collection procedure

### Fields to collect (all now logged per charge-state read)

History CSV columns: `vehicleId,timestampMillis,percent,chargeLimit,chargingState,socPercent,rangeMiles,batteryLevel,usableBatteryLevel,ratedRangeMiles,estRangeMiles,idealRangeMiles,chargeEnergyAdded,chargeMilesAddedRated,chargeMilesAddedIdeal`.

- `percent` is the rounded form of `socPercent`; `socPercent` is `usable_battery_level` when plausible, else `battery_level` (`PreciseReading`).
- `rangeMiles` is `est_battery_range` when plausible, else `battery_range`; the explicit columns carry the raw trio.
- Identical percent+state readings within 60 s are skipped; the store keeps the newest 20k rows. Rows from the older CSV format are dropped on load (pre-1.0, no migration).

Manually note per session: ambient temperature, minutes since the last drive or charge (rest), the car's displayed range and display mode (Rated / Estimated / Ideal), and firmware version.

### Procedure

1. Install a build with the extended logging (this branch / next preview).
2. **Sweep:** with the car parked and rested ≥30 min, capture readings at a few SOC points, e.g. ~90%, ~70%, ~50%, ~30% (different days are fine). Opening the car screen while connected records a sample.
3. **100% anchor:** set charge limit to 100 (`chargeLimit = 100`), charge until `chargingState = Complete`; note `chargeEnergyAdded` and `chargeMilesAddedRated` at completion. Leave parked 30–60 min (top balance), then take one reading. Record the car screen's Rated range for comparison.
4. **Repeat** the 100% anchor on a second day/temperature to see repeatability.
5. **Service Mode (optional):** run Controls > Service > Battery Health, record the SoH %/range verdict; run rarely (full cycle, rate-limited, no API).
6. **Export:** `adb exec-out run-as com.dzid26.teslable cat files/battery-history.csv > battery-history.csv` (needs a debuggable build; the Keystores session has pulled this file before), or the owner exports/shares it.

### Analysis

- For each mid-SOC row compute `ratedRangeMiles / (batteryLevel / 100)` and `ratedRangeMiles / (usableBatteryLevel / 100)`; compare both with the 100% anchor. The correct convention reproduces the anchor within ~1–2 mi; the spread across points is BMS noise.
- Energy check: `chargeEnergyAdded / ((socEnd − socStart) / 100)`; compare with the range-derived capacity (`fullRatedRange / ratedConstant`, or simply full rated miles × the car's rated Wh/mi if known). Flag >5 pp gaps.
- Feed the anchor and the sweep into `RatedRangeEstimator` with the owner's exact EPA baseline for the trim/year.

### Success criteria

One SOC convention reproduces the 100% anchor within ±2 mi across points, and the energy-delta capacity agrees with the range-derived capacity within ~5%. Anything worse means the endpoints were not rested or the swing was too small.

## 4. Logging change (this branch)

The app previously logged only `socPercent` and the est-preferred `rangeMiles`, so the raw rated/ideal ranges, both SOC variants, and the session counters were unavailable for calibration. This branch adds them end-to-end:

- `TeslaCommands.Charge`: parses `ideal_battery_range`, `charge_energy_added`, `charge_miles_added_rated`, `charge_miles_added_ideal` (test: `TeslaCommandsTest`).
- `BatterySample` + `BatteryHistoryCsv`: new nullable columns `batteryLevel`, `usableBatteryLevel`, `ratedRangeMiles`, `estRangeMiles`, `idealRangeMiles`, `chargeEnergyAdded`, `chargeMilesAddedRated`, `chargeMilesAddedIdeal`; 15-column format, older rows drop on load (no migration, pre-1.0).
- `BatteryHistoryStore.record` copies every new field from the charge state.

No UI change; the chart and stats keep using `rangeMiles`.
