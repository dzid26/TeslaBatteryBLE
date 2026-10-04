# Dr.EV feature inventory
Date: 2026-10-04

Vendor: BatterMachine — BATTER MACHINE Co., Ltd (Batter Machine Inc.), South Korea [1][2][3].
App: "Dr.EV – For your Tesla" (also listed "Dr.EV-For your Tesla", CN title "电车博士 (Dr.EV)") [1][2][4].

## Summary (max 6 bullets)

- Dr.EV is a **cloud-connected Tesla-only** battery-management app: Tesla-account OAuth + virtual key + Tesla Fleet Telemetry streaming; real-time SoC/range display plus server-side AI reports — not a local/BLE app [5][6][7].
- Core loop is **charge-session analytics**: per-charge voltage/current/temperature → patented filter (continuous) + weekly DNN correction → SoH %, health-vs-peers range, degradation trend, remaining life (time/cycles/mileage), lifespan factors in months, early warnings [8][9].
- It adds **charging/driving/parking monitors** (C-rate, efficiency, torque/power/speed, standby drain, trickle/cell-balancing analysis), history timelines, weekly/monthly reports, leaderboards, and a subscriber-only **PNG resale/insurance certificate** (health, capacity, market price, CB-R, warranty history) [8][10].
- Identity/pricing: iOS id6479555058, Android `com.battermachine.drev` (+ CN `com.battermachine.drev.cn`); App Store 4.8★ (29 ratings); Pro Monthly $8.99 / Lite Monthly $5.99 / Pro Weekly $4.49 / Pro Annual $89.99 / Pro Lifetime $269.99 / Lite Lifetime $179.99; free 1-day (24 h) trial [1][2][4][11].
- Marketing claims "$4,200/yr untracked degradation" cost and "+$3,100 resale impact" on drev.ai hero; certificate example shows $5,200 battery market price — treat as vendor claims, not independent findings [12].
- Local-BLE verdict: **displays, thresholds, trip/charge logs, and simple SoH trending are replicable on-device**; **peer comparison, weekly DNN SoH correction, lifespan-months attribution, recurring-warning AI, leaderboards, and certificates are inherently cloud/fleet-data features** and cannot work offline [8][9][10].

## Feature inventory (table: feature | what it shows | raw signals needed | cloud/compute | local feasibility guess)

