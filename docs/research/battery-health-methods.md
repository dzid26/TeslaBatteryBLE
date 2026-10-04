# Battery health methods & data sources

Date: 2026-10-04

> Context: TeslaBatteryBLE is a local-first Android app (BLE, no cloud) that can read charge state (SOC, rated/estimated range, charge limit, energy added, charger power) and track parked drain. Benchmark: Dr.EV by BatterMachine computes SoH, remaining life, cell-imbalance warnings, and charging efficiency from Tesla cloud Fleet Telemetry (pack voltage/current/temperature). This brief asks what can be matched locally, what needs OBD, and what needs opt-in cloud.

## Summary (max 6 bullets)

- Best local-only SoH today is the **rated-range method** (displayed rated range ÷ original EPA rated range for exact trim/year) plus the **energy-delta method** (kWh added ÷ SOC delta) averaged over many sessions; both work with BLE-readable signals, no current integration needed.
- **Coulomb counting is not possible over BLE** (no pack-current signal); it needs CAN (OBD) or Fleet Telemetry `PackCurrent`/`PackVoltage` streams.
- A **phone-connected BLE OBD dongle is integrable** (OBDLink MX+ ~$140, Vgate/vLinker cheaper): it unlocks the tight local match — `Nominal full pack`, `Full pack when new` (3/Y), brick/cell voltages, pack temps, CAC imbalance, DC/AC charge totals.
- **Opt-in cloud** (Fleet API polling + self-hosted Fleet Telemetry streaming) unlocks history, wake-free high-frequency pack signals, and fleet-cohort comparison — at the cost of developer-app onboarding, per-request/signal billing, token custody, and location/privacy exposure. App stays no-cloud by default.
- Copy **TeslaMate** (rated-range + charge-history SoH views), **Scan My Tesla** (nominal-pack + cell-spread UX), and **Recurrent/Geotab-style cohort bands** (peer distribution by model/age) rather than Dr.EV's black-box AI.
- Tesla's own **Service-Mode Battery Health Test** (controlled discharge + recharge, up to 24 h on AC ≥5 kW) is the ground-truth reference and has no API; the app should let users log its result manually.

## Estimation methods (comparison table: method | needs | accuracy | local-only?)

| Method | Needs | Accuracy | Local-only? |
|---|---|---|---|
| (a) Rated-range | Rated range at known SOC (best at/near 100%, rested, moderate temp) + correct original EPA range for exact trim/year; BMS-calibrated pack | ±2–5% single reading; ±1–2% trended over weeks [1][2][3] | **Yes (BLE)** — rated range + SOC are BLE-readable |
| (b) Energy-delta / capacity | `energy_added` + SOC start/end of a charge session, large SOC swing (≥30–50%), no interruptions; repeat sessions | ±3–5% per session; ±1–3% averaged/filtered [4][5] | **Yes (BLE)** — energy added + SOC are BLE-readable |
| (c) Coulomb counting | Continuous pack-current integration (± offset/noise), full or large cycles; OCV rest correction | Exact in lab; drifts without OCV/Kalman correction [6] | **No over BLE** — no current signal; needs CAN or Telemetry |
| (d) Service-mode Health Test | Physical car + AC charger ≥5 kW, SOC <20%, up to 24 h, parked [7][8] | Reference-grade (direct V + ∫I between two OCV points) [9] | Yes (in-car, manual) but **no API** — user runs it, app logs result |
| (e) Fleet Telemetry estimation | Self-hosted telemetry server + `PackVoltage`, `PackCurrent`, `BrickVoltageMin/Max`, `ModuleTempMin/Max`, `Soc`, `EnergyRemaining`, `DC/ACChargingEnergyIn`, `LifetimeEnergyUsed` [10]; many sessions | Research-grade: a few % MAE with good models (e.g. NCM/NCA R² ~0.84 reported by vendor [11]); Dr.EV uses patented + AI hybrid on V/I/T [12] | No — requires cloud account + server |
| (f) Statistical / fleet-cohort | Large fleet history (age, mileage, climate, charge habits); e.g. Recurrent-style range scores, Geotab 22k-EV study (avg ~2.3%/yr, DCFC top factor) [13][14] | Cohort bands, not per-pack truth; good for "normal vs outlier" UX | No (needs fleet data); but **static published curves can ship in-app** as reference bands |

