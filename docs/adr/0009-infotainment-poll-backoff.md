# ADR-0009: Infotainment poll backoff

Status: Accepted
Date: 2026-10-08
Related: ADR-0001 (battery tracker), ADR-0008 (BLE log envelope), issue #112,
`docs/research/tesla-ble-capabilities.md` (Constraints: Polling)

## Context

- While VCSEC said the car was awake and an Infotainment session existed, the
  app sent a charge request and then a drive request every 10 s, whatever the
  car was doing. ADR-0001 only intended that cadence while charging.
- Other BLE projects found the same thing and back off their Infotainment
  polling: it runs every 10 s only while the car is active, and slows down or
  stops when it is not. Only genuine activity counts; "user present" is true
  whenever a phone key is near, and merely staying unlocked is not activity.
  Evidence and citations: `docs/research/infotainment-polling-and-sleep.md`.
- Any Infotainment request restarts the car's own sleep countdown (about 10
  minutes). A slow idle cadence, such as 30 s reads for 11 minutes, would keep
  the car awake about 21 minutes instead of 10, and periodic idle reads would
  keep it from sleeping at all. This app never wakes the car and rides along on
  drives and charges, so it adopts the activity model but not idle polling.
- VCSEC status does not affect sleep and keeps the BLE link alive (cars drop
  idle links after about 30 s), so it stays at 10 s.

## Decision

`InfotainmentPollPolicy` (`core`, pure, clock passed in) decides, on every VCSEC
status, whether the charge+drive pair is due. One instance per vehicle link.

- Never while asleep, and never to wake the car: reads only when VCSEC says
  awake and an Infotainment session exists.
- Active (charging state Charging or Starting; shift state D, R or N): every
  10 s. When activity ends, the reads stop right away. There is no idle window
  and no periodic idle read.
- Not active: single reads on events, each sent at once. Events: a fresh start,
  VCSEC asleep to awake, locked to unlocked, any closure (doors, trunks, charge
  port) changing between open and not open, and an explicit user request
  (the refresh button; waking the car is not one itself, but its asleep to
  awake step is). Any state other than CLOSED and UNKNOWN counts as open, so a
  door going ajar is an opening.
- A fresh start is the first READY link since the app process or tracking
  started (app start, auto-start after boot or update, tracking switched on) or
  right after pairing / key enrollment. The controller calls
  `onFreshStart(nowMillis)` for it. A reconnect after a dropped link (weak
  signal, car out of range and back) is not an event: no read, no follow-up, and
  the controller calls nothing.
- The transition memory (previous asleep, locked and closure state) survives
  ordinary reconnects and is reset only by a fresh start. A wake, unlock or door
  change that happened while the link was down is therefore an event on the
  first status after the reconnect.
- Each event also schedules one follow-up read 60 s later, to catch a shift
  into D or charging starting just after it. A new event replaces a pending
  follow-up instead of stacking. After that, nothing until the next event. A
  follow-up that falls due while the car is asleep is dropped.
- Not events: user presence, and staying unlocked or locked with no closure
  change.

A read is due slightly early (1 s slack) so that jitter in the 10 s status
ticks does not turn a 10 s cadence into 20 s.

## Consequences

- Readings (SOC, charging, gear) can be old while the car is awake and idle,
  and stop while it sleeps (the status log still shows the sleep). Charging and
  driving stay at 10 s.
- After the last event the car's own countdown runs undisturbed, so it should
  sleep within about 10 minutes of the follow-up (roughly 15-25 minutes of
  being left idle in total). A real-car check is part of the change.
- The raw log format is unchanged; its records are simply sparser while idle.
