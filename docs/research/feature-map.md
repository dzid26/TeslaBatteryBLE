# Battery health feature map

Date: 2026-10-04
Synthesis of the three research notes in this folder (2026-10-04).

Purpose: what of the benchmark battery-health app's feature set can be implemented
locally (BLE-only), what needs an optional OBD dongle (tight match), and what we
deliberately skip (cloud/fleet features). The benchmark product is referred to
generically from here on; naming stays in the research notes.

## Verdict

- **Loose match (BLE-only, no new hardware): ~70% of the useful product.** Trended
  battery health, charge/park history, efficiency, taper/balancing detection, drain
  alerts, habit insights, local reports — all on-device, free, no account.
- **Tight match (optional ~$150 OBD dongle): the pack-level signals that normally
  require cloud telemetry** — nominal pack capacity, cell/brick imbalance, pack
  temperatures, coulomb checks. Purely local and user-owned.
- **Cloud-only by design (skip):** live fleet peer comparison (ship static published
  bands instead), weekly DNN correction, ±month lifespan attribution, recurring-warning
  AI (heuristics instead), leaderboards, resale certificates.
- **We win on:** no account/virtual-key/telemetry onboarding, instant value (the
  benchmark needs ~20 charge sessions before a verdict), privacy, no subscription.
  We lose on: fleet data and pack-level signals without OBD.

## Feature matrix

Tier: **L** = BLE-only loose · **T** = BLE + optional OBD tight · **C** = cloud-only (skip).

| # | Benchmark capability | Our local equivalent | Data source | Tier | Phase |
|---|---|---|---|---|---|
| 1 | SoC / range dashboard | Already live; add history | `ChargeState.battery_level`, `battery_range`, `est_battery_range` | L | done / P2 |
| 2 | SoH % (hybrid AI) | Fused rated-range + energy-delta estimate, trended, with confidence | `battery_range` at known SOC + `charge_energy_added` ÷ ΔSOC | L | P2 |
| 2b | True capacity / SoH | `nominal full pack` / `full pack when new` | OBD CAN (3/Y direct) | T | P3 |
| 3 | Peer health range (e.g. 77–89%) | Static published degradation bands by age/mileage | shipped reference curves (published fleet-study priors) | L | P2 |
| 4 | Remaining life (km + years) | Conservative projection of future range from trend; shown as range, never ±months | local trend model | L | P3 |
| 5 | Lifespan factors (−12 mo) | Qualitative habit cards: high-charge-limit share, AC/DC mix, deep discharges, cold/hot charging | `charge_limit_soc` history, `fast_charger_*`, SOC log, `charge_limit_reason` | L (qualitative) | P2 |
| 6 | Cell imbalance "N×" | Real spread + CAC | OBD brick min/max voltages + CAC | T | P3 |
| 7 | Worsening / recurring warnings | Rolling-window heuristics: N consecutive declines, outliers | session history | L | P2/P3 |
| 8 | Learning state + confidence | Session-count gate + confidence levels (same UX idea, local) | session counter | L | P2 |
| 9 | Live charge V/I/T | Charger-side V/A/kW + power taper curve; pack V/I/T only with OBD | `charger_voltage/actual_current/power`, `charge_rate_mph_float` | L partial / T full | P2 |
| 10 | Charging efficiency | kWh added ÷ ΔSOC, AC-vs-DC loss proxy, per-session efficiency trend | `charge_energy_added`, SOC endpoints, `fast_charger_present` | L | P2 |
| 11 | Charge modes (5 presets) | Charge-limit presets + schedules + amps over BLE | `ChangeChargeLimit`, `ScheduleCharging`, `SetChargingAmps` (CHARGING_MANAGER) | L | P2 |
| 12 | Trickle / balancing sawtooth | Detect end-of-charge taper + sawtooth in `charger_power` at high SOC; "rest at 100%" tip | charge-session samples | L | P2 |
| 13 | Driving analytics (power/torque/speed, aggression) | Coarse power + speed + regen ratio → gentle aggression heuristics; no torque | `DriveState.power`, `speed_float`, odometer | L partial | P3 |
| 14 | Parking standby/parasitic drain | **Vampire-drain tracking** (already core), plus baseline comparison and alerts; thermal only via proxies | VCSEC sleep poll + SOC deltas; `battery_heater` | L | P1/P2 |
| 15 | History timeline | Local session DB (charge + park + drive) | local | L | P2 |
| 16 | Weekly/monthly reports | Locally generated summaries + insights | local | L | P3 |
| 17 | Leaderboards | Skip (needs backend); optional static percentile bands | — | C | skip |
| 18 | Resale certificate PNG | Local export report from own data; no "certified" claim, no pricing model | local | L-lite | P3 optional |
| 19 | Smart care notifications | Local rule-based: charge limit advice, balance prompt, drain alert, efficiency drop | local + BLE | L | P2 |
| 20 | Ground truth (bonus) | Log Tesla Service-Mode Battery Health Test result manually as calibration baseline | user input | L | P2 |

## Guardrails — what we never claim without OBD

- No "cell imbalance" labels (spread is not observable over BLE).
- No pack/module temperature claims (only charger/cabin proxies exist).
- No SoH to 0.1% precision; show ranges + trend + confidence (the benchmark presents ranges too).
- No lifespan factors quantified in months; qualitative habit cards only.
- Peer comparison only against static published curves, never a live fleet cohort.
- Resale export is a data report, not a "certificate" or valuation.

## Strategic notes from the research

- The benchmark's cold start (~20 sessions; a 24 h trial shows almost nothing) and
  subscription pricing are exploitable differentiators: we can show value on day one
  from SOC/range history even before SoH confidence is high.
- Its accuracy is not independently verified (a widely cited third-party-vs-Tesla
  comparison story was about a different product). Its own materials admit a
  systematic bias vs Tesla's discharge test. Our approach should stay transparent:
  method + confidence + ground-truth logging.
- BLE is poll-only (no subscriptions) and Infotainment data requires an awake car;
  VCSEC survives sleep. Vampire-drain tracking (VCSEC + SOC deltas) is a genuinely
  local-first feature the benchmark cannot do without waking the car or cloud polling.
- OBD route is model/year dependent (harness variants; S/X splits) — treat as an
  optional power-user tier in Phase 3, not a core requirement.

## Proposed roadmap delta (mirrored in master plan)

- **Phase 2 — Battery health v1 (loose, BLE-only)**: fused rated-range/energy-delta SoH
  with confidence; static peer bands; session-count learning gate; service-test logging;
  habit cards; efficiency + taper detection.
- **Phase 3 — Battery health v2 (tight, optional OBD)**: ELM327/STN dongle support,
  harness guidance, read-only PIDs (nominal/full pack kWh, brick min/max + CAC, pack
  temps), real imbalance card, coulomb checks.

## Open questions

- OBD first target: OBDLink MX+ (STN) vs cheap ELM327 clones; which model harnesses to document first.
- Source + licensing for static degradation reference curves we ship.
- Whether an opt-in Fleet Telemetry bridge ever makes sense (default: no, contradicts no-cloud).