## Method details

### a) Rated-range method

Formula: `SoH ≈ (displayed_range / SOC_fraction) / EPA_range_when_new`. Best at exactly 100% (`displayed / EPA`); otherwise scale, e.g. 216 mi at 80% on a 300-mi-EPA car → 270 mi full → 90% SoH [4]. Tesla's displayed rated miles are linearly proportional to BMS capacity via a fixed Wh/mi constant, so falling full-charge miles track capacity loss [1]. Caveats: BMS estimate noise (±10–20 mi swings with temp, rest state, recent driving, OTA constant changes); always read rested ≥30 min, moderate temps, same SOC point, and trend over weeks, not single readings [2][3][5]. Must match EPA figure to exact model/trim/year — wrong baseline invalidates everything [4][5]. TeslaMate-style dashboards do exactly this (projected/rated range vs baseline over time) [15].

### b) Energy-delta / capacity method

Formula: `Capacity_now = energy_added_kWh / SOC_delta_fraction`; `SoH = Capacity_now / Capacity_when_new`. Example: 26.25 kWh for 20→60% → 65.6 kWh usable; vs 75 kWh new → 87.5% [4]. Works with BLE charge sessions (energy added + SOC start/end already tracked). Accuracy improves with large SOC swings, stable SOC endpoints (rested, no sentry/climate load), and averaging; single sessions carry charging-loss and SOC-reporting error (AC `charge_energy_added` is charger-measured; DC variant is battery-measured [10]). Best practice from community calculators: average the range-method and charge-method estimates and flag gaps >5 pp as mismatch [4].

### c) Coulomb counting (why BLE can't do it)

Integrate current over time (Ah in/out); over 100→0% gives usable capacity directly [6]. Drift from sensor bias/noise and <100% coulombic efficiency forces OCV rest recalibration or Kalman/particle-filter observers (dual-EKF standard in BMS literature) [6]. BLE exposes no current signal — only SOC, range, energy-added, power. Current lives on CAN (`pack current`, motor currents) and in Fleet Telemetry (`PackCurrent` at HV contactors, `DiMotorCurrent*`, `DCChargingPower`) [10]. So local coulomb counting requires the OBD path.

### d) Tesla service-mode Battery Health Test

What it does: controlled discharge (below 10%, possibly to 0%) then full AC recharge, measuring OCV-adjacent voltages at two stable no-load points plus ∫I between them to infer capacity vs rated [9]. Entry: Controls > Service > Battery Health (newer UI surfaces it without the old "service" passcode); conditions: Park, no battery/thermal alerts, no pending update, <20% SOC, AC charger ≥5 kW, up to 24 h, features (Sentry/climate) disabled [7][8]. Output: single SoH % vs when-new; may recalibrate range display [7]. No API exposes it; result must be read off the screen and typed into any app. Run rarely (each run is a full cycle; Tesla rate-limits re-runs; avoid public stations that drop sessions during the zero-draw discharge phase) [8][16]. Quick in-app alternative: Controls > Service > Battery Health panel / Tesla app Request Service > Battery & Charging > Range gives a pass/normal-range evaluation without the full test [8][17].

### e) Fleet Telemetry-based estimation (verified signals)

`teslamotors/fleet-telemetry` is the reference server for the car→server streaming protocol [18]. The `Available Data` table (and `vehicle_data.proto`) confirms battery-relevant fields exist [10]: `PackCurrent`, `PackVoltage`, `BrickVoltageMin/Max` (+ brick numbers), `ModuleTempMin/Max` (+ module IDs), `Soc`/`BatteryLevel`, `EnergyRemaining`, `RatedRange`/`IdealBatteryRange`/`EstBatteryRange`, `ChargeLimitSoc`, `AC/DCChargingEnergyIn`, `AC/DCChargingPower`, `ChargeAmps`, `ChargerVoltage`, `LifetimeEnergyUsed`, `BMSState`, `IsolationResistance`. Dr.EV's approach: patented charge/discharge-window SoH estimators (PCT/KR2024/019086, /019107, /019132) fused with AI anomaly/RUL models over these V/I/T streams, needing several charge sessions before a confirmed verdict ("Learning" → Normal/High Risk), plus cell-imbalance multiples, trend, and lifespan-factor UX [12][19][20]. Note Dr.EV's SoH writeups conflate standard estimators (coulomb counting, OCV correction, EKF/UKF, SVR/RF, LSTM/CNN) with its proprietary filter — useful taxonomy, marketing-level proof [6].

