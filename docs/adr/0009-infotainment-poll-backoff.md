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
  stops when it is not. Only genuine activity counts; merely staying unlocked,
  or "user present" on its own, is not activity.
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
status, whether the read (charge, drive, closures and climate, in sequence) is
due. One instance per vehicle link.

- Never while asleep (asleep also drops the hold below), and never to wake the
  car: reads only when VCSEC says awake and an Infotainment session exists.
- A read every 10 s while either of these holds:
  - Active: charging state Charging or Starting, shift state D, R or N, Sentry
    mode on (any sentry state but Off), or the climate on (`is_climate_on`, or a
    climate keeper mode of On, Dog or Party). In Sentry and with the climate on
    the car stays awake and draws hundreds of watts, so a read every 10 s costs
    nothing that matters and keeps the readings fresh (owner, 2026-10-09).
    When activity ends, these reads stop right away: the policy takes each
    flag from the latest closures and climate reading
    (`onClosuresReading`, `onClimateReading`), and an asleep status or a fresh
    start clears them. This is the one place to extend (`isActive()`).
  - Within the hold after the last status change: 10 minutes if the latest
    status shows user presence, otherwise 1 minute. Presence is re-evaluated on
    every status, so when the person leaves, the remaining hold drops to the
    short one.
- Idle safety read: while VCSEC says awake and the car is neither active nor
  in a hold, one read once 20 minutes (`IDLE_SAFETY_INTERVAL_MS`) have passed
  since the last read. It catches an event the status does not show (remote
  climate, Sentry, a remote wake) when the car stays awake anyway. 20 minutes is
  deliberately longer than the car's own sleep countdown (about 10-15 minutes):
  a car that would sleep is already asleep when the read comes due, so the read
  is skipped and its sleep is never extended. It does not start a hold. The
  value is tuned from the real-car check.
- Status changes that start or restart the hold: asleep to awake; any
  lock-state change (locking too, which catches driving away and locking from
  the phone); any closure (doors, trunks, charge port) changing between open
  and not open, where any state other than CLOSED and UNKNOWN counts as open, so
  a door going ajar is an opening; a fresh start; and an explicit user request
  (the refresh button).
- A change in user presence alone is not a change, so presence never starts a
  hold by itself; it only sets the hold's length. In the owner's experience
  presence means someone sitting in the car, not a phone key approaching
  (another project reports phone-key range; see the research note), and either
  way it alone must not keep resetting the car's sleep countdown.
- A fresh start is the first READY link since the app process or tracking
  started (app start, auto-start after boot or update, tracking switched on) or
  right after pairing / key enrollment. The controller calls
  `onFreshStart(nowMillis)` for it. A reconnect after a dropped link (weak
  signal, car out of range and back) is not a change, and the controller calls
  nothing.
- The transition memory (previous asleep, locked and closure state) survives
  ordinary reconnects and is reset only by a fresh start. A wake, lock change or
  door change that happened while the link was down therefore starts the hold on
  the first status after the reconnect.

A read is due slightly early (1 s slack) so that jitter in the 10 s status
ticks does not turn a 10 s cadence into 20 s.

## Consequences

- Readings (SOC, charging, gear) refresh every 10 s while the car is active or
  recently changed, and go stale once the hold ends and while it sleeps (the
  status log still shows the sleep).
- With nobody present, the car's own countdown runs undisturbed from 1 minute
  after the last change, so it should sleep roughly 11-12 minutes after it.
  When the car reports presence (someone in the car), the hold is 10 minutes,
  so expect sleep roughly 20-25 minutes after the last change. That is
  the price of catching someone getting in and driving off. A real-car check is
  part of the change.
- A car in Sentry or with the climate on is read every 10 s while it stays
  that way, and the car sleeps on its own once both are off: the first reading
  that shows both off ends the active reads, and the idle rules take over (the
  1 or 10 minute hold is not restarted, because reads are not status changes).
  A car that stays awake for another reason the status does not show gets one
  reading every 20 minutes; one that would have slept is never touched by it.
- Each read is four requests now (charge, drive, closures, climate) instead of
  two (ADR-0008), on the same cadence.
- Sentry and climate are only seen on a read, so one that starts between reads
  shows after the next one: within 10 s during a hold or other activity, up to
  20 minutes otherwise (the safety read).
- The envelope gains the closures and climate payloads (ADR-0008); the logs are
  otherwise sparser while idle.

## Considered and deferred

- Android activity recognition to detect driving, instead of waiting for a
  drive to show up in a read.
- An opt-in "stay awake while the phone is near and moving" feature.
