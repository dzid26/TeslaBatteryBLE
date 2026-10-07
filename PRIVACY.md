# Privacy

**TeslaBatteryBLE is local-first. It has no internet permission and sends nothing
anywhere.**

## What the app stores (on your device only)

- Vehicle advertisements seen during scanning (name, address, RSSI) — in memory while scanning
- Your enrolled vehicle keys — stored in app-private storage, device-only by
  default; included in Android backup only if you opt in (see below)
- Known cars (VIN, advertised BLE name) so you can reconnect
- Battery history (SOC samples, charge sessions) in a local file
- App settings

The app never synchronizes, uploads, or shares anything. Android's system backup
is separate: if you have it enabled, your device may include app data in cloud
backup or device-to-device transfer, except battery history, which the app
excludes so it stays on this device. By default vehicle keys stay encrypted with
this device's hardware-backed Keystore (AES) and are kept out of backups, so a
new phone requires re-pairing with an NFC card tap. One setting applies to all
paired cars: if you opt in to "Include vehicle keys in Android backup", the keys
can be restored on a new phone — Android backups are encrypted with your Google
account and device lock, and on Android 12+ the app additionally requires that
encrypted backup is available; Android 11 and below always keep the keys out of
backup. A rooted or forensically extracted device is outside these protections.
The enrolled keys are charging-manager scoped: they can read vehicle data and
control charging, but cannot unlock or drive, and new keys always need an NFC
card tap plus vehicle confirmation. Uninstalling removes on-device data; a system
backup copy, if any, is managed by your device and Google account settings.

## Permissions and why they exist

| Permission | Why |
| --- | --- |
| Bluetooth scan / connect | Find and talk to the car over BLE |
| Location (fine) | Required by Android for BLE scanning; the app never reads or stores device location |
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
