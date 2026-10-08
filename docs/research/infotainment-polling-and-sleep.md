# Infotainment polling and vehicle sleep

Evidence behind ADR-0009. Competitive research lives here only (AGENTS.md).

## Observed by other BLE projects

- yoziru/esphome-tesla-ble, PR #198 (https://github.com/yoziru/esphome-tesla-ble/pull/198):
  found that polling the Infotainment domain keeps the car awake. The owner of
  that project confirmed it and approved a back-off model.
- yoziru/esphome-tesla-ble, PR #213 (https://github.com/yoziru/esphome-tesla-ble/pull/213):
  the back-off model, as the owner described it to us:
  - VCSEC status every 10 s, always: it does not affect sleep, and it keeps the
    BLE link alive (cars drop idle links after about 30 s).
  - Infotainment every 10 s while the car is "active".
  - Every 30 s while awake but not active, for an idle window of 660 s after the
    last activity; the window also restarts on (re)connect.
  - After the window, one Infotainment poll every 660 s, never waking the car.
  - Only genuine activity resets the idle timer. "User present" is true whenever
    a phone key is near, and merely staying unlocked is not activity.
  - A car that blips awake on its own must not restart the window, or it is kept
    awake each time it tries to sleep.
- yoziru/esphome-tesla-ble issues #201 and #202
  (https://github.com/yoziru/esphome-tesla-ble/issues/201,
  https://github.com/yoziru/esphome-tesla-ble/issues/202): related reports.
  Not re-read for ADR-0009; check them before citing specifics.
- tesla_ble_mqtt and TeslaMate's documentation on vehicle sleep also describe
  Infotainment or API polling as a reason a car stays awake. Not re-read for
  ADR-0009; the maintainer should add links when verifying.

## Our own premise

- Any Infotainment request restarts the car's own sleep countdown (about 10
  minutes), per the project owner (2026-10-08). So the window in PR #213 (30 s
  reads for 11 minutes) keeps a car awake about 21 minutes instead of 10, and
  its 660 s idle reads keep it awake indefinitely when the idle gap is shorter
  than the countdown. This app never wakes the car and rides along on drives and
  charges, so ADR-0009 keeps only the activity model: 10 s while charging or
  driving, otherwise single event reads.
- Not yet measured on a real car. The real-car check in the ADR-0009 PR is the
  first measurement; record the result here.

## Related

- Issue #112 (the report), `docs/research/tesla-ble-capabilities.md`
  (Constraints: Polling).