### f) Statistical / fleet-cohort approaches

Recurrent (Range Score, "CarFax for batteries") aggregates daily datapoints (temp, age, charge patterns) across the fleet to predict remaining range-life and flag outliers [13][21]; Geotab's 22k-EV study gives public priors (avg ~2.3%/yr degradation; frequent high-power DCFC leading factor) [14]. For a local-first app: no live cohort without cloud, but published curves can ship as static reference bands ("typical 3-yr-old ≈97%, 5-yr ≈95%" per Recurrent via third-party writeups [22]) against which the user's trended SoH is plotted.

### Charging efficiency & cell imbalance (Dr.EV extras)

- Efficiency: AC `ACChargingEnergyIn` (charger-measured) vs `DCChargingEnergyIn` (battery-measured) plus `ACChargingPower`/`DCChargingPower` give loss ratios; BLE `energy_added` + wall-meter or charger kW can approximate locally.
- Imbalance: needs per-brick min/max — Telemetry has `BrickVoltageMin/Max` [10]; CAN has cell-group voltages/CAC (Scan My Tesla surfaces highest/lowest cell groups + CAC imbalance [23]); BLE has nothing. Local warnings without OBD must stay heuristic (e.g. sudden range drop + slow charge taper), never labeled "cell imbalance".

## Local "loose match" plan (BLE only) and "tight match" plan (BLE + optional OBD)

### Loose match — BLE only (no new hardware, no cloud)

1. Trended rated-range SoH: log rated range + SOC per session/park; compute full-range estimate, compare to user-confirmed EPA baseline (exact trim/year picker); show trend + confidence, not single readings.
2. Energy-delta SoH: per charge session `kWh / ΔSOC`; keep only clean sessions (ΔSOC ≥30%, uninterrupted, rested endpoints); running median.
3. Fused display: average the two estimators when both fresh; show gap flag (>5 pp = "check data") [4]; peer-band overlay from static published curves.
4. Charging-efficiency proxy: energy-added vs SOC-gain over time; parked-drain rate (already tracked) as health-adjacent signal.
5. Manual ground truth: field to log Service-Mode test % + date; recalibrate baseline display.
6. What NOT to claim: cell imbalance, remaining-life years, or "% SoH to 1 decimal" — show ranges and trend arrows.

### Tight match — BLE + optional OBD (local, user-owned dongle)

Hardware: model-specific CAN harness cable + BLE-capable OBD adapter. Scan My Tesla compatibility list: OBDLink MX+ (top pick, iOS+Android), OBDLink LX/MX, vLinker FS / Vgate iCar Pro (budget), JWardell CANserver / S3XY-buttons (Wi-Fi), DIY ESP32 [24][25]. Cost: OBDLink MX+ $139.95 [26]; budget BT adapters substantially cheaper but slower refresh [27]; plus ~$15–40 harness cable per model generation (3/Y pre-Highland vs Highland vs Juniper; S/X by year split) [24]. Coverage: Model 3/Y (all gens with right harness), S/X (2012–2015, 2015–2021, 2021+ Plaid splits) [24]; phone BLE pairing works on Android via Bluetooth Classic dongles [25].

Data unlocked (CAN): `Nominal full pack`, `Full pack when new` (3/Y), buffer, SOC variants (UI min/avg), cell/brick min-max voltages + CAC imbalance, pack/module temps, odometer-gated trip energy, charge totals — the exact signals Scan My Tesla dashboards show [23][28][29]. SoH becomes `nominal_full / full_when_new` (3/Y) instead of inferred [29], imbalance warnings become real (mV spread + CAC Ah), and coulomb-style ∫I checks become possible.