| Feature | What it shows | Raw signals needed | Cloud/compute | Local feasibility guess |
|---|---|---|---|---|
| Onboarding: Tesla OAuth + virtual key + Fleet Telemetry | 4-step setup: install → link Tesla account (OAuth) → register Dr.EV virtual key → streaming analysis starts; support docs for scopes, key pairing, telemetry status, firmware ≥2024.26 (Atom S/X ≥2025.20) [5][6] | Tesla account, vehicle VIN, OAuth scopes, key-pair registration, telemetry stream grant | Cloud (Tesla Fleet API + vendor backend); per-access data cost cited by dev [11] | Not replicable via BLE by design; local app skips accounts/telemetry entirely |
| SoC / expected range / health % dashboard | Real-time battery analysis cards: SoC, expected range, overall health % [1][2] | SoC, rated/expected range, odometer, charge state | Client display of cloud-polled values | **High** — BLE can read/derive SoC + range display |
| Battery Health Insights (hybrid patented + AI) | SoH % ring (Normal/Learning/High-Risk), peer "health range" (e.g. 77–89%), remaining-mileage estimate, personalized trend charts [8][9] | Voltage, current, temperature (continuous); SoC swing, energy added, mileage, model/year peer cohort | Patented filter on-device/edge continuous + **weekly server DNN** verification/correction [8] | Partial: local filter + trend chart feasible; peer band + weekly DNN correction needs cloud |
| AI Battery Report: remaining life distance + lifespan | Cumulative km/mi left before end-of-life; time left at current habits (e.g. "5 yr 8 mo"); Period/Cycles/Mileage slider tabs [9] | SoH trajectory, mileage, charge-cycle counting, usage pattern | Server AI projection | **Medium**: simple extrapolation local; vendor's 3-basis projection + calibration needs history + model |
| Lifespan Factors (±months) | Named habits quantified, e.g. "High Charge Limit −12 mo", "Favorable Temperature +9 mo", "Mostly AC Charging +2 mo", "Cell Imbalance −16 mo" [9] | Charge-limit history, ambient/battery temp exposure, AC/DC mix, imbalance metric | Server attribution model | **Low**: heuristics could guess; month-quantified attribution is vendor model |
| Early warnings | "Cell Imbalance N× vs average", "Worsening Trend", "Recurring Warning (N consecutive charges)"; green "No issues detected" when clear [9] | Cell-voltage spread vs fleet mean, session-to-session trend, repeat counter | Server anomaly detection, weekly safety checks [8] | **Medium**: local imbalance ratio + repeat counter feasible; fleet-mean "N×" needs cloud baseline |
| Confidence + Learning state | 4 levels (Very Low/Low/Medium/High); "Learning" badge + "N/20 sessions" progress bar before confirmed Normal/High-Risk verdict [9] | Session count, variance across charges | Server policy (quota ≈20 sessions in examples) | **High**: session-counter gate is trivially local |
| Intelligent Charging Monitor | Live voltage/current/temperature/efficiency graphs; overcurrent/overheat/efficiency-drop alerts; modes: Short Trip, Standard, Max Range, Cell Balancing, Max Charging Speed [8] | Charge power, V/I/T, efficiency (energy added vs SoC delta), charge-limit setting | Real-time stream display + rule alerts (client), mode presets | **High** for monitor/alerts via BLE; mode presets are just charge-limit policies |
| Trickle / cell-balancing analysis | Deep dive on end-of-charge taper; sawtooth max-cell-voltage = balancing signature; guidance to charge >80% (~100%) + rest overnight [8][13] | Charge power taper curve, max-cell-voltage trace, rest time at high SoC | Pattern detection (could be local rules) + educational content | **High**: taper + sawtooth detection is local signal processing |
| Driving analytics | Motor power, torque, vehicle speed, energy use, V/I/T, C-rate; flags aggressive acceleration / excessive power [8] | Speed, pedal/power, torque, discharge C-rate, temp | Stream + rule/ML flagging | **High** for raw display + simple aggression thresholds via BLE/CAN-equivalent |
| Parking analytics | Standby consumption, parasitic drain, temperature fluctuation; abnormal-drain/thermal alerts [8] | Quiescent current/power, SoC drop while parked, temp | Background monitoring + baseline comparison | **Medium**: needs always-nearby BLE or periodic wake; phone-background limits apply |
| History timeline | Every charge/trip: start/end, energy, behavior, battery-use patterns; daily/weekly/monthly/custom views [8] | Session logs (time, energy, SoC delta, distance) | Cloud archive + charts | **High**: local DB suffices at small scale |
| Weekly/monthly reports | Auto-generated health + driving/charging efficiency + behavior-impact tips [1][8] | Aggregated sessions | Server report job | **High**: local aggregation + templates can mimic; vendor wording/peer context cannot |
| Global leaderboards | Rankings vs worldwide Tesla community: battery health, charging efficiency, energy use [1][8] | Normalized SoH, efficiency, consumption + identity/cohort | Cloud fleet DB (core value, inherently social) | **None** without cloud |
| Battery certificate (subscribers) | Instant PNG: model/year/VIN/mileage, health %, capacity kWh, market price, battery score + AI health score, CB-R (Basic 50–85% / slow AC / fast DC), full valid/expired warranty history; for sale/insurance/lease/warranty-extension [10] | All above + warranty catalog | Server generation on subscribe (monthly/annual/lifetime) | **None** as credential; local app can show same numbers but cannot issue trusted cert |
| Resale-value estimate | Hero promises "battery health and residual value in clear numbers"; cert shows market price (ex. $5,200); homepage claims +$3,100 resale impact [5][12][10] | SoH, capacity, mileage, market comps | Server pricing model | **Low**: needs market data |
| Smart guide / notifications | Plain-language care tips: optimal limits, balance-charge prompts, driving feedback; proactive anomaly pushes [8] | Alert triggers above | Rules + push infra | **Medium**: local notifications feasible; push when away needs cloud |
| Fleet management label | Store categories/keywords include "EV Battery & Fleet Management"; vendor company BatterMachine [2][3] | Multi-vehicle accounts (Pro Monthly 2-vehicles tier exists) [11] | Cloud multi-vehicle | **Low** for BLE (one-car-at-a-time) |

