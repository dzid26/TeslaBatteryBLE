# Real-car log, 2026-10-09

Owner test of the build before the Infotainment session gate fix, one car,
about a day of logcat plus the status log. No VIN, car ID, BLE name or raw log
is recorded here. Context: ADR-0009, `infotainment-polling-and-sleep.md`.

## Link quality

- The phone sat at the edge of BLE range inside the house: about 650
  connect/disconnect cycles in a day. Link up median 15 s, down median 20 s.
- Every drop happened at RSSI below -95 dBm. None happened near the car or in
  it.
- RSSI by position:

  | Position           | RSSI (dBm)    |
  | ------------------ | ------------- |
  | In the house       | -104 to -110  |
  | Approaching        | about -73 to -88 |
  | In the car/driving | -40 to -55    |

## VCSEC user presence

- Readings with presence true: median -49 dBm. Presence false: median -107 dBm.
- It turned on with a door open at about -82 dBm: someone getting in, not just
  a phone key approaching.

## Readings

- `battery_range` moves in steps of 0.0816 mi (multiples seen: 0.1633,
  0.2449, ...).

## Sleep

- With the old build reading every ~15 s, the car still fell asleep about 18
  minutes after a wake (30 s after the last read), and 2-5 minutes after the
  last lock or door change.
- Caveat from the owner: the phone was walking away and the link dropping, so
  this does not yet prove reads do not hold the car awake. A test with a stable
  link is planned.

## Wakes

- Unexplained wakes at 02:15 and 03:32 UTC were likely the owner debugging, not
  the app. The logcat buffer did not cover them, so they stay unattributed.
- Auto-start after an app update worked (`MY_PACKAGE_REPLACED` in logcat).

## Bug found: reads blocked for the whole morning

The log held VCSEC status from the wake at 08:34:45 through unlock, doors and a
20-minute drive, but no charge or drive read. Replaying the statuses through
`InfotainmentPollPolicy` showed it asked for reads (due at the wake and
repeatedly during the drive), so the controller blocked them. The controller
passed `sessionReady = !chargeAfterSession && ...` to the policy; the flag was
set when a read started the Infotainment handshake and cleared only when the
session was established or the link closed. When the handshake never finished
(retries ran out, the car went back to sleep, or the link dropped) the flag
stayed set, the policy never got `sessionReady`, and no new handshake started.
Fixed by `InfotainmentSessionGate`; see ADR-0009.
