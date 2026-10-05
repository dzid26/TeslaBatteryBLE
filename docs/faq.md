# Frequently asked questions

Short answers to common questions about TeslaBatteryBLE. See also
[compatibility](compatibility.md) and the [master plan](master-plan.md).

## Does it need an account or internet?

No. Everything runs on-device over Bluetooth LE. The app has no internet
permission: no Tesla account, no cloud registration, no telemetry, no
analytics. See [PRIVACY.md](../PRIVACY.md).

## Which cars are supported?

Model 3 (all years), Model Y (all years), and Model S/X 2021+ (Palladium).
Pre-2021 Model S/X do not speak this BLE protocol. See
[compatibility](compatibility.md) for the full matrix.

## Why doesn't my car appear while scanning?

Common causes:

- The car advertises a BLE name of the form `S<8 hex chars of sha1(vin)>C`,
  so look for an unfamiliar short name rather than a friendly model name.
- Advertising stops when the car's BLE connection slots are full — "not
  visible" can mean slots are full, not out of range. Move other BLE clients
  (such as keyfobs or phones holding a connection) away or disconnect them.
- The phone must have Bluetooth switched on, and on Android, location
  services must be on for BLE scanning to return results. The app never reads
  or stores device location.

## Does it wake my car?

Only when you explicitly ask it to. The app never wakes the car in the
background just to read battery state. VCSEC status reads work while the car
is asleep; charge state (SOC and related data) requires an awake car, so SOC
reads wait until the car is already awake or you trigger a wake.

## What do the pairing steps look like?

1. Put the car in pairing mode: hold the NFC card on the console until the
   car prompts.
2. The app sends an unsigned `addKey` request over BLE with the default role
   `CHARGING_MANAGER` (least privilege).
3. Confirm on the vehicle screen when it asks. The app then verifies the new
   key appears in the car's key list.

## Where are my keys stored?

In app-private storage on your phone, which other apps cannot read. Vehicle
keys (P-256) are generated on-device. The enrolled key is included in Android's
system backup, so pairing survives a phone change; backup is protected by your
Google account and device lock secret. The default `CHARGING_MANAGER` role limits
impact to read + charge control — it cannot unlock or drive the car, and adding
new keys always needs an NFC card tap plus vehicle confirmation. You can remove
the app's key from the car's key list at any time. Uninstalling the app deletes
local key material. Details:
[session and pairing](protocol/session-and-pairing.md),
[ADR-0005](adr/0005-plaintext-key-storage.md).

## Why is background tracking sometimes killed?

Android OEM battery management can stop background work aggressively. The app
uses a foreground service with a persistent notification to keep SOC tracking
alive while the screen is off. If tracking still stops, exempt the app from
battery optimization in system settings and keep the persistent notification
enabled.

## Is it on F-Droid or Play?

Not yet. Right now the preview APK is published as a rolling GitHub preview
release (debug builds). Signed stable releases and F-Droid are planned — see
the [master plan](master-plan.md).

## How do I report a bug / security issue?

- Bugs and feature requests: use the issue forms in
  [`.github/ISSUE_TEMPLATE/`](../.github/ISSUE_TEMPLATE/). Include car
  model/year, vehicle software version, Android version, and logs with VINs
  and keys redacted.
- Security issues: do **not** open a public issue — follow
  [SECURITY.md](../SECURITY.md) and report privately.