## Detailed findings (short sections per feature group)

### Onboarding & data connection
4 steps on drev.ai/product: download → "Link Tesla Account … with secure OAuth" → "Register Virtual Key" → "real-time analysis begins" [5]. Support hub has dedicated Getting Started (scopes, first sync), Fleet Telemetry (virtual key, streaming, firmware, config), Vehicle Commands (offline/timeout/permissions) categories [6][7]. Coverage: Model 3/Y/S/X + Cybertruck, worldwide, KO/EN/CN +15 more; firmware ≥2024.26, Atom-based old S/X ≥2025.20 [5]. No BLE, no local-only mode is advertised — it "reads your Tesla's data, straight from Tesla fleet telemetry" [12]. Developer reply to a critical review confirms they "utilize official Tesla data, which incurs a cost per access" — hence no full free tier [11].

### Battery health (SoH, peers, trend, Learning, confidence)
Hybrid method: "patented algorithm continuously processes real-time voltage, current, and temperature" + "weekly AI-driven (DNN) analyses to predict and verify" with correction and trend charts [8]. Report guide shows SoH ring with Normal (green) / Learning (gray) / High Risk (red); peer "Health Range" (e.g. 84% inside 77–89% = in line with fleet); "Learning" until session quota met (example 16/20); confidence Very Low→High with explicit "do not act on single number" guidance at Very Low [9]. Tesla's own built-in health test is a contrasting ground truth: full discharge+recharge with OCV-vs-charge integration, walls of preconditions (Park, <20%, online, no updates/warnings, ≥5 kW stable AC) [14].

### AI Battery Report (remaining life, factors, warnings)
Summary card = Remaining Life Distance (cumulative, not per-charge range) + Remaining Lifespan ("5 yr 8 mo" at current habits) + Early Warnings; detail adds Period/Cycles/Mileage tabs (often disagree: low-mileage old car can have cycle headroom but short calendar life) [9]. Lifespan Factors section quantifies habits in months (red adverse / green favorable) [9]. Warnings: Cell Imbalance "N× vs average vehicle" (ex. 5.8×, 6.3×), Worsening Trend, Recurring Warning (ex. 5 consecutive sessions); badge is composite (capacity + trend + warnings), so 84% SoH can still be High Risk and 78% can be Normal with a −12 mo charge-limit lever [9].

### Charging
Live V/I/T + efficiency + graphs; anomaly alerts (overcurrent, overheating, efficiency drop); mode presets Short Trip / Standard / Max Range / Cell Balancing / Max Charging Speed (store text shortens to e.g. Short Trip, Max Range) [1][8]. Statistics view adds power, C-rate, temperature rise, plus dedicated trickle/balancing analysis [8]. Companion research posts show balancing sawtooth on max-cell-voltage and pre-heat behavior before fast charging — the kind of traces the monitor visualizes [13].

### Driving
Tracks motor power, torque, speed; flags aggressive acceleration/excessive power; analyses "critical trickle-charging phases" on the charge side; driving stats also log consumption, V/I/T, C-rate over flexible windows [8].

### Parking
Standby draw, parasitic drain, temperature swings; abnormal-drain/thermal detection; same daily/weekly/monthly/custom slicing as driving/charging stats [8]. Real failure cases (pack failure while parked; max-cell-voltage gap ~40% SoC before shutdown with physical dent cause) illustrate why parked monitoring matters [15].

### History / reports / charts
Full session archive (times, energy, behavior, battery-use patterns) [1][8]; auto weekly/monthly reports with behavior-linked tips [1][8]; trend charts "influenced by real driving and charging behaviors" [8].

