# Range as an online SOC signal: what the car reports, and what it can tell us

Date: 2026-10-05
Related: `docs/research/battery-health-methods.md` (method taxonomy), `docs/reference/fleet-telemetry-vs-ble.md` (signal matrix), `docs/research/tesla-ble-capabilities.md` (BLE field catalogue).

Question: can the float rated range give sub-percent SOC online, without charging to 100%? This rework supersedes the earlier range-at-100 framing for `bestSocPercent`: active 100% calibration is out of scope (owner decision 2026-10-05). The range-at-100 question stays answered below as a by-product.

## 1. What the car reports (raw inventory)

### BLE (`ChargeState`, `core/src/main/proto/vehicle.proto`)

| Field | # | Proto | Meaning | At 100%? |
|---|---|---|---|---|
| `battery_range` | 111 | L318 | Rated range (float, 2 dp — L317) | No — current SOC |
| `est_battery_range` | 112 | L319 | Estimated range (2 dp); consumption-projected (TSV L90) | No — current SOC |
| `ideal_battery_range` | 113 | L320 | Ideal range (2 dp); idealized constant, often absent | No — current SOC |
| `battery_level` | 114 | L322 | Displayed SOC % (int32) | SOC, not range |
| `usable_battery_level` | 115 | L323 | Usable SOC % (int32); can sit below displayed | SOC, not range |
| `charge_limit_soc` | 104 | L309 | Charging target %; 100 only if the owner set it | Target, not range |
| `charge_energy_added` | 116 | L326 | kWh added this session (1 dp — L325); 0 when idle | Session delta |
| `charge_miles_added_rated` / `_ideal` | 117–118 | L327–328 | Rated/ideal miles added this session | Session delta |
| `charge_rate_mph` / `charge_rate_mph_float` | 126 / 156 | L339 / L378 | Charging speed | Rate, not range |
| `minutes_to_full_charge` / `minutes_to_charge_limit` | 123 / 142 | L335–336 | Time estimates | Time, not range |
| `max_range_charge_counter` | 109 | L314 | Count of range-mode charges | Counter, not range |
| `powershare_vehicle_energy_left_hr` | 173 | L399 | Powershare runtime hours | Not traction range |

Re-verified 2026-10-05: grepping this proto for `range`, `energy`, `capacity`, `nominal`, `full` adds nothing — there is no full-pack energy, no capacity, and no `range_at_100` field in BLE.

### Cloud (Fleet Telemetry, `tools/signal-matrix/upstream/fleet-telemetry/vehicle_data.proto` + `.../tesla-docs/available-data.tsv`)

| Signal | # | Proto | TSV | Meaning | At 100%? |
|---|---|---|---|---|---|
| `RatedRange` | 32 | L42 | L170 | "officially rated range … given its current SOC" | No |
| `EstBatteryRange` / `IdealBatteryRange` | 40 / 41 | L50–51 | L90 / L117 | Estimated ("takes driving conditions into account") / ideal ("assuming ideal conditions") | No |
| `Soc` / `BatteryLevel` | 8 / 42 | L18 / L52 | L210 / L10 | Usable / displayed SOC; TSV types are `real` (sub-percent resolution unverified) | SOC |
| `EnergyRemaining` | 158 | L168 | L89 | Nominal energy remaining (kWh) | Current energy |
| `NominalFullPackEnergyKwh` | 263 | L293 | absent | True full-pack energy (kWh); proto L288: fields 260–269 first available in firmware 2026.32 | Capacity in kWh, not miles |
| `BmsFullchargecomplete` | 3 | L13 | L12 | "Indicates BMS is fully charged" | Marker, not range |
| `ChargeLimitSoc`, `DCChargingEnergyIn`, `ACChargingEnergyIn` | 38, 34, 36 | L48, L44, L46 | L25, L41, L2 | Limit; battery-measured energy (usable for AC and DC); charger-measured AC | Not full range |

No range-at-100 signal. Fleet Telemetry also needs an owner account plus a self-hosted server; the app cannot read it today.

### Service Mode / in-car

- Controls > Service > **Battery Health** (and the longer Battery Health Test) reports a state-of-health % / capacity-vs-new after a controlled cycle; no API — the result is read off the touchscreen and typed in. Conditions are secondhand (section 5).
- The car's own range display at 100% is the only direct "range at 100%": it is `battery_range` rendered at `battery_level = 100`.

## 2. The online signal: rated range → finer SOC

### 2.1 Inversion

`bestSocPercent (future) ≈ ratedRangeMiles / fullRatedRangeMiles × 100`

- **Resolution.** `battery_range` is a 2-dp float (proto L317). 0.01 mi over a ~300 mi full range is ~0.003% SOC — far finer than the 1% int, if the underlying value is continuous.
- **Basis.** Our matrix reads rated range as "remaining energy over the trim's rated consumption constant" (`docs/reference/fleet-telemetry-vs-ble.md` L89, `tools/signal-matrix/mapping.json` L43), and `battery-health-methods.md` L31 cites third-party [1] that rated miles are linearly proportional to capacity via a fixed Wh/mi constant. Neither is a Tesla statement (section 5).
- **Unknown.** Whether the car computes `battery_range` from the integer SOC (so it steps in ~3 mi quanta) or from a finer/smoothed internal SOC (so it drifts continuously). The parked test in section 3 settles it; the whole approach hinges on that.