Integration sketch: optional "Connect OBD" screen (BLE GATT to dongle, ELM327/STN protocol, CAN 500 kbps powertrain bus filters); read-only PID set; same estimators as loose match plus direct-capacity SoH and imbalance card; all processing on-device; dongle stays user's property, unplugged when not measuring (also answers warranty-hygiene: passive read-only taps).

## Cloud (opt-in) capabilities

- Fleet API (REST polling): vehicle-data endpoints (charge state, climate, drive, location), commands, wakes; needs developer app registration (domain, keypair, Tesla account OAuth, per-vehicle virtual-key pairing), pay-per-use billing with $10/mo developer discount and hard billing limits (over-limit apps auto-disabled) [30][31]; polling is expensive vs streaming, Tesla pushes migration to Telemetry [31].
- Fleet Telemetry (streaming, self-hosted): car pushes only subscribed fields on change at up to 2 Hz [10]; unlocks wake-free history, high-frequency V/I/T for Dr.EV-style estimators, and alert streams; same onboarding burden plus running a server (reference impl: `teslamotors/fleet-telemetry`) [18]; per-signal billing still applies [31].
- What it buys over BLE+OBD: continuous history without proximity, fleet cohorts, RUL models, efficiency analytics. Privacy cost: tokens that can locate/track the car, server-side location + charge history; keep default OFF, explicit consent, token stored in user-controlled backend only, per Tesla best-practice (don't wake excessively, use minimum_delta, prefer telemetry over polling) [31].

## Precedents worth copying

| App | Metrics/UX | Local-first? | Copy |
|---|---|---|---|
| TeslaMate (self-hosted, OSS) | Grafana: Battery Health, charging stats/curves, drives, efficiency; SoH from rated/projected range vs configured original capacity; community custom dashboards [15] | Yes (own server, owner tokens) | Trend charts + configurable baseline capacity; charge-history tables |
| TeslaLogger (self-hosted, OSS) | Trip/charge logging, degradation views, community firmware/survey data | Yes (own server) | Long-history tables; firmware-annotated range steps |
| Scan My Tesla (+ TM-Spy, TesLAX) | Live CAN: nominal/full-when-new pack, cell spreads, CAC, temps, power; FAQ documents SoH formula + buffer semantics [28][29] | Yes (dongle + phone) | Cell-spread card; nominal-pack SoH; buffer explainer |
| TezLab | Battery Range Performance: extrapolated full-charge range estimate [32] | No (cloud) | Simple "full-range estimate" headline number |
| Teslemetry / Tessie (API wrappers) | Hosted Fleet API/Telemetry access, per-signal data (incl. Pack V/I example payloads) [33] | No (hosted cloud) | Field list for future opt-in mapping |
| Recurrent | Range Score + health reports from fleetStats; buyer-facing verdicts [13][21] | No | Peer-band + Buy/Caution/Pass-style plain-language verdict |
| Geotab (fleet study) | Public degradation priors (~2.3%/yr; DCFC factor) [14] | N/A (research) | Static reference bands + habit advice |
| Dr.EV | SoH ring + peer range, RUL (personalized vs absolute mi/yr), lifespan factors (±months), early warnings (imbalance ×N, trend, streaks) [19][20] | No (cloud AI) | Factor-attribution UX ("High charge limit −12 mo") — computed locally from charge-limit/drain history, no AI claims |

## Cautions

- **Warranty is claim-specific, not voided by a passive dongle alone.** In the US, the Magnuson-Moss Warranty Act bars denial based solely on aftermarket parts/service; Tesla must tie a failure to the modification, and vendors report no warranty-void cases from read-only taps — but physical damage from installation (pinched harness, water ingress) is still on the owner [34][35]. (Factual summary, not legal advice.)
- **Tesla's terms govern API/data use, not the owner's right to read their own CAN bus.** Fleet API access requires accepting Tesla's Fleet API agreement, pay-per-use billing, and rate/wake limits; abuse (excessive wakes/polling) gets apps throttled or disabled, which is why Tesla directs developers to Telemetry with minimum-delta configs [30][31]. (Factual summary, not legal advice.)
- **Safety/ ToS boundaries: read-only, parked-or-passenger use.** OBD taps and Service Mode are diagnostics, not driving interfaces: never interact with a dongle or Service Mode screens while driving, never write CAN frames, and treat the Service-Mode Health Test as infrequent (full 0–100% cycle wears the pack; Tesla rate-limits re-runs) [7][8][16]. (Factual summary, not legal advice.)

## Sources (numbered, URLs, accessed 2026-10-04)

- [1] BatterMachine, "How Tesla Calculates Usable vs Rated SOC and Range" — https://www.battermachine.com/post/how-tesla-calculates-usable-vs-rated-soc-and-range-estimates
- [2] Tesla Motors Club, "The most accurate way to measure battery degradation" — https://teslamotorsclub.com/tmc/threads/the-most-accurate-way-to-measure-battery-degradation.170883/
- [3] Tesla Owners Online, "Battery Degradation - Rated Range calculation" — https://www.teslaownersonline.com/threads/battery-degradation-rated-range-calculation.14417/
- [4] LumenCalculator, "Battery Degradation Calculator" (range + charge formulas, 5 pp agreement rule) — https://lumencalculator.com/battery-degradation-calculator/
- [5] Vehiclers, "How to Check Tesla Battery Health" (method table; 295/326 = 90.5% example) — https://www.vehiclers.com/tesla/how-to-check-tesla-battery-health/
- [6] BatterMachine, "Methods for State of Health Estimation in Tesla Batteries" (coulomb counting, OCV, EKF/UKF, ML taxonomy) — https://www.battermachine.com/post/methods-for-state-of-health-estimation-in-tesla-batteries
- [7] Tesla Owner's Manual (Model 3), Battery Health / Health Test procedure — https://www.tesla.com/ownersmanual/model3/en_us/GUID-B9807218-7291-4F68-9AFF-7C525CF498F3.html
- [8] Not a Tesla App, "Tesla's Battery Health Test" (AC-only, up to 24 h) — https://www.notateslaapp.com/news/2049/teslas-battery-health-test-see-your-battery-health-in-app-or-in-service-mode
- [9] BatterMachine, "Tesla Battery Health Test: Procedure, Principles, and Real-World Results" (OCV two-point + ∫I) — https://www.battermachine.com/post/tesla-battery-health-test-procedure-principles-and-real-world-results
- [10] Tesla Developer, "Fleet Telemetry — Available Data" (PackCurrent/PackVoltage/Brick/ModuleTemp/EnergyRemaining/…) — https://developer.tesla.com/docs/fleet-api/fleet-telemetry/available-data
- [11] BatterMachine AI page (SoH R²/MAE claims; 400k+ sessions) — https://www.battermachine.ai/en
- [12] BatterMachine, "Key Features of Dr.EV" (hybrid patented+AI algorithm; weekly analysis) — https://www.battermachine.com/post/key-features-of-dr-ev
- [13] Recurrent Auto, owner/for-owner pages (fleet-data insights) — https://www.recurrentauto.com/ and https://www.recurrentauto.com/for-owners
- [14] Geotab, "EV Battery Health: Key Findings from 22,700-Vehicle Analysis" (2.3%/yr; DCFC factor) — https://www.geotab.com/blog/ev-battery-health/
- [15] TeslaMate docs/projects + Reddit r/TeslaMate accuracy thread + custom Grafana dashboards — https://docs.teslamate.org/docs/projects ; https://www.reddit.com/r/TeslaMate/comments/1gzabbh/how_accurate_is_the_battery_health_estimation/
- [16] Tesla FAQ BY, "Battery Test — calibration via Service Mode" (home-charger-only; 6-month re-run limit; not a cure) — https://tesla.zverski.eu/en/articles/all-battery-test-service-mode
- [17] Tesla Service Mode User Guide (ODIN PROC_HVBMS_X_STATE-OF-HEALTH-TEST) — https://service.tesla.com/docs/Public/ServiceMode/service_mode_user_guide.pdf
- [18] GitHub teslamotors/fleet-telemetry (reference server) — https://github.com/teslamotors/fleet-telemetry
- [19] BatterMachine, "5 Years Left or High Risk Alert? How to Read Your Dr.EV AI Battery Report" — https://www.battermachine.com/post/dr-ev-ai-battery-report-guide
- [20] Dr.EV Play Store listing (patent numbers PCT/KR2024/019086, /019107, /019132) — https://play.google.com/store/apps/details?id=com.battermachine.drev&hl=en_US
- [21] MotorTrend, "Explaining Recurrent Range Score" — https://www.motortrend.com/features/recurrent-range-score-artificial-intelligence-predicts-used-electric-car-battery-life
- [22] GearUp Insights, "Used EV Battery Health Checklist" (Recurrent 3-yr ≈97%, 5-yr ≈95%) — https://gearupinsights.com/post/used-ev-battery-health-checklist
- [23] Scan My Tesla changelog 2.0 (CAC/cell-voltage dashboard) — https://www.scanmytesla.com/changelog-2-0
- [24] Scan My Tesla, "Adapters" (harness splits; compatible dongles) — https://www.scanmytesla.com/adapters
- [25] Scan My Tesla FAQ + VoltChek 2026 scanner roundup (BT Classic on Android; Vgate budget vs OBDLink speed) — https://www.scanmytesla.com/faq ; https://voltchek.app/blog/best-obd2-scanner-tesla
- [26] OBDLink MX+ product page ($139.95) — https://www.obdlink.com/products/obdlink-mxp/
- [27] VoltChek, "Best OBD2 Scanner for Tesla: What Actually Works (2026)" — https://voltchek.app/blog/best-obd2-scanner-tesla
- [28] Scan My Tesla signal-details sheet (in-app vs calculated signals) — https://docs.google.com/spreadsheets/d/1UBHw2eY3QyJL3vUz0CnTZ7iLlLB-ao5s61hexT0GuHM/edit?usp=sharing
- [29] Scan My Tesla FAQ ("Full pack when new" SoH note; degradation calc removed as incorrect) — https://www.scanmytesla.com/faq
- [30] Tesla Developer docs (Fleet API overview, auth, onboarding) — https://developer.tesla.com/ ; https://developer.tesla.com/docs/fleet-api/getting-started/what-is-fleet-api
- [31] Tesla Developer, "Billing and Limits" ($10 discount; auto-disable; telemetry 94–97% cheaper than polling) — https://developer.tesla.com/docs/fleet-api/billing-and-limits
- [32] TezLab Support, "Battery Range Performance & Battery Health" — https://support.tezlabapp.com/article/45-battery-range-performance
- [33] Tessie developer reference, "Access Tesla Fleet Telemetry" (example PackVoltage/PackCurrent payload) — https://developer.tessie.com/reference/access-tesla-fleet-telemetry
- [34] Magnuson-Moss discussion + T Sportline warranty guide (aftermarket parts don't void per se) — https://tsportline.com/blogs/tesla-aftermarket-support/will-installing-aftermarket-parts-void-my-tesla-warranty
- [35] Enhance Auto (S3XY Buttons) FAQ (no warranty-void reports from thousands of users) — https://www.enhauto.com/pages/faq

## Confidence & gaps

- High confidence: rated-range + energy-delta formulas; Service-Mode procedure/conditions; Telemetry field list (verified against official docs table); OBD hardware compat + MX+ price; Fleet API billing/onboarding shape.
- Medium confidence: exact OBD coverage per newest Highland/Juniper harness (vendor pages change; verify before recommending SKUs); budget-dongle reliability (community-reported, varies by firmware); Recurrent/Geotab numbers as cited by third parties (directional priors, not pack truth); Dr.EV accuracy claims (vendor-published R²/session counts, no independent audit).
- Gaps (need car-in-hand or owner input, not web): per-trim EPA/original-kWh baseline table for the app; BLE signal inventory vs estimator minimums (which charge-state fields the BLE GATT layer actually yields today); CAN DBC/PID mapping for the dongle path (Scan My Tesla sheet is reference, not a license); UX copy for confidence intervals; decision on static peer-band dataset to bundle.