### Gamification
Leaderboards rank battery health, charging efficiency, energy consumption vs global Dr.EV Tesla community [1][8]. No "Battery Management Score" numeric scale is published in the sources reviewed; certificate instead shows "Battery score / AI health score" and CB-R without public formula [10] — record as gap.

### Resale value estimate
drev.ai positions residual value beside health ("holding its value", "+$3,100 resale impact", "~$4,200/yr untracked degradation" hero claims) [12]; certificate is the monetized artifact: PNG with health, capacity, market price, scores, CB-R under three conditions, warranty history, for negotiations/insurance/lease-return/warranty-extension [10].

## Data sources & requirements
- Cloud path only: Tesla Fleet Telemetry streams (V/I/T, SoC, range, mileage, charge state, location-derived climate, charge-limit/AC-DC mix) → vendor backend → app display + weekly AI [5][8][12].
- Compute split: continuous patented filter (lightweight, could be edge) vs weekly DNN verification, fleet-peer distributions, lifespan-months attribution, recurring-warning logic, leaderboard, certificate/pricing (server) [8][9][10].
- Quota to value: ~20 charge sessions before confirmed verdict; trial users with no drive/charge history see little (see criticisms) [9][11].
- No evidence of BLE, OBD, or on-car direct access; no offline mode documented [5][6].

## Pricing & platforms
- Platforms: iOS 15+ (iPhone/iPad), watchOS 10+, Android; 18 languages (EN +17); 5K+ Android downloads; Lifestyle category; 294 MB iOS; seller BATTER MACHINE Co., Ltd; unrelated-to-Tesla disclaimer [2][11].
- Packages: `com.battermachine.drev` global + `com.battermachine.drev.cn` China variant [4].
- IAP (App Store US): Pro Monthly (1 vehicle) $8.99; Lite Monthly (1 vehicle) $5.99; Pro Weekly (1 vehicle) $4.49; Pro Annual (1 vehicle) $89.99; Pro Monthly (2 vehicles) $17.99; Pro Lifetime (1 vehicle) $269.99; Lite Lifetime $179.99 [11].
- Trial: "free one-day trial" / 24-hour full-feature trial [8][11]; certificates "Subscribers Only … available the moment you subscribe" (monthly/annual/lifetime) [10].
- Support/billing: store-governed cancellation/refunds; Pro activation keyed to vehicle/account targeting; trial/referral reuse limits; revoke = Tesla-access removal + virtual-key deletion [7].

## Limitations & user criticisms
- Paid wall surprise + short trial: ES reviewer "es una app de pago y no te lo dice … solo 24 horas de prueba y no te dan ninguna información durante ese tiempo"; dev reply: 24 h full features, but results need accumulated drive/charge data; paid because Tesla official-data access costs per call [11].
- Cold-start problem: Learning state until ~20 sessions means day-one buyers, demo-car histories, or low-use cars get "no verdict" — corroborated by guide's Case 1 (16/20, imbalance flag but no decision) [9].
- Weekly (not continuous) AI cadence: safety/anomaly DNN runs weekly — balanced for cost, but not real-time protection [8].
- Cloud + account friction: OAuth scopes, virtual-key pairing, telemetry config, firmware floors, command timeouts each have troubleshooting docs — a long tail of failure modes a BLE app avoids [5][6].
- Third-party estimation limits: BatterMachine's own Tesla-test comparison admits their "alternative (positive algorithm) tends to report slightly higher" SoH vs Tesla's discharge test and that true initial pack capacity is uncertain (two reference capacities) [14]. InsideEVs' recent third-party-test story (Recurrent estimate 89–92% vs Tesla 88% on a 2022 M3P ex-demo car) is a useful parallel on estimator-vs-Tesla gaps, but it is about Recurrent, not Dr.EV — do not cite it as Dr.EV validation [16].
- Thin public review base: 4.8★ but only 29 App Store ratings; 5K+ Play downloads; Task-noted appbrain/apkcombo criticism (no live monitoring/widgets) could not be independently verified from fetched pages — recorded as unverified.
- Tesla-only, no other makes; "not affiliated/endorsed/sponsored by Tesla" [1].