### 2.2 Why not estimated (or ideal) range

- `est_battery_range` is projected with recent consumption ("Takes driving conditions into account", TSV L90), so it is not a single-valued function of SOC and cannot be inverted for SOC; parked, it can move with the consumption basis instead of charge.
- `ideal_battery_range` uses an idealized constant (TSV L117), is legacy and often absent; not a SOC or capacity signal.

### 2.3 The scale (`fullRatedRange`) without a 100% charge

- Use the owner's exact EPA/trim rated range as baseline when known.
- Learn it online: each read gives `fullRatedRange ≈ ratedRange / (level / 100)`; fit across many readings/sessions. No active calibration.
- `charge_miles_added_rated / ΔSOC` over sessions is a second estimate of the same scale.
- A natural 100% charge is a free anchor if it happens; it is never required.

### 2.4 Secondary: state of health

- The rated-range and energy-delta SoH methods stay as described in `battery-health-methods.md`; finer SOC also sharpens the energy-delta endpoints. No active 100% procedure.

## 3. Investigation plan (online, no 100% charge)

### What is logged

History CSV (raw car fields only): `vehicleId,timestampMillis,batteryLevel,chargeLimit,chargingState,usableBatteryLevel,ratedRangeMiles,estRangeMiles,idealRangeMiles,chargeEnergyAdded,chargeMilesAddedRated,chargeMilesAddedIdeal`. Identical level+state readings within 60 s are skipped; newest 20k rows kept; older formats drop on load (pre-1.0).

Export: `adb exec-out run-as com.dzid26.teslable cat files/battery-history.csv > battery-history.csv` (debuggable build), or the owner shares the file.

### A. Parked behavior of rated range (the key test)

- While parked and untouched, capture reads over hours/days. Prefer natural app opens; each BLE wake draws energy and can perturb drain, so note wake times.
- Compare `ratedRangeMiles` at constant `batteryLevel` / `usableBatteryLevel`:
  - range changes within a constant integer step → sub-percent information exists; quantify the step (0.01 mi? 0.1? ~3?) plus drift rate (mi/h) and temperature sensitivity;
  - range only moves when the integer steps → no resolution gain; stop and rely on the int SOC.
- Also compare the car's displayed Rated range with `ratedRangeMiles` while parked.

### B. Driving / charging reads

- Consecutive reads across drives and charge sessions: plot ΔratedRange vs Δlevel; check for within-step movement and hysteresis.

### C. Scale fit

- Fit `fullRatedRange` from (ratedRange, level) pairs; residuals expose quantization and BMS noise; compare with the EPA baseline; check whether displayed or usable level fits better.
- Cross-check with `chargeMilesAddedRated / ΔSOC`.

### Success criteria

- Effective SOC resolution from range is materially better than the 1% floor, reproducibly.
- The inverted SOC is consistent across sessions (within a stated tolerance) and does not track consumption (unlike `est`).

## 4. Logging state

The app records raw car fields only (12-column CSV); the car view shows the displayed SOC and rated range, and stats derive from the raw fields at runtime. The logging change that made this possible landed with PR #67 and was reworked to raw-only on `work/raw-history`.

## 5. Assumptions / unverified

Secondhand or inferred; none of these are pinned to a Tesla statement yet:

- **Rated-range definition and linearity** ("remaining energy over the trim's rated consumption constant", proportional to SOC): our matrix wording plus third-party reports (`battery-health-methods.md` [1]); the TSV only says "officially rated range … given its current SOC" (L170). The parked test in section 3 can confirm both.
- **Buffer semantics** (`usable_battery_level` excludes a bottom buffer; rated miles follow usable energy): inferred from the field pair and community sources.
- **Cloud sub-percent SOC** (`Soc` / `BatteryLevel` are `real`): the type allows decimals; actual resolution is unverified.
- **BMS internal SOC resolution** behind the 2-dp range: unknown; this is the central open question.
- **Service-Mode conditions** (<20% SOC, AC ≥5 kW, up to 24 h; rate-limited): secondhand (`battery-health-methods.md` [7][8]).
- **`charge_miles_added_rated` derivation** (energy added / rated constant): inferred.
- **Error percentages** (±2–5% single reading, ±1–2% trended; ±3–5% per session, ±1–3% averaged): from `battery-health-methods.md`, itself secondhand.
- **`est_battery_range` error shape** (largest after highway/cold drives): secondhand.

Verified raw, for contrast: field numbers and decimal annotations (proto lines above), the TSV descriptions, the firmware 2026.32 note (proto L288), and `charge_energy_added` measured at the battery (TSV L41).

## 6. Raw sources

- `core/src/main/proto/vehicle.proto` L306–L399 (charge state fields, decimal annotations at L317 and L325).
- `tools/signal-matrix/upstream/fleet-telemetry/vehicle_data.proto` L13–L293 (signal numbers; firmware note L288).
- `tools/signal-matrix/upstream/tesla-docs/available-data.tsv` L2, L10, L12, L25, L41, L89–L90, L117, L170, L210.
- `docs/reference/fleet-telemetry-vs-ble.md` L89 and `tools/signal-matrix/mapping.json` L43 (our rated-range wording, not Tesla).
- `docs/research/battery-health-methods.md` L20–L31, L43 (method taxonomy, third-party citations).
