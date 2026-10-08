# ADR-0009: Infotainment poll backoff

Status: Accepted
Date: 2026-10-08
Related: ADR-0001 (battery tracker), ADR-0008 (BLE log envelope), issue #112,
`docs/research/tesla-ble-capabilities.md` (Constraints: Polling)

## Context

- While VCSEC said the car was awake and an Infotainment session existed, the
  app sent a charge request and then a drive request every 10 s, whatever the
  car was doing. ADR-0001 only intended that cadence while charging.
- yoziru/esphome-tesla-ble found that Infotainment polling keeps a car awake
  (PR #198) and fixed it with a backoff (PR #213): VCSEC every 10 s always,
  Infotainment every 10 s while active, every 30 s for an idle window of 660 s
  after the last activity (also restarted on connect), then one poll every
  660 s. Only genuine activity resets the idle timer; "user present" is true
  whenever a phone key is near, and staying unlocked is not activity.
- VCSEC status does not affect sleep and keeps the BLE link alive (cars drop
  idle links after about 30 s), so it stays at 10 s.
- This app never wakes the car, and rides along on drives and charges.

## Decision

`InfotainmentPollPolicy` (`core`, pure, clock passed in) decides, on every VCSEC
status, whether the charge+drive pair is due. One instance per vehicle link.

- Asleep: no Infotainment reads.
- Active (charging state Charging or Starting; shift state D, R or N): every
  10 s. Each active reading restarts the idle timer.
- Window: for 660 s after the last activity or trigger, every 30 s. Triggers:
  a (re)connect, locked to unlocked, any closure going to open (doors, trunks,
  charge port), an explicit user request (refresh button, notification wake).
  User presence, staying unlocked, locking and closing are not triggers.
- Idle (window expired): one read every 660 s.
- Wake from sleep (VCSEC asleep to awake): one read right away, so every
  wake-up gets a SOC/charging/gear reading, without restarting the window. A
  car that blips awake by itself must not restart the window, or it is kept
  awake each time it tries to sleep. If the reading shows activity, the active
  cadence takes over.

A read is due slightly early (1 s slack) so that jitter in the 10 s status
ticks does not turn a 10 s cadence into 20 s.

## Consequences

- Readings (SOC, charging, gear) can be up to 11 minutes old while the car is
  awake and idle, and stop while it sleeps (the status log still shows the
  sleep). Charging and driving stay at 10 s.
- The raw log format is unchanged; its records are simply sparser while idle.
- The 660 s and 30 s values are yoziru's, found empirically on their cars. A
  real-car check is part of the change: left idle with the phone in range, the
  car should show asleep in the status log within about 15-25 minutes.
