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
status, whether the charge+drive pair is due. One instance per vehicle link.

- Never while asleep (asleep also drops the hold below), and never to wake the
  car: reads only when VCSEC says awake and an Infotainment session exists.
- A read every 10 s while either of these holds:
  - Active: charging state Charging or Starting, or shift state D, R or N. When
    activity ends, these reads stop right away. This is the one place to extend
    (`isActive()`): sentry and climate join it later.
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

The Infotainment session is requested on demand, by the read itself
(`InfotainmentSessionGate`, `core`), never on connect:

- A due read with no session starts the handshake and goes out when the
  session is established; further due reads while it is in flight do not send
  another request.
- Never while VCSEC says asleep: a sleeping car cannot answer, and the request
  might wake it. On connect only the VCSEC session is requested.
- The wait for the handshake ends when the session is established, the retries
  give up, the car is reported asleep, or the link drops. A wait that outlived
  its handshake once blocked every read for a whole drive (found in the first
  real-car log, `docs/research/real-car-log-2026-10-09.md`).
- The policy's "session ready" input means "a read can be attempted now": a
  session exists or the VIN is valid so one can be started.

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
- A car that stays awake on its own (remote climate, Sentry) gets one reading
  every 20 minutes. One that would have slept is never touched by it.
- The raw log format is unchanged; its records are simply sparser while idle.

## Considered and deferred

- Android activity recognition to detect driving, instead of waiting for a
  drive to show up in a read.
- An opt-in "stay awake while the phone is near and moving" feature.
- Sentry and climate as active states. This is the next PR; it needs
  VehicleState and ClimateState reads, logged raw.
