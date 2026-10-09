# Infotainment polling and vehicle sleep

Evidence behind ADR-0009. Competitive research lives here only (AGENTS.md).
Sources were read on 2026-10-08; quotes are short extracts.

## What other projects found

- **VCSEC status does not affect sleep.** Tesla's Go SDK says a VCSEC-only
  client "can avoid waking infotainment"
  (https://pkg.go.dev/github.com/teslamotors/vehicle-command/pkg/vehicle).
  The esphome-tesla-ble README: "VCSEC status polling is low-power and does not
  affect vehicle sleep" (https://github.com/yoziru/esphome-tesla-ble).
- **Infotainment polling keeps the car awake.** esphome-tesla-ble PR #198:
  "Infotainment polling with WAKE_IF_NEEDED every 30s kept the car awake,
  preventing VCSEC from ever reporting ASLEEP"
  (https://github.com/yoziru/esphome-tesla-ble/pull/198).
- **Their back-off, PR #213** (https://github.com/yoziru/esphome-tesla-ble/pull/213),
  read from the code (`TeslaBLEVehicle::update`):
  - VCSEC every 10 s, always.
  - "Active" is charging or Sentry Mode only. Unlocked and user present were
    removed, because "a car that is merely left unlocked, or reports user
    presence because a phone is in range, can still fall asleep on its own"
    (their issues #201 and #202).
  - Infotainment every 10 s while active, every 30 s while awake and idle for
    the first 660 s after the last activity or a (re)connect, then every 660 s
    without waking the car.
  - "A car that is merely observed asleep, or briefly blips awake on its own,
    must not restart the aggressive polling window or it would be re-woken
    every time it tried to sleep."
- **Idle time before sleep.** esphome-tesla-ble defaults to 660 s ("default 11
  min"); tesla_ble_mqtt recommends at least 660 s
  (https://github.com/iainbullock/tesla_ble_mqtt_docker). TeslaMate's FAQ: the
  car "does not fall asleep before it have been inactive for some 15 minutes"
  (cloud API, not BLE;
  https://github.com/teslamate-org/teslamate/blob/main/website/docs/faq.md).
- **Idle links.** TeslaBleHttpProxy: cars "may terminate connections after ~30
  seconds" (https://github.com/wimaha/TeslaBleHttpProxy#connection-timeouts).
  Our 10 s VCSEC status poll keeps the link busy.

## Where ADR-0009 differs, and why

- Any Infotainment request restarts the car's own sleep countdown (about 10
  minutes; the project owner's premise, 2026-10-08; measured shorter on
  2026-10-09, see "To measure"). A 30 s window for 11
  minutes would keep the car awake about 21 minutes instead of 10, and 660 s
  idle reads could keep it awake indefinitely. So ADR-0009 drops both: 10 s
  while charging or driving, and for a short hold (1 min, or 10 min when the
  car reports someone present) after each status change, then nothing.
- Driving counts as active because the phone rides along; the ESP32 bridge
  stays home and never sees a drive.
- This app never wakes the car, so a wake-up blip only starts the short hold.

## Presence

- The owner's experience (2026-10-09): VCSEC reports a user present when
  someone sits in the car; approaching with a phone key does not set it. This
  differs from the PR #213 comment above, so the real-car check should confirm
  which applies.

## To measure

- Measured 2026-10-09 (`docs/research/real-car-log-2026-10-09.md`): the car
  slept 0.5-2.5 minutes after the last read, and about 2 minutes after a BLE
  wake with a single read. Reads keep it awake while they continue, but each
  adds only a couple of minutes, not the ~10 assumed above, so the hold costs
  less than ADR-0009 estimated.
- First real-car log (2026-10-09, old build, unstable link):
  `real-car-log-2026-10-09.md`. A repeat with a stable link is planned to show
  whether reads hold the car awake.

## Related

- Issue #112 (the report), `docs/research/tesla-ble-capabilities.md`
  (Constraints: Polling).
