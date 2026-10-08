# Privacy

**TeslaBatteryBLE is local-first. It has no internet permission and sends nothing
anywhere.**

## What the app stores (on your device only)

- Vehicle advertisements seen during scanning (name, address, RSSI) — in memory while scanning
- Your enrolled vehicle keys — stored in app-private storage, device-only by
  default; usable from an Android backup only if you opt in (see below)
- Known cars (VIN, advertised BLE name) so you can reconnect
- Battery history (SOC samples, charge sessions), the car's status readings
  (asleep or awake, locked, someone in the car, doors and other closures) and
  its drive state (gear, speed, power, odometer and, while the car is
  navigating, the navigation destination and route it reports), each with the
  time your phone received it and the signal strength (RSSI) it last measured
  from the car, in local files that stay on this phone
- When your phone's Bluetooth connection to each car was established and when
  it ended, with the signal strength at that moment, in local files that stay
  on this phone. Signal strength hints at how close your phone was to the car,
  so with the times it shows when you were near the car and when you left
- App settings

The app never synchronizes, uploads, or shares anything. Android's system backup
is separate: if you have it enabled, your device may include app data in cloud
backup or device-to-device transfer, except battery history, status readings,
drive state, signal strength and connection times, which the app excludes so
they stay on this device. By default each vehicle key is encrypted
with an AES key that stays in this device's hardware-backed Keystore. Android 11
and below keep the encrypted keys out of backups; on Android 12+ a backup may
include them, but they cannot be decrypted on another phone. Either way, a new
phone requires re-pairing with an NFC card tap. One setting applies to all
paired cars: if you opt in to "Include vehicle keys in Android backup", the keys
can be restored on a new phone from a cloud backup or a device-to-device
transfer. Cloud backups are encrypted with your Google account and device lock,
and on Android 12+ the app additionally requires that encrypted backup is
available; it sets no such condition on a device-to-device transfer. Android 11
and below always keep the keys out of backup. A rooted or forensically extracted
device is outside these protections.
The enrolled keys are charging-manager scoped: they can read vehicle data and
control charging, but cannot unlock or drive, and new keys always need an NFC
card tap plus vehicle confirmation. Uninstalling removes on-device data; a system
backup copy, if any, is managed by your device and Google account settings.

## Permissions and why they exist

| Permission | Why |
| --- | --- |
| Bluetooth scan / connect | Find and talk to the car over BLE |
| Location (fine) | Required by Android for BLE scanning; the app never reads or stores device location (the navigation destination and route in the drive state are what the car reports, not where your phone is) |
| Notifications | Foreground-service status and alerts |
| Foreground service | Keep the car connection and SOC tracking alive while the screen is off |

## Network and third parties

- No analytics, no crash reporting, no ads, no accounts.
- The app's log is kept in memory only: it is never written to disk or sent anywhere,
  and there is no log export. If you share a screenshot of it for support, redact
  VINs and keys first.
- Any future feature requiring network access (for example an optional opt-in data
  export) will be off by default, documented here, and clearly separated from the
  core app. The core app will keep working without it.

## Contact

Questions or concerns: open a [security advisory](https://github.com/dzid26/TeslaBatteryBLE/security/advisories/new)
for sensitive matters, or a regular issue otherwise.