## Sources (numbered, URLs, accessed 2026-10-04)
[1] Google Play — Dr.EV (com.battermachine.drev), features + disclaimer — https://play.google.com/store/apps/details?id=com.battermachine.drev&hl=en_US
[2] Apple App Store — Dr.EV-For your Tesla (id6479555058) — https://apps.apple.com/us/app/dr-ev-for-your-tesla/id6479555058
[3] Apple App Store seller + privacy (BATTER MACHINE Co., Ltd) — same as [2]
[4] Google Play — 电车博士 Dr.EV CN (com.battermachine.drev.cn) — https://play.google.com/store/apps/details?id=com.battermachine.drev.cn&hl=en_IN
[5] drev.ai/product — value, models, firmware, 4-step OAuth/key setup — https://www.drev.ai/product
[6] drev.ai/support hub — Fleet Telemetry / scopes / key docs — https://www.drev.ai/support?category=account-billing-security (hub + category filters)
[7] drev.ai/support articles — subscription/refund, revoke+key removal, activation limits, trial/referral limits (linked from [6])
[8] BatterMachine blog — Key Features of Dr.EV (9 groups, hybrid algo, modes, stats, leaderboards, 1-day trial) — https://www.battermachine.com/post/key-features-of-dr-ev
[9] BatterMachine blog — AI Battery Report guide (SoH ring, peer range, Learning 16/20, ±months factors, N× imbalance, confidence, 3 cases) — https://www.battermachine.com/post/dr-ev-ai-battery-report-guide
[10] drev.ai/certification — PNG certificate contents, CB-R, warranty history, subscriber-only — https://www.drev.ai/certification
[11] App Store text listing — IAP prices, 29-rating 4.8★, ES paid-app review + dev data-cost reply, trial terms — https://apps.apple.com/us/app/dr-ev-for-your-tesla/id6479555058 (text fetch 2026-10-04)
[12] drev.ai homepage hero — "straight from Tesla fleet telemetry", $4,200/yr + $3,100 resale claims, 1-day trial, 4.8★ — https://drev.ai/
[13] BatterMachine/drev.ai resources — balancing sawtooth + pre-heat charging analyses — https://www.drev.ai/resources (posts: "How to Know If Your Tesla Is Balancing Its Battery", "Why Tesla Warms the Battery Before Fast Charging")
[14] BatterMachine blog — Tesla Battery Health Test procedure/principles vs Dr.EV comparison — https://www.battermachine.com/post/tesla-battery-health-test-procedure-principles-and-real-world-results
[15] drev.ai/resources — parked-failure + driving pack-failure cases — https://www.drev.ai/resources (posts: "A Case of Tesla Battery Failure During Parking", "A Tesla Battery Pack Failure During a Drive")
[16] InsideEVs — third-party (Recurrent) estimate vs Tesla built-in test on ex-demo 2022 M3P — context only, NOT Dr.EV — https://insideevs.com/news/800168/tesla-demo-car-battery-test/
[17] r/DrEVdev — Key Features mirror thread — https://www.reddit.com/r/DrEVdev/comments/1lgb7fa/key_features_of_drev/

## Confidence & gaps
- High confidence: identity/IDs, onboarding path, 9 feature groups, report anatomy (±months, N×, Learning/confidence), charging modes, leaderboards, certificate contents, IAP prices, trial length — all from first-party pages/listings quoted above.
- Medium: "real-time" latency and exact Fleet Telemetry signal list (vendor says V/I/T + SoC/range/mileage family; full field list not published); weekly DNN cadence confirmed but model details proprietary (only journal-link pointer: sciencedirect S2666546823000915 cited on listings [1][2]).
- Gaps / unverified: "Battery Management Score" numeric definition; CB-R formula; resale-pricing model; Lite-vs-Pro tier matrix beyond prices; appbrain/apkcombo "no live monitoring/widgets" criticism (per task brief, not independently confirmed); YouTube review content (descriptions only); China-variant parity.
